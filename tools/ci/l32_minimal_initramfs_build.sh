#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/linux"
SOURCE_DIR="${CACHE_ROOT}/linux-${LINUX_VERSION}"
FROZEN_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
BUILD_DIR="${ROOT_DIR}/build/l32-linux-initramfs"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
INIT_BUILD_DIR="${BUILD_DIR}/minimal-init"
INIT_ELF="${INIT_BUILD_DIR}/init"
INIT_SPEC="${INIT_BUILD_DIR}/initramfs.list"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"

mkdir -p "${BUILD_DIR}" "${EVIDENCE_DIR}" "${INIT_BUILD_DIR}"

command -v "${L32_CROSS_COMPILE_PREFIX}gcc" >/dev/null 2>&1 || {
  echo "ERROR: provision the pinned L32 Linux toolchain first" >&2
  exit 20
}

# The source tree and toolchain are already qualified by L32-C. Reuse that
# immutable source rather than introducing another download/extraction path.
"${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" check "${FROZEN_BUILD_DIR}" >/dev/null || {
  echo "ERROR: frozen L32-C Linux build must be present before the initramfs checkpoint" >&2
  exit 21
}
[[ -d "${SOURCE_DIR}" ]] || { echo "ERROR: frozen Linux source cache is missing" >&2; exit 21; }

rm -rf "${INIT_BUILD_DIR}"
mkdir -p "${INIT_BUILD_DIR}"

"${L32_CROSS_COMPILE_PREFIX}gcc" \
  -march=rv32ima_zicsr_zifencei -mabi=ilp32 \
  -nostdlib -static -no-pie \
  -Wl,--build-id=none -Wl,-e,_start -Wl,-Ttext=0x00010000 \
  -Wl,-z,max-page-size=4096 \
  "${ROOT_DIR}/software/l32/minimal_init.S" -o "${INIT_ELF}"

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -l -A "${INIT_ELF}" \
  | tee "${EVIDENCE_DIR}/init-readelf.txt"
file "${INIT_ELF}" | tee "${EVIDENCE_DIR}/init-file.txt"
"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${INIT_ELF}" | grep -q 'Class:[[:space:]]*ELF32'
"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${INIT_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${INIT_ELF}" | grep -q 'Type:[[:space:]]*EXEC'
init_arch="$(${L32_CROSS_COMPILE_PREFIX}readelf -A "${INIT_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
[[ "${init_arch}" == rv32i* && "${init_arch}" == *"_m"* && "${init_arch}" == *"_a"* ]] || {
  echo "ERROR: minimal init lost required RV32IMA ISA: ${init_arch}" >&2; exit 22;
}
if [[ "${init_arch}" =~ _f[0-9] || "${init_arch}" =~ _d[0-9] || "${init_arch}" =~ _c[0-9] ]]; then
  echo "ERROR: minimal init retained unsupported F/D/C extension: ${init_arch}" >&2
  exit 22
fi

# Use a gen_init_cpio specification rather than host mknod. This guarantees
# /dev/console exists for PID 1 even on an unprivileged self-hosted runner.
cat > "${INIT_SPEC}" <<EOF
dir /dev 0755 0 0
nod /dev/console 0600 0 0 c 5 1
file /init ${INIT_ELF} 0755 0 0
EOF

# Keep this userspace checkpoint in a separate object tree so the exact frozen
# kernel-only Image remains untouched and reusable.
rm -rf "${OBJ_DIR}"
mkdir -p "${OBJ_DIR}"
make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  "${LINUX_RV32_DEFCONFIG}" 2>&1 | tee "${BUILD_DIR}/config.log"

"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -d EFI \
  -d RISCV_ISA_C \
  -d FPU \
  -d VGA_CONSOLE \
  -e BLK_DEV_INITRD \
  --set-str INITRAMFS_SOURCE "${INIT_SPEC}"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" olddefconfig \
  2>&1 | tee -a "${BUILD_DIR}/config.log"

grep -qx 'CONFIG_32BIT=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_MMU=y' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_BLK_DEV_INITRD=y' "${OBJ_DIR}/.config"
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${INIT_SPEC}\"" "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux config did not retain the deterministic minimal initramfs source" >&2
  exit 23
}

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" Image 2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || {
  echo "ERROR: initramfs Linux build did not produce vmlinux/Image" >&2
  exit 24
}

cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
sha256sum "${INIT_ELF}" "${INIT_SPEC}" "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "L32_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS"
  echo "linux_version=${LINUX_VERSION}"
  echo "init=${INIT_ELF}"
  echo "init_arch=${init_arch}"
  echo "initramfs_spec=${INIT_SPEC}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
