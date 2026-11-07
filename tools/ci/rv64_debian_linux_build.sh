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
ROOTFS_STAGE="$(sed -n 's/^stage=//p' "${ROOTFS_RESULT}" | head -n 1)"
case "${ROOTFS_STAGE}" in
  d1|d2|d3|sd1) ;;
  *) fail "Debian rootfs stage is missing or invalid: ${ROOTFS_STAGE}" ;;
esac
[[ "${ROOTFS_DIR}" == "${ROOT_DIR}/build/rv64-debian-rootfs/rootfs" ]] || \
  fail 'the qualified Debian rootfs must reside in its fixed staging directory'
# Bind every resulting Linux Image to the exact qualified rootfs manifest.
# In particular, a D3 initramfs Image must never be silently reused at SD1.
rootfs_result_sha="$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')"
rootfs_tree_sha=''
if [[ "${ROOTFS_STAGE}" == sd1 ]]; then
  [[ -x "${ROOTFS_DIR}/sbin/init" ]] || fail 'the real SD-root Debian /sbin/init is missing'
  [[ ! -e "${ROOTFS_DIR}/init" ]] || fail 'SD1 disk root must not contain the D3 initramfs /init wrapper'
  rootfs_tree_sha="$(sed -n 's/^rootfs_tree_sha256=//p' "${ROOTFS_RESULT}" | head -n 1)"
  [[ "${rootfs_tree_sha}" =~ ^[[:xdigit:]]{64}$ ]] || \
    fail 'SD1 full Debian rootfs manifest is missing its tree identity'
else
  [[ -x "${ROOTFS_DIR}/init" ]] || fail 'the Debian initramfs PID1 is missing'
  [[ -c "${ROOTFS_DIR}/dev/console" ]] || fail 'the Debian console device node is missing'
fi

BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
[[ -s "${BASELINE_OBJ}/.config" && -s "${BASELINE_OBJ}/vmlinux" ]] || \
  fail 'qualified RV64 baseline build is unavailable'
[[ "$(cat "${SOURCE_DIR}/.aethercore-linux-source-sha256" 2>/dev/null)" == "${RV64_LINUX_SHA256}" ]] || \
  fail 'kernel source differs from frozen upstream Linux 6.6.143'

# Diagnostic-only fork: preserve the qualified upstream source and old Image.
# Run only in an isolated checkout. The resulting Image gets a new SHA and
# cannot be paired with an existing Product RTL/DDR checkpoint.
diagnostic_patch_sha=''
case "${AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_PATCH:-0}" in
  0) ;;
  1)
    [[ "${ROOTFS_STAGE}" == sd1 ]] || fail 'GPIO-CS diagnostic patch is SD1-only'
    [[ ! -e "${BUILD_DIR}" ]] ||
      fail 'GPIO-CS diagnostic needs a fresh isolated checkout; existing Debian Image staging must not be overwritten'
    diagnostic_patch="${ROOT_DIR}/tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
    [[ -s "${diagnostic_patch}" ]] || fail 'pinned GPIO-CS diagnostic patch missing'
    diagnostic_patch_sha="$(sha256sum "${diagnostic_patch}" | awk '{print $1}')"
    [[ "${diagnostic_patch_sha}" == ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638 ]] ||
      fail 'diagnostic Linux patch SHA changed without review'
    mkdir -p "${ROOT_DIR}/build"
    diagnostic_source="$(mktemp -d "${ROOT_DIR}/build/rv64-sd1-gpio-source.XXXXXXXX")"
    cp -a --reflink=auto "${SOURCE_DIR}/." "${diagnostic_source}/"
    git -C "${diagnostic_source}" apply --check "${diagnostic_patch}" ||
      fail 'diagnostic patch does not apply to frozen upstream Linux'
    git -C "${diagnostic_source}" apply "${diagnostic_patch}" ||
      fail 'isolated diagnostic kernel patch application failed'
    SOURCE_DIR="${diagnostic_source}"
    echo "RV64_SD1_DIAGNOSTIC_LINUX_PATCH_APPLIED sha256=${diagnostic_patch_sha} source=${SOURCE_DIR}"
    ;;
  *) fail 'AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_PATCH must be 0 or 1' ;;
esac

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
# One upstream source; the distro-specific Kbuild objects are isolated from
# the IMAC PID1 qualification and seeded without shared mutable hard links.
cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"
mkdir -p "${EVIDENCE_DIR}"

initramfs_source="${ROOTFS_DIR}"
if [[ "${ROOTFS_STAGE}" == sd1 ]]; then
  # The *entire* genuine Debian tree belongs on the SD ext4 partition. No
  # full rootfs is linked into the Linux Image, and no /init wrapper remains.
  initramfs_source=''
fi
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -e NONPORTABLE -d PORTABLE -d EFI -d VGA_CONSOLE -d RISCV_ISA_V \
  -e RISCV_ISA_C -e FPU -e BLK_DEV_INITRD -e BINFMT_ELF -e BINFMT_SCRIPT \
  -e DEVTMPFS -e TMPFS -e SERIAL_8250 -e SERIAL_8250_CONSOLE \
  -e SERIAL_OF_PLATFORM \
  --set-str INITRAMFS_SOURCE "${initramfs_source}"

