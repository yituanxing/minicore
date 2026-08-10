#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/linux"
SOURCE_DIR="${CACHE_ROOT}/linux-${LINUX_VERSION}"
FROZEN_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-busybox"
BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox-src/busybox"
PROBE_BUILD_DIR="${ROOT_DIR}/build/l32-runtime-probe"
PROBE_ELF="${PROBE_BUILD_DIR}/l32-runtime-probe"
REAL_BUILD_DIR="${ROOT_DIR}/build/l32-real-programs"
LUA_ELF="${REAL_BUILD_DIR}/lua"
SQLITE_ELF="${REAL_BUILD_DIR}/sqlite-smoke"
BASH_ELF="${REAL_BUILD_DIR}/bash"
LUA_SMOKE="${REAL_BUILD_DIR}/lua-smoke.lua"
BASH_SMOKE="${REAL_BUILD_DIR}/bash-smoke.sh"
BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox"
OBJ_DIR="${BUILD_DIR}/obj"
OBJ_MARKER="${OBJ_DIR}/.aethercore-object-inputs"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
ROOTFS_DIR="${BUILD_DIR}/rootfs"
INIT_SCRIPT="${ROOTFS_DIR}/init"
INIT_SPEC="${ROOTFS_DIR}/initramfs.list"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"

mkdir -p "${BUILD_DIR}" "${EVIDENCE_DIR}" "${ROOTFS_DIR}"
command -v "${L32_CROSS_COMPILE_PREFIX}gcc" >/dev/null 2>&1 || { echo "ERROR: provision the pinned L32 Linux toolchain first" >&2; exit 20; }

"${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" check "${FROZEN_BUILD_DIR}" >/dev/null || {
  echo "ERROR: frozen L32-C Linux build must be present before BusyBox initramfs" >&2
  exit 21
}
[[ -d "${SOURCE_DIR}" ]] || { echo "ERROR: frozen Linux source cache is missing" >&2; exit 21; }

grep -qx 'L32_BUSYBOX_BUILD_RESULT: status=PASS' "${BUSYBOX_BUILD_DIR}/result.txt" || { echo "ERROR: qualified L32 BusyBox build is required" >&2; exit 22; }
[[ -s "${BUSYBOX_ELF}" ]] || { echo "ERROR: qualified BusyBox ELF is missing" >&2; exit 22; }
actual_busybox_sha="$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
recorded_busybox_sha="$(sed -n 's/^busybox_sha256=//p' "${BUSYBOX_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_busybox_sha}" && "${actual_busybox_sha}" == "${recorded_busybox_sha}" ]] || { echo "ERROR: qualified BusyBox ELF hash drifted" >&2; exit 23; }

grep -qx 'L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS' "${PROBE_BUILD_DIR}/result.txt" || { echo "ERROR: qualified L32 runtime probe is required" >&2; exit 23; }
[[ -s "${PROBE_ELF}" ]] || { echo "ERROR: qualified runtime probe ELF is missing" >&2; exit 23; }
actual_probe_sha="$(sha256sum "${PROBE_ELF}" | awk '{print $1}')"
recorded_probe_sha="$(sed -n 's/^probe_sha256=//p' "${PROBE_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_probe_sha}" && "${actual_probe_sha}" == "${recorded_probe_sha}" ]] || { echo "ERROR: qualified runtime probe ELF hash drifted" >&2; exit 23; }

grep -qx 'L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS' "${REAL_BUILD_DIR}/result.txt" || { echo "ERROR: qualified L32 real programs are required" >&2; exit 23; }
for real in "${LUA_ELF}" "${SQLITE_ELF}" "${BASH_ELF}" "${LUA_SMOKE}" "${BASH_SMOKE}"; do
  [[ -s "${real}" ]] || { echo "ERROR: missing qualified real-program input ${real}" >&2; exit 23; }
done

rm -rf "${ROOTFS_DIR}"
mkdir -p "${ROOTFS_DIR}"
cat > "${INIT_SCRIPT}" <<'EOF'
#!/bin/sh
/bin/uname -a
echo "L32 BUSYBOX SHELL READY"
exec /bin/sh -i
EOF
chmod 0755 "${INIT_SCRIPT}"

