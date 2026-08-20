#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BASELINE_RESULT="${ROOT_DIR}/build/rv64-linux-early/result.txt"
BUILD_DIR="${ROOT_DIR}/build/rv64-linux-initramfs"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
INIT_BUILD_DIR="${BUILD_DIR}/minimal-init"
INIT_ELF="${INIT_BUILD_DIR}/init"
INIT_SPEC="${INIT_BUILD_DIR}/initramfs.list"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for tool in gcc readelf; do
  [[ -x "${CROSS}${tool}" ]] || fail "missing pinned RV64 Linux tool ${CROSS}${tool}"
done
[[ "$(${CROSS}gcc -dumpmachine)" == riscv64*linux* ]] || fail "unexpected RV64 Linux target"
[[ "$(${CROSS}gcc -dumpfullversion)" == "13.3.0" ]] || fail "unexpected GCC version"

grep -qx 'RV64_LINUX_EARLY_BUILD_RESULT: status=PASS' "${BASELINE_RESULT}" || \
  fail "qualified RV64 Linux baseline must exist first"
BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
[[ -s "${BASELINE_OBJ}/.config" ]] || fail "qualified RV64 baseline config is missing"
[[ -s "${BASELINE_OBJ}/vmlinux" ]] || fail "qualified RV64 baseline vmlinux is missing"
[[ -d "${SOURCE_DIR}" ]] || fail "qualified RV64 Linux source cache is missing"
[[ "$(cat "${SOURCE_DIR}/.aethercore-linux-source-sha256" 2>/dev/null)" == "${RV64_LINUX_SHA256}" ]] || \
  fail "RV64 Linux source cache identity drifted"

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# The minimal-initramfs kernel differs from the already-qualified baseline only
# by BLK_DEV_INITRD and INITRAMFS_SOURCE. Seed a private object tree from the
# qualified baseline instead of compiling the unchanged kernel twice. GNU cp
# uses a reflink when the hosted filesystem supports it and falls back to an
# ordinary byte copy otherwise; hard links are deliberately not used because
# Kbuild must never mutate the qualified baseline cache through shared inodes.
printf 'Seeding RV64 initramfs object tree from qualified baseline: %s\n' "${BASELINE_OBJ}"
cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"
mkdir -p "${EVIDENCE_DIR}" "${INIT_BUILD_DIR}"

"${CROSS}gcc" \
  -march=rv64ima_zicsr_zifencei -mabi=lp64 \
  -nostdlib -static -no-pie \
  -Wl,--build-id=none -Wl,-e,_start -Wl,-Ttext=0x00010000 \
  -Wl,-z,max-page-size=4096 \
  "${ROOT_DIR}/software/rv64_userspace/minimal_init.S" -o "${INIT_ELF}"

"${CROSS}readelf" -h -l -A "${INIT_ELF}" | tee "${EVIDENCE_DIR}/init-readelf.txt"
file "${INIT_ELF}" | tee "${EVIDENCE_DIR}/init-file.txt"
"${CROSS}readelf" -h "${INIT_ELF}" | grep -q 'Class:[[:space:]]*ELF64'
"${CROSS}readelf" -h "${INIT_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
"${CROSS}readelf" -h "${INIT_ELF}" | grep -q 'Type:[[:space:]]*EXEC'
init_arch="$(${CROSS}readelf -A "${INIT_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
[[ "${init_arch}" == rv64i* && "${init_arch}" == *"_m"* && "${init_arch}" == *"_a"* ]] || \
  fail "minimal init lost required RV64IMA ISA: ${init_arch}"
if [[ "${init_arch}" =~ _c[0-9] || "${init_arch}" =~ _f[0-9] || "${init_arch}" =~ _d[0-9] || "${init_arch}" =~ _v[0-9] ]]; then
  fail "minimal init retained unsupported C/F/D/V extension: ${init_arch}"
fi

cat > "${INIT_SPEC}" <<EOF
dir /dev 0755 0 0
nod /dev/console 0600 0 0 c 5 1
file /init ${INIT_ELF} 0755 0 0
EOF

# Preserve the already-qualified RV64 kernel configuration byte-for-byte as the
# starting point. This checkpoint changes only the deterministic initramfs
# options needed to execute PID 1.
cp "${BASELINE_OBJ}/.config" "${OBJ_DIR}/.config"
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -e BLK_DEV_INITRD \
  --set-str INITRAMFS_SOURCE "${INIT_SPEC}"

export KBUILD_BUILD_USER="${RV64_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${RV64_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${RV64_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${RV64_LINUX_BUILD_TIMESTAMP}"
export TZ="${RV64_LINUX_BUILD_TZ}"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" olddefconfig \
  2>&1 | tee "${BUILD_DIR}/config.log"

for required in \
  'CONFIG_64BIT=y' \
  'CONFIG_MMU=y' \
  'CONFIG_NONPORTABLE=y' \
  'CONFIG_BLK_DEV_INITRD=y'; do
  grep -qx "${required}" "${OBJ_DIR}/.config" || fail "resolved config missing ${required}"
done
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${INIT_SPEC}\"" "${OBJ_DIR}/.config" || \
  fail "Linux config did not retain deterministic RV64 initramfs source"
grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config" || fail "Linux config retained EFI"
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config" || fail "Linux config retained C"
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config" || fail "Linux config retained FPU"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail "RV64 initramfs build did not produce vmlinux/Image"
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || fail "vmlinux is not ELF64"

cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
sha256sum "${INIT_ELF}" "${INIT_SPEC}" "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV64_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_source_sha256=${RV64_LINUX_SHA256}"
  echo "baseline_image=${BASELINE_IMAGE}"
  echo "baseline_object_seed=${BASELINE_OBJ}"
  echo "init=${INIT_ELF}"
  echo "init_arch=${init_arch}"
  echo "initramfs_spec=${INIT_SPEC}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