if [[ "${ROOTFS_STAGE}" != sd1 ]]; then
  # The RAM-root Product target embeds the *entire* Debian cpio.  Linux's
  # initramfs_async unpack is a major silent boot path in RTL; bypassing gzip
  # removes its guest-CPU decompression work without removing any Debian file.
  # SD1 uses an ext4 block root and has no built-in initramfs to compress.
  # The firmware/DTB overlap check in rv64_minimal_init_payload_build.sh is
  # mandatory because this trades smaller firmware for a larger raw Image.
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
    -e INITRAMFS_COMPRESSION_NONE -d INITRAMFS_COMPRESSION_GZIP
fi

# The frozen upstream RISC-V defconfig is intentionally debug-heavy.  It
# enables the 1000-round plist selftest, page-table selftests and allocator /
# scheduler assertions that are not Debian ABI or device requirements.  Those
# tests appeared on the critical path of real Product RTL boots and can
# obscure whether the distribution reached PID1.  Keep the upstream
# baseline untouched; only the staged Debian Image selects a functional boot
# profile.  A diagnostic boot remains available through the caller's kernel
# command line (initcall_debug loglevel=8), without re-enabling these tests.
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -d DEBUG_PLIST -d DEBUG_PAGEALLOC -d DEBUG_VM -d DEBUG_VM_PGTABLE \
  -d SCHED_DEBUG -d DEBUG_RT_MUTEXES -d DEBUG_SPINLOCK \
  -d DEBUG_MUTEXES -d DEBUG_RWSEMS -d DEBUG_LIST -d MEMTEST

# Linux's tiny PID1 image does not establish userspace filesystem and
# service capability. The D2/D3 distribution stages deliberately request
# the host kernel contracts their real Debian programs need, while leaving
# the frozen D1 Image configuration independently reproducible.
if [[ "${ROOTFS_STAGE}" == d2 || "${ROOTFS_STAGE}" == d3 || "${ROOTFS_STAGE}" == sd1 ]]; then
  # In Linux 6.6, fs/devpts/ is built by CONFIG_UNIX98_PTYS; there is no
  # independently selectable CONFIG_DEVPTS_FS symbol to demand after olddefconfig.
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
    -e PROC_FS -e SYSFS -e TMPFS -e DEVTMPFS -e DEVTMPFS_MOUNT \
    -e UNIX98_PTYS -e TTY -e FUTEX \
    -e NET -e UNIX -e POSIX_TIMERS -e SYSVIPC \
    -e EPOLL -e EVENTFD -e SIGNALFD -e TIMERFD \
    -e INOTIFY_USER -e FHANDLE
fi
if [[ "${ROOTFS_STAGE}" == d3 || "${ROOTFS_STAGE}" == sd1 ]]; then
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
    -e CGROUPS -e CGROUP_PIDS -e NAMESPACES -e PID_NS -e UTS_NS -e IPC_NS \
    -e USER_NS -e NET_NS -e EXT4_FS -e BLOCK -e BLK_DEV_LOOP \
    -e TMPFS_POSIX_ACL -e AUTOFS_FS -e BINFMT_MISC \
    -e INET -e IPV6 -e SECCOMP \
    -e OF -e GPIOLIB -e GPIO_ALTERA -e SPI -e SPI_OC_TINY \
    -e MMC -e MMC_BLOCK -e MMC_SPI
fi
if [[ "${ROOTFS_STAGE}" == sd1 ]]; then
  # A raw SD card with an MBR p1 must be recognized before root mount.
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" -e MSDOS_PARTITION
fi

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
grep -Fqx "CONFIG_INITRAMFS_SOURCE=\"${initramfs_source}\"" "${OBJ_DIR}/.config" || \
  fail "Debian ${ROOTFS_STAGE} kernel selected an incorrect initramfs source"
if [[ "${ROOTFS_STAGE}" != sd1 ]]; then
  grep -Fqx 'CONFIG_INITRAMFS_COMPRESSION_NONE=y' "${OBJ_DIR}/.config" || \
    fail "Debian ${ROOTFS_STAGE} kernel failed to select uncompressed initramfs"
  if grep -Eq '^CONFIG_INITRAMFS_COMPRESSION_(GZIP|BZIP2|LZMA|XZ|LZO|LZ4|ZSTD)=y$' "${OBJ_DIR}/.config"; then
    fail "Debian ${ROOTFS_STAGE} unexpectedly compressed its built-in initramfs"
  fi
fi
grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config" || fail 'unowned EFI path enabled'
for disabled in \
  DEBUG_PLIST DEBUG_PAGEALLOC DEBUG_VM DEBUG_VM_PGTABLE \
  SCHED_DEBUG DEBUG_RT_MUTEXES DEBUG_SPINLOCK \
  DEBUG_MUTEXES DEBUG_RWSEMS DEBUG_LIST MEMTEST; do
  # Kconfig omits a symbol entirely when its dependencies are disabled.
  if grep -Eq "^CONFIG_${disabled}=(y|m)$" "${OBJ_DIR}/.config"; then
    fail "Debian ${ROOTFS_STAGE} unexpectedly enabled costly debug selftest ${disabled}"
  fi
