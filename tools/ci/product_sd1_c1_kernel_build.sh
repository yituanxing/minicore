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
C1_GPIO_CS_PATCH="$ROOT/tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
C1_GPIO_CS_PATCH_SHA=ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638
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
[[ "$RV64_LINUX_VERSION" == 6.6.143 && -s "$C1_GPIO_CS_PATCH" &&
   "$(sha256sum "$C1_GPIO_CS_PATCH" | cut -d' ' -f1)" == "$C1_GPIO_CS_PATCH_SHA" ]] ||
  fail 'C1 physical GPIO-CS initialization requires exact reviewed Linux patch'
command -v patch >/dev/null || fail 'patch(1) is required for isolated C1 Linux source'

mkdir -p "$BUILD"
# The already-reviewed GPIO-CS correction must also be present in C1's own
# Linux Image. Without it, upstream mmc_spi issues CMD0 before the physical
# card has seen 74 CS-high/DI-high clocks. A genuine warm RTL run reached
# exactly this fail-closed rejection at 332M. Never mutate the signed official
# SD1 Linux source or apply the patch to the sealed official Image.
C1_SOURCE="$BUILD/linux-source"
original_driver="$KERNEL_SOURCE/drivers/mmc/host/mmc_spi.c"
upstream_driver_sha="$(sha256sum "$original_driver" | cut -d' ' -f1)"
cp -a --reflink=auto "$KERNEL_SOURCE" "$C1_SOURCE"
[[ "$(realpath "$KERNEL_SOURCE")" != "$(realpath "$C1_SOURCE")" && \
   "$(stat -c '%d:%i' "$original_driver")" != \
   "$(stat -c '%d:%i' "$C1_SOURCE/drivers/mmc/host/mmc_spi.c")" ]] ||
  fail 'C1 patched source cannot alias immutable upstream Linux'
patch --dry-run --batch --forward -p1 -d "$C1_SOURCE" < "$C1_GPIO_CS_PATCH" >/dev/null ||
  fail 'reviewed C1 GPIO-CS patch does not apply to pinned upstream Linux'
patch --batch --forward -p1 -d "$C1_SOURCE" < "$C1_GPIO_CS_PATCH" >/dev/null ||
  fail 'isolated C1 GPIO-CS patch failed'
patched_driver_sha="$(sha256sum "$C1_SOURCE/drivers/mmc/host/mmc_spi.c" | cut -d' ' -f1)"
[[ "$upstream_driver_sha" != "$patched_driver_sha" &&
   "$(sha256sum "$original_driver" | cut -d' ' -f1)" == "$upstream_driver_sha" ]] ||
  fail 'GPIO-CS patch did not modify isolated C1 source or changed upstream bytes'
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

make -C "$C1_SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" \
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

make -C "$C1_SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" \
  -j"$JOBS" Image > "$BUILD/build.log" 2>&1 || fail 'C1 Linux Image build failed'
IMAGE="$OBJ/arch/riscv/boot/Image"
[[ -s "$IMAGE" && -s "$OBJ/vmlinux" ]] || fail 'C1 Linux image absent'
[[ -s "$OBJ/drivers/mmc/host/mmc_spi.o" &&
   -s "$SEED/obj/drivers/mmc/host/mmc_spi.o" ]] ||
  fail 'C1 mmc_spi objects missing for mandatory GPIO-CS differential'
cmp -s "$OBJ/drivers/mmc/host/mmc_spi.o" "$SEED/obj/drivers/mmc/host/mmc_spi.o" &&
  fail 'C1 still linked unpatched mmc_spi.o; unsafe physical CMD0 preamble'
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
  echo "gpio_cs_patch_sha256=$C1_GPIO_CS_PATCH_SHA"
  echo "upstream_mmc_spi_sha256=$upstream_driver_sha"
  echo "patched_mmc_spi_sha256=$patched_driver_sha"
  echo 'rootfs_binding=NONE'
  echo 'qualification=NOT_YET_VERIFIED_ON_PRODUCT_RTL_OR_QEMU'
} > "$BUILD/result.txt"
echo 'PRODUCT_SD1_C1_KERNEL_BUILT_UNQUALIFIED rootfs_binding=NONE'
