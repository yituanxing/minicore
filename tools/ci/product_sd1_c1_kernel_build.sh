#!/usr/bin/env bash
# Build a rootfs-agnostic C1 kernel from the already qualified SD1 Linux
# kernel configuration.  A tiny /init is embedded; Debian/Alpine stay on SD.
# This is opt-in and does not mutate the frozen ordinary SD1 kernel artifacts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/software/rv64/linux_early.env"
BUILD="$ROOT/build/product-sd1-c1-kernel"
SEED="$ROOT/build/rv64-debian-linux"
BASE_RESULT="$SEED/result.txt"
C1_INIT="$ROOT/build/product-sd1-c1-initramfs"
KERNEL_SOURCE="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/rv64/linux/linux-${RV64_LINUX_VERSION}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() { echo "PRODUCT_SD1_C1_KERNEL_REJECTED: $*" >&2; exit 2; }
[[ $# == 1 && "$1" == prepare ]] || fail 'usage: product_sd1_c1_kernel_build.sh prepare'
[[ -f "$BASE_RESULT" && -f "$SEED/obj/.config" ]] ||
  fail 'qualified SD1 Linux build required first'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$BASE_RESULT" ||
  fail 'existing SD1 Linux evidence is not PASS'
grep -Fxq 'stage=sd1' "$BASE_RESULT" || fail 'C1 requires SD1 Linux as kernel base'
[[ -s "$C1_INIT/init" && -s "$C1_INIT/initramfs.list" ]] ||
  fail 'first build the rootfs-agnostic C1 guest /init'
[[ ! -e "$BUILD" ]] || fail 'refusing to overwrite an existing C1 kernel'
[[ "$CROSS" == /* && -x "${CROSS}gcc" && -x "${CROSS}readelf" ]] ||
  fail 'absolute fixed RV64 Linux cross compiler required'
[[ -s "$KERNEL_SOURCE/Makefile" && -x "$KERNEL_SOURCE/scripts/config" ]] ||
  fail 'verified upstream Linux source required'
[[ "$(cat "$KERNEL_SOURCE/.aethercore-linux-source-sha256" 2>/dev/null)" == "$RV64_LINUX_SHA256" ]] ||
  fail 'upstream Linux source differs from frozen source SHA'

mkdir -p "$BUILD"
cp -a --reflink=auto "$SEED/obj" "$BUILD/obj"
OBJ="$BUILD/obj"
"$KERNEL_SOURCE/scripts/config" --file "$OBJ/.config" \
  -e BLK_DEV_INITRD -e INITRAMFS_COMPRESSION_NONE \
  -d INITRAMFS_COMPRESSION_GZIP \
  --set-str INITRAMFS_SOURCE "$C1_INIT/initramfs.list"

export KBUILD_BUILD_USER="$RV64_LINUX_BUILD_USER"
export KBUILD_BUILD_HOST="$RV64_LINUX_BUILD_HOST"
export KBUILD_BUILD_VERSION="$RV64_LINUX_BUILD_VERSION"
export KBUILD_BUILD_TIMESTAMP="$RV64_LINUX_BUILD_TIMESTAMP"
export TZ="$RV64_LINUX_BUILD_TZ"

make -C "$KERNEL_SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" \
  olddefconfig > "$BUILD/config.log" 2>&1 ||
  fail 'C1 kernel Kconfig resolution failed'
grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"$C1_INIT/initramfs.list\"" "$OBJ/.config" ||
  fail 'kernel silently dropped rootfs-independent C1 initramfs'
grep -Fxq 'CONFIG_INITRAMFS_COMPRESSION_NONE=y' "$OBJ/.config" ||
  fail 'C1 early initramfs must not spend guest cycles decompressing'
for required in 'CONFIG_EXT4_FS=y' 'CONFIG_MMC_BLOCK=y' 'CONFIG_MMC_SPI=y' \
                'CONFIG_DEVTMPFS=y' 'CONFIG_PROC_FS=y'; do
  grep -Fxq "$required" "$OBJ/.config" || fail "missing C1 block/root handoff: $required"
done

make -C "$KERNEL_SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" \
  -j"$JOBS" Image > "$BUILD/build.log" 2>&1 || fail 'C1 Linux Image build failed'
IMAGE="$OBJ/arch/riscv/boot/Image"
[[ -s "$IMAGE" && -s "$OBJ/vmlinux" ]] || fail 'C1 Linux image absent'
"${CROSS}readelf" -h "$OBJ/vmlinux" | grep -q 'Machine:[[:space:]]*RISC-V' ||
  fail 'C1 kernel must be RISC-V'

# This result deliberately contains no Debian/rootfs/media SHA. An external
# C1 resume gate validates full RTL, kernel, DTB, SD read-ledger and firmware;
# this immutable Linux Image can be reused with a different guest ext4 root.
{
  echo 'PRODUCT_SD1_C1_KERNEL_BUILD_RESULT: status=BUILT_UNQUALIFIED'
  echo "kernel_sha256=$(sha256sum "$IMAGE" | cut -d' ' -f1)"
  echo "init_sha256=$(sha256sum "$C1_INIT/init" | cut -d' ' -f1)"
  echo "initramfs_spec_sha256=$(sha256sum "$C1_INIT/initramfs.list" | cut -d' ' -f1)"
  echo "config_sha256=$(sha256sum "$OBJ/.config" | cut -d' ' -f1)"
  echo 'rootfs_binding=NONE'
  echo 'qualification=NOT_YET_VERIFIED_ON_PRODUCT_RTL_OR_QEMU'
} > "$BUILD/result.txt"
echo 'PRODUCT_SD1_C1_KERNEL_BUILT_UNQUALIFIED rootfs_binding=NONE'