done
if [[ "${ROOTFS_STAGE}" == d2 || "${ROOTFS_STAGE}" == d3 || "${ROOTFS_STAGE}" == sd1 ]]; then
  for required in \
    'CONFIG_PROC_FS=y' 'CONFIG_SYSFS=y' 'CONFIG_TMPFS=y' \
    'CONFIG_DEVTMPFS=y' 'CONFIG_DEVTMPFS_MOUNT=y' \
    'CONFIG_UNIX98_PTYS=y' \
    'CONFIG_NET=y' 'CONFIG_UNIX=y' 'CONFIG_FUTEX=y' \
    'CONFIG_POSIX_TIMERS=y' 'CONFIG_SYSVIPC=y' \
    'CONFIG_EPOLL=y' 'CONFIG_EVENTFD=y' 'CONFIG_SIGNALFD=y' \
    'CONFIG_TIMERFD=y' 'CONFIG_INOTIFY_USER=y' 'CONFIG_FHANDLE=y'; do
    grep -qx "${required}" "${OBJ_DIR}/.config" || \
      fail "missing ${ROOTFS_STAGE} userspace kernel setting ${required}"
  done
fi
if [[ "${ROOTFS_STAGE}" == d3 || "${ROOTFS_STAGE}" == sd1 ]]; then
  for required in \
    'CONFIG_CGROUPS=y' 'CONFIG_CGROUP_PIDS=y' 'CONFIG_NAMESPACES=y' \
    'CONFIG_PID_NS=y' 'CONFIG_UTS_NS=y' 'CONFIG_IPC_NS=y' \
    'CONFIG_USER_NS=y' 'CONFIG_NET_NS=y' 'CONFIG_EXT4_FS=y' \
    'CONFIG_BLOCK=y' 'CONFIG_BLK_DEV_LOOP=y' 'CONFIG_TMPFS_POSIX_ACL=y' \
    'CONFIG_AUTOFS_FS=y' 'CONFIG_BINFMT_MISC=y' \
    'CONFIG_INET=y' 'CONFIG_IPV6=y' 'CONFIG_SECCOMP=y' \
    'CONFIG_OF=y' 'CONFIG_GPIOLIB=y' 'CONFIG_GPIO_ALTERA=y' \
    'CONFIG_SPI=y' 'CONFIG_SPI_OC_TINY=y' \
    'CONFIG_MMC=y' 'CONFIG_MMC_BLOCK=y' 'CONFIG_MMC_SPI=y'; do
    grep -qx "${required}" "${OBJ_DIR}/.config" || \
      fail "missing ${ROOTFS_STAGE} service kernel setting ${required}"
  done
fi
if [[ "${ROOTFS_STAGE}" == sd1 ]]; then
  grep -qx 'CONFIG_MSDOS_PARTITION=y' "${OBJ_DIR}/.config" || \
    fail 'SD1 raw MBR root partition cannot be recognized'
fi

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail 'real Debian kernel image not produced'
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || \
  fail 'Linux vmlinux is not RISC-V ELF'
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
if [[ -n "${diagnostic_patch_sha}" ]]; then
  cp "${ROOT_DIR}/tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch" \
    "${EVIDENCE_DIR}/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
fi
sha256sum "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"
if [[ -n "${diagnostic_patch_sha}" ]]; then
  sha256sum "${EVIDENCE_DIR}/linux-6.6.143-mmc-spi-gpio-cs-off.patch" >> "${EVIDENCE_DIR}/sha256.txt"
fi
[[ "$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')" == "${rootfs_result_sha}" ]] || \
  fail 'qualified Debian rootfs manifest changed while building the Linux Image'

{
  echo 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS'
  echo "stage=${ROOTFS_STAGE}"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo 'isa=rv64gc_zicsr_zifencei'
  echo 'abi=lp64d'
  echo "rootfs=${ROOTFS_DIR}"
  echo "rootfs_result_sha256=${rootfs_result_sha}"
  if [[ "${ROOTFS_STAGE}" == sd1 ]]; then
    echo "rootfs_tree_sha256=${rootfs_tree_sha}"
  fi
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
  echo "config_sha256=$(sha256sum "${EVIDENCE_DIR}/resolved.config" | awk '{print $1}')"
  if [[ -n "${diagnostic_patch_sha}" ]]; then
    echo 'qualification=SD1_GPIO_CS_DIAGNOSTIC_ONLY_NOT_FROZEN_PRODUCT'
    echo "upstream_linux_source_sha256=${RV64_LINUX_SHA256}"
    echo "kernel_patch_applied=1"
    echo "diagnostic_patch_sha256=${diagnostic_patch_sha}"
  fi
} | tee "${BUILD_DIR}/result.txt"
