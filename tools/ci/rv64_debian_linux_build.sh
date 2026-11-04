#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision the fixed RV64 GCC first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BASELINE_RESULT="${ROOT_DIR}/build/rv64-linux-early/result.txt"
ROOTFS_RESULT="${ROOT_DIR}/build/rv64-debian-rootfs/result.txt"
BUILD_DIR="${ROOT_DIR}/build/rv64-debian-linux"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
grep -qx 'RV64_LINUX_EARLY_BUILD_RESULT: status=PASS' "${BASELINE_RESULT}" || \
  fail 'the unchanged upstream RV64 Linux baseline must qualify first'
grep -qx 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "${ROOTFS_RESULT}" || \
  fail 'the official Debian riscv64 LP64D rootfs must qualify first'
ROOTFS_DIR="$(sed -n 's/^rootfs=//p' "${ROOTFS_RESULT}" | head -n 1)"
[[ -x "${ROOTFS_DIR}/init" ]] || fail 'the Debian dynamic PID1 is missing'
[[ -c "${ROOTFS_DIR}/dev/console" ]] || fail 'the Debian console device node is missing'

BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
[[ -s "${BASELINE_OBJ}/.config" && -s "${BASELINE_OBJ}/vmlinux" ]] || \
  fail 'qualified RV64 baseline build is unavailable'
[[ "$(cat "${SOURCE_DIR}/.aethercore-linux-source-sha256" 2>/dev/null)" == "${RV64_LINUX_SHA256}" ]] || \
  fail 'kernel source differs from frozen upstream Linux 6.6.143'

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
# One upstream source; the distro-specific Kbuild objects are isolated from
# the IMAC PID1 qualification and seeded without shared mutable hard links.
cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"
mkdir -p "${EVIDENCE_DIR}"

"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -e NONPORTABLE -d PORTABLE -d EFI -d VGA_CONSOLE -d RISCV_ISA_V \
  -e RISCV_ISA_C -e FPU -e BLK_DEV_INITRD -e BINFMT_ELF -e BINFMT_SCRIPT \
  -e DEVTMPFS -e TMPFS -e SERIAL_8250 -e SERIAL_8250_CONSOLE \
  -e SERIAL_OF_PLATFORM \
  --set-str INITRAMFS_SOURCE "${ROOTFS_DIR}"

export KBUILD_BUILD_USER="${RV64_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${RV64_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${RV64_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${RV64_LINUX_BUILD_TIMESTAMP}"
export TZ="${RV64_LINUX_BUILD_TZ}"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" olddefconfig \
  2>&1 | tee "${BUILD_DIR}/config.log"

for required in \
  'CONFIG_64BIT=y' 'CONFIG_MMU=y' 'CONFIG_NONPORTABLE=y' \
  'CONFIG_RISCV_ISA_C=y' 'CONFIG_FPU=y' \
  'CONFIG_BLK_DEV_INITRD=y' 'CONFIG_BINFMT_ELF=y' 'CONFIG_BINFMT_SCRIPT=y' \
  'CONFIG_SERIAL_8250_CONSOLE=y'; do
  grep -qx "${required}" "${OBJ_DIR}/.config" || fail "missing distro kernel setting ${required}"
done
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${ROOTFS_DIR}\"" "${OBJ_DIR}/.config" || \
  fail 'Debian rootfs was not selected as the real initramfs source'
grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config" || fail 'unowned EFI path enabled'

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail 'real Debian kernel image not produced'
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || \
  fail 'Linux vmlinux is not RISC-V ELF'
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
sha256sum "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS'
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo 'isa=rv64gc_zicsr_zifencei'
  echo 'abi=lp64d'
  echo "rootfs=${ROOTFS_DIR}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
