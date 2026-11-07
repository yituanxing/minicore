#!/usr/bin/env bash
# Build a DISTINCT Product SPI-SD OpenSBI/DTB carrying the rootfs-independent
# C1 Linux Image. This is firmware staging, NOT an RTL C1 checkpoint PASS.
# Ordinary SD1 / D2 firmware, Linux, source media and manifests stay intact.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/software/rv64/linux_early.env"
C1_LINUX="$ROOT/build/product-sd1-c1-kernel"
C1_RESULT="$C1_LINUX/result.txt"
C1_IMAGE="$C1_LINUX/obj/arch/riscv/boot/Image"
C1_CONFIG="$C1_LINUX/obj/.config"
C1_INIT="$ROOT/build/product-sd1-c1-initramfs"
C1_SPEC="$C1_INIT/initramfs.list"
BOOT="$ROOT/build/product-sd1-c1-boot"
INPUT="$ROOT/build/product-sd1-c1-firmware-verified-input"

fail() { printf 'PRODUCT_SD1_C1_FIRMWARE_REJECTED: %s\n' "$*" >&2; exit 2; }
field() {
  local value
  value="$(sed -n "s/^$1=//p" "$2")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || fail "missing/duplicate $1 in $2"
  printf '%s' "$value"
}
[[ $# == 1 && "$1" == prepare ]] ||
  fail 'usage: product_sd1_c1_firmware_build.sh prepare'
[[ ! -e "$BOOT" ]] ||
  fail 'refusing to overwrite a previous rootfs-independent C1 firmware build'
[[ ! -e "$INPUT" && ! -L "$INPUT" ]] ||
  fail 'refusing to overwrite a prior C1 firmware input verification'
[[ -s "$C1_RESULT" && -s "$C1_IMAGE" && -s "$C1_CONFIG" &&
   -s "$C1_INIT/result.txt" && -s "$C1_INIT/init" && -s "$C1_SPEC" ]] ||
  fail 'first build the exact C1 kernel and static guest /init'
grep -Fxq 'PRODUCT_SD1_C1_KERNEL_BUILD_RESULT: status=BUILT_UNQUALIFIED' "$C1_RESULT" ||
  fail 'C1 kernel has no verified build manifest'
grep -Fxq 'PRODUCT_SD1_C1_INITRAMFS_RESULT: status=READY_UNVERIFIED' "$C1_INIT/result.txt" ||
  fail 'C1 guest /init has no verified build manifest'
grep -Fxq 'rootfs_binding=NONE' "$C1_RESULT" ||
  fail 'C1 kernel is unexpectedly bound to a Debian or Alpine rootfs'
image_sha="$(sha256sum "$C1_IMAGE" | cut -d' ' -f1)"
config_sha="$(sha256sum "$C1_CONFIG" | cut -d' ' -f1)"
init_sha="$(sha256sum "$C1_INIT/init" | cut -d' ' -f1)"
spec_sha="$(sha256sum "$C1_SPEC" | cut -d' ' -f1)"
[[ "$(field kernel_sha256 "$C1_RESULT")" == "$image_sha" &&
   "$(field config_sha256 "$C1_RESULT")" == "$config_sha" &&
   "$(field init_sha256 "$C1_RESULT")" == "$init_sha" &&
   "$(field initramfs_spec_sha256 "$C1_RESULT")" == "$spec_sha" &&
   "$(field init_elf_sha256 "$C1_INIT/result.txt")" == "$init_sha" &&
   "$(field initramfs_spec_sha256 "$C1_INIT/result.txt")" == "$spec_sha" ]] ||
  fail 'C1 kernel/config/initramfs bytes changed after their manifests were sealed'
grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"$C1_SPEC\"" "$C1_CONFIG" ||
  fail 'C1 kernel no longer contains the rootfs-independent /init'
for config in CONFIG_EXT4_FS=y CONFIG_MMC_BLOCK=y CONFIG_MMC_SPI=y \
              CONFIG_SPI_OC_TINY=y CONFIG_DEVTMPFS=y \
              CONFIG_MSDOS_PARTITION=y CONFIG_INITRAMFS_COMPRESSION_NONE=y; do
  grep -Fxq "$config" "$C1_CONFIG" ||
    fail "C1 Linux lacks built-in Product SD /init requirement $config"
done

# The historical OpenSBI builder MUST remain byte-identical to preserve the
# huge already-qualified strict SD1 seed cache key. It accepts only a PASS
# marker and image_sha256, so provide a distinct C1-FIRMWARE-INPUT-PRECHECK
# manifest after checking every C1 kernel/config/init byte above. This PASS
# certifies ONLY that an input Image matches its source manifest: it is not a
# claim of Linux boot, Product RTL C1 READY, or native distro success.
mkdir -p "$INPUT/obj/arch/riscv/boot"
ln -s "$C1_IMAGE" "$INPUT/obj/arch/riscv/boot/Image"
{
  echo 'PRODUCT_SD1_C1_FIRMWARE_INPUT_PRECHECK: status=PASS'
  echo 'qualification=BYTES_VERIFIED_FOR_OPENSBI_INPUT_ONLY_NOT_RTL'
  echo "image_sha256=$image_sha"
  echo "c1_kernel_manifest_sha256=$(sha256sum "$C1_RESULT" | cut -d' ' -f1)"
  echo 'rootfs_binding=NONE'
} > "$INPUT/result.txt"
[[ "$(sha256sum "$INPUT/obj/arch/riscv/boot/Image" | cut -d' ' -f1)" == "$image_sha" ]] ||
  fail 'C1 prechecked OpenSBI input does not match the actual kernel Image'

# OpenSBI uses machine-mode integer ABI. Both the DTS's Product SPI-SD
# controller and the C1 handshake are explicit; 'init=/sbin/init' would bypass
# the checkpoint completely and is forbidden for this separate firmware.
export RV64_PAYLOAD_LINUX_BUILD_DIR="$INPUT"
export RV64_PAYLOAD_BUILD_DIR="$BOOT"
export RV64_PAYLOAD_LINUX_QUALIFIER='PRODUCT_SD1_C1_FIRMWARE_INPUT_PRECHECK'
export RV64_PAYLOAD_RESULT_TAG='PRODUCT_SD1_C1_FIRMWARE_INTERMEDIATE_RESULT'
export RV64_PAYLOAD_INPUT_TAG='PRODUCT_SD1_C1_FIRMWARE_INPUT'
export RV64_PAYLOAD_PRODUCT_BOARD=1
export RV64_PAYLOAD_ENABLE_SPI_SD=1
export RV64_PAYLOAD_ISA='rv64imafdc_zicsr_zifencei'
export RV64_PAYLOAD_ABI='lp64'
export RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw rdinit=/init"
unset RV64_PAYLOAD_SPI_CLOCK_HZ RV64_PAYLOAD_SPI_MAX_HZ

kernel_manifest_sha="$(sha256sum "$C1_RESULT" | cut -d' ' -f1)"
init_manifest_sha="$(sha256sum "$C1_INIT/result.txt" | cut -d' ' -f1)"
bash "$ROOT/tools/ci/rv64_minimal_init_payload_build.sh"
grep -Fxq 'PRODUCT_SD1_C1_FIRMWARE_INTERMEDIATE_RESULT: status=PASS' "$BOOT/result.txt" ||
  fail 'generic OpenSBI payload did not build the C1 firmware'
for fixed in 'spi_sd=1' 'product_board=1' 'abi=lp64' \
             'isa=rv64imafdc_zicsr_zifencei'; do
  grep -Fxq "$fixed" "$BOOT/result.txt" || fail "C1 firmware differs: $fixed"
done
grep -Fxq "bootargs=$RV64_PAYLOAD_BOOTARGS" "$BOOT/result.txt" ||
  fail 'C1 firmware command line lost the rootfs-independent PID1'
grep -Fq 'riscv,ndev = <52>;' "$BOOT/aethersoc-v0-initramfs.dts" ||
  fail 'C1 Product PLIC must export the physical 52 interrupt sources'
grep -Fq 'mmc-spi-slot' "$BOOT/aethersoc-v0-initramfs.dts" ||
  fail 'C1 Product DTS does not expose its SPI SD root device'

fw="$(field firmware_bin "$BOOT/result.txt")"
dtb="$(field fdt "$BOOT/result.txt")"
[[ -s "$fw" && -s "$dtb" &&
   "$(field linux_image "$BOOT/result.txt")" == "$INPUT/obj/arch/riscv/boot/Image" &&
   "$(field linux_image_sha256 "$BOOT/result.txt")" == "$image_sha" &&
   "$(sha256sum "$C1_RESULT" | cut -d' ' -f1)" == "$kernel_manifest_sha" &&
   "$(sha256sum "$C1_INIT/result.txt" | cut -d' ' -f1)" == "$init_manifest_sha" &&
   "$(sha256sum "$C1_IMAGE" | cut -d' ' -f1)" == "$image_sha" &&
   "$(sha256sum "$C1_CONFIG" | cut -d' ' -f1)" == "$config_sha" ]] ||
  fail 'C1 build inputs changed while OpenSBI firmware was assembled'

# The output must never be mistaken for a Linux/Debian runtime PASS. Its
# identity is Linux/Image/DTB/firmware ONLY; no rootfs or removable-media SHA.
{
  echo 'PRODUCT_SD1_C1_FIRMWARE_RESULT: status=READY_UNVERIFIED'
  echo 'qualification=ROOTFS_INDEPENDENT_FW_NOT_YET_QUALIFIED_ON_PRODUCT_RTL'
  echo 'rootfs_binding=NONE'
  echo 'spi_sd=1'
  echo 'product_board=1'
  echo "bootargs=$RV64_PAYLOAD_BOOTARGS"
  echo "firmware_bin=$fw"
  echo "firmware_bin_sha256=$(sha256sum "$fw" | cut -d' ' -f1)"
  echo "fdt=$dtb"
  echo "fdt_sha256=$(sha256sum "$dtb" | cut -d' ' -f1)"
  echo "linux_image=$C1_IMAGE"
  echo "linux_image_sha256=$image_sha"
  echo "linux_config_sha256=$config_sha"
  echo "c1_init_sha256=$init_sha"
  echo "c1_initramfs_spec_sha256=$spec_sha"
  echo "c1_kernel_manifest_sha256=$kernel_manifest_sha"
  echo "c1_init_manifest_sha256=$init_manifest_sha"
  echo 'rtl_c1_ready=NOT_YET_PROVEN'
} > "$BOOT/result.txt"
echo 'PRODUCT_SD1_C1_FIRMWARE_READY_UNVERIFIED rootfs_binding=NONE no_RTL_boot=1'
