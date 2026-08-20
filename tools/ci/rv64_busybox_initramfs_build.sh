#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/linux_early.env"
source "${ROOT_DIR}/software/rv64_busybox/manifest.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BASELINE_RESULT="${ROOT_DIR}/build/rv64-linux-early/result.txt"
BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/rv64-busybox"
BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox-src/busybox"
BUILD_DIR="${ROOT_DIR}/build/rv64-linux-busybox"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
ROOTFS_DIR="${BUILD_DIR}/rootfs"
INIT_SCRIPT="${ROOTFS_DIR}/init"
INIT_SPEC="${ROOTFS_DIR}/initramfs.list"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

grep -qx 'RV64_LINUX_EARLY_BUILD_RESULT: status=PASS' "${BASELINE_RESULT}" || \
  fail "qualified RV64 Linux baseline must exist first"
BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
[[ -s "${BASELINE_OBJ}/.config" ]] || fail "qualified RV64 baseline config is missing"
[[ -d "${SOURCE_DIR}" ]] || fail "qualified RV64 Linux source cache is missing"
[[ "$(cat "${SOURCE_DIR}/.aethercore-linux-source-sha256" 2>/dev/null)" == "${RV64_LINUX_SHA256}" ]] || \
  fail "RV64 Linux source cache identity drifted"

grep -qx 'RV64_BUSYBOX_BUILD_RESULT: status=PASS' "${BUSYBOX_BUILD_DIR}/result.txt" || \
  fail "qualified RV64 BusyBox build is required"
[[ -s "${BUSYBOX_ELF}" ]] || fail "qualified RV64 BusyBox ELF is missing"
actual_busybox_sha="$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
recorded_busybox_sha="$(sed -n 's/^busybox_sha256=//p' "${BUSYBOX_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_busybox_sha}" && "${actual_busybox_sha}" == "${recorded_busybox_sha}" ]] || \
  fail "qualified RV64 BusyBox hash drifted"

rm -rf "${BUILD_DIR}"
mkdir -p "${OBJ_DIR}" "${EVIDENCE_DIR}" "${ROOTFS_DIR}"

cat > "${INIT_SCRIPT}" <<'EOF'
#!/bin/sh
/bin/uname -a
/bin/echo "RV64 BUSYBOX SHELL READY"
exec /bin/sh -i
EOF
chmod 0755 "${INIT_SCRIPT}"

cat > "${INIT_SPEC}" <<EOF
dir /bin 0755 0 0
dir /dev 0755 0 0
dir /proc 0555 0 0
dir /sys 0555 0 0
dir /tmp 1777 0 0
nod /dev/console 0600 0 0 c 5 1
nod /dev/null 0666 0 0 c 1 3
file /bin/busybox ${BUSYBOX_ELF} 0755 0 0
slink /bin/sh busybox 0777 0 0
slink /bin/uname busybox 0777 0 0
slink /bin/echo busybox 0777 0 0
file /init ${INIT_SCRIPT} 0755 0 0
EOF

# Derive from the already-qualified unchanged RV64 kernel configuration. This
# checkpoint changes only deterministic initramfs composition and compression.
cp "${BASELINE_OBJ}/.config" "${OBJ_DIR}/.config"
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -e BLK_DEV_INITRD \
  -d INITRAMFS_COMPRESSION_GZIP \
  -e INITRAMFS_COMPRESSION_NONE \
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
  'CONFIG_BLK_DEV_INITRD=y' \
  'CONFIG_INITRAMFS_COMPRESSION_NONE=y'; do
  grep -qx "${required}" "${OBJ_DIR}/.config" || fail "resolved config missing ${required}"
done
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${INIT_SPEC}\"" "${OBJ_DIR}/.config" || \
  fail "Linux config did not retain deterministic RV64 BusyBox initramfs source"
grep -qx '# CONFIG_INITRAMFS_COMPRESSION_GZIP is not set' "${OBJ_DIR}/.config" || \
  fail "RV64 BusyBox initramfs unexpectedly retained gzip compression"
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config" || fail "Linux config retained C"
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config" || fail "Linux config retained FPU"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail "RV64 BusyBox initramfs build did not produce vmlinux/Image"
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || fail "vmlinux is not ELF64"

cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
cp "${INIT_SCRIPT}" "${EVIDENCE_DIR}/init"
cp "${INIT_SPEC}" "${EVIDENCE_DIR}/initramfs.list"
sha256sum "${BUSYBOX_ELF}" "${INIT_SCRIPT}" "${INIT_SPEC}" "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV64_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_source_sha256=${RV64_LINUX_SHA256}"
  echo "busybox_version=${RV64_BUSYBOX_VERSION}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=${actual_busybox_sha}"
  echo "init=${INIT_SCRIPT}"
  echo "initramfs_spec=${INIT_SPEC}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