# gen_init_cpio avoids privileged mknod and keeps the image deterministic.
# The minimal shell remains only a launcher. CPU semantics are validated by the
# small static probe plus unchanged third-party applications under /opt/l32.
cat > "${INIT_SPEC}" <<EOF
dir /bin 0755 0 0
dir /dev 0755 0 0
dir /proc 0555 0 0
dir /sys 0555 0 0
dir /tmp 1777 0 0
dir /opt 0755 0 0
dir /opt/l32 0755 0 0
nod /dev/console 0600 0 0 c 5 1
file /bin/busybox ${BUSYBOX_ELF} 0755 0 0
file /bin/l32-runtime-probe ${PROBE_ELF} 0755 0 0
file /opt/l32/lua ${LUA_ELF} 0755 0 0
file /opt/l32/lua-smoke.lua ${LUA_SMOKE} 0644 0 0
file /opt/l32/sqlite-smoke ${SQLITE_ELF} 0755 0 0
file /opt/l32/bash ${BASH_ELF} 0755 0 0
file /opt/l32/bash-smoke.sh ${BASH_SMOKE} 0755 0 0
slink /bin/sh busybox 0777 0 0
slink /bin/uname busybox 0777 0 0
slink /bin/echo busybox 0777 0 0
slink /bin/printf busybox 0777 0 0
file /init ${INIT_SCRIPT} 0755 0 0
EOF

# Preserve this variant's Kbuild object tree across workload changes. Kbuild
# only has to regenerate the embedded initramfs and relink the final image.
obj_inputs="${LINUX_SHA256}:${L32_TOOLCHAIN_VERSION}:${L32_CROSS_COMPILE_PREFIX}"
if [[ -d "${OBJ_DIR}" ]] && { [[ ! -f "${OBJ_MARKER}" ]] || [[ "$(cat "${OBJ_MARKER}" 2>/dev/null)" != "${obj_inputs}" ]]; }; then
  rm -rf "${OBJ_DIR}"
fi
mkdir -p "${OBJ_DIR}"
printf '%s\n' "${obj_inputs}" > "${OBJ_MARKER}"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" "${LINUX_RV32_DEFCONFIG}" 2>&1 | tee "${BUILD_DIR}/config.log"
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -d EFI -d RISCV_ISA_C -d FPU -d VGA_CONSOLE -e BLK_DEV_INITRD \
  -d INITRAMFS_COMPRESSION_GZIP -e INITRAMFS_COMPRESSION_NONE \
  --set-str INITRAMFS_SOURCE "${INIT_SPEC}"
make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" olddefconfig 2>&1 | tee -a "${BUILD_DIR}/config.log"

grep -qx 'CONFIG_32BIT=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_MMU=y' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_BLK_DEV_INITRD=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_INITRAMFS_COMPRESSION_NONE=y' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_INITRAMFS_COMPRESSION_GZIP is not set' "${OBJ_DIR}/.config"
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${INIT_SPEC}\"" "${OBJ_DIR}/.config" || { echo "ERROR: Linux config did not retain the BusyBox initramfs source" >&2; exit 24; }

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" -j"${JOBS}" Image 2>&1 | tee "${BUILD_DIR}/linux-build.log"
VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || { echo "ERROR: BusyBox initramfs Linux build did not produce vmlinux/Image" >&2; exit 25; }

cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
cp "${INIT_SPEC}" "${EVIDENCE_DIR}/initramfs.list"
cp "${INIT_SCRIPT}" "${EVIDENCE_DIR}/init"
sha256sum \
  "${BUSYBOX_ELF}" "${PROBE_ELF}" "${LUA_ELF}" "${SQLITE_ELF}" "${BASH_ELF}" \
  "${LUA_SMOKE}" "${BASH_SMOKE}" "${INIT_SCRIPT}" "${INIT_SPEC}" \
  "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS"
  echo "linux_version=${LINUX_VERSION}"
  echo "busybox_version=${BUSYBOX_VERSION}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=${actual_busybox_sha}"
  echo "runtime_probe=${PROBE_ELF}"
  echo "runtime_probe_sha256=${actual_probe_sha}"
  echo "lua_sha256=$(sha256sum "${LUA_ELF}" | awk '{print $1}')"
  echo "sqlite_sha256=$(sha256sum "${SQLITE_ELF}" | awk '{print $1}')"
  echo "bash_sha256=$(sha256sum "${BASH_ELF}" | awk '{print $1}')"
  echo "init=${INIT_SCRIPT}"
  echo "initramfs_spec=${INIT_SPEC}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
