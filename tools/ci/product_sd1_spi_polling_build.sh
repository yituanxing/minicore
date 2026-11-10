#!/usr/bin/env bash
set -euo pipefail
# Isolated SPI polling Product SD1 firmware candidate. No RTL cycles here.
# Never change the signed Debian SD, the qualified patched Image, or existing
# 572M/512M full-RTL checkpoints. Polling is not yet a performance PASS.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source software/rv64/opensbi_first_exec.env
source software/rv64/linux_early.env
die() { echo "PRODUCT_SD1_SPI_POLLING_REJECTED: $*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
[[ "${AETHERCORE_SD1_SPI_POLLING_BUILD:-0}" == 1 ]] || die 'explicit polling diagnostic opt-in required'
: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?activate pinned cross compiler}"
[[ "$("${AETHERCORE_RV64_LINUX_CROSS_COMPILE}gcc" -dumpfullversion)" == 13.3.0 ]] ||
  die 'pinned GCC 13.3 required'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  die 'original signed full Debian SD media verification failed'
diag="$ROOT/build/product-sd1-gpio-cs-diagnostic"
manifest="$diag/diagnostic-manifest.txt"
oldboot="$diag/boot/result.txt"
image="$diag/linux/obj/arch/riscv/boot/Image"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
[[ -s "$manifest" && -s "$oldboot" && -s "$image" && -s "$sd" ]] ||
  die 'complete authenticated patched kernel / official media are required'
grep -Fxq 'PRODUCT_SD1_GPIO_CS_DIAG_ARTIFACTS: status=READY_FOR_SHORT_RTL_ONLY' "$manifest" ||
  die 'missing separate original GPIO-CS patch qualification'
[[ "$(field patch_sha256 "$manifest")" == ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638 &&
   "$(field linux_image_sha256 "$manifest")" == "$(sha "$image")" &&
   "$(field sd_image_sha256 "$manifest")" == "$(sha "$sd")" &&
   "$(field linux_image_sha256 "$oldboot")" == "$(sha "$image")" ]] ||
  die 'patched Image or original SD identity mismatch'
old_dts="$diag/boot/aethersoc-v0-initramfs.dts"
old_dtb="$(field fdt "$oldboot")"
old_fw="$(field firmware_bin "$oldboot")"
[[ -s "$old_dts" && -s "$old_dtb" && -s "$old_fw" ]] ||
  die 'original Product GPIO-CS DTB and firmware missing'
[[ "$old_dtb" == "$diag/boot/"* && "$old_fw" == "$diag/boot/"* ]] ||
  die 'original DTB/firmware must belong to authenticated diagnostic cache'
[[ "$(sha "$old_dtb")" == "$(field dtb_sha256 "$manifest")" &&
   "$(sha "$old_fw")" == "$(field firmware_sha256 "$manifest")" ]] ||
  die 'original diagnostic DTB/firmware bytes changed'
out="$ROOT/build/product-sd1-spi-polling-candidate"
[[ ! -e "$out" ]] || die 'existing polling candidate must not be overwritten'
mkdir -p "$out/boot" "$out/opensbi"
source_sha="$(sha "$old_dts")"
python3 tools/ci/product_sd1_spi_polling_dts.py \
  --input "$old_dts" --output "$out/boot/product-spi-polling.dts" \
  --expected-input-sha256 "$source_sha" | tee "$out/dts-proof.txt"
command -v dtc >/dev/null || die 'dtc must be installed'
dtc -I dts -O dtb -o "$out/boot/product-spi-polling.dtb" "$out/boot/product-spi-polling.dts"
[[ "$(sha "$out/boot/product-spi-polling.dtb")" != "$(sha "$old_dtb")" ]] ||
  die 'polling candidate DTB equals original IRQ DTB'
source_dir="${AETHERCORE_CACHE_ROOT:-$HOME/.cache/aethercore}/l32/opensbi/$RV64_OPENSBI_COMMIT"
[[ -d "$source_dir/.git" &&
   "$(git -C "$source_dir" rev-parse HEAD)" == "$RV64_OPENSBI_COMMIT" ]] ||
  die 'original pinned OpenSBI source unavailable'
git -C "$source_dir" diff --quiet --ignore-submodules -- ||
  die 'original cached OpenSBI source dirty'
make -C "$source_dir" O="$out/opensbi" PLATFORM="$RV64_OPENSBI_PLATFORM" \
  PLATFORM_RISCV_XLEN="$RV64_OPENSBI_XLEN" \
  PLATFORM_RISCV_ISA=rv64imafdc_zicsr_zifencei \
  PLATFORM_RISCV_ABI=lp64 \
  FW_TEXT_START="$RV64_OPENSBI_FW_TEXT_START" \
  FW_FDT_PATH="$out/boot/product-spi-polling.dtb" \
  FW_PAYLOAD_PATH="$image" \
  FW_PAYLOAD_OFFSET="$RV64_OPENSBI_PAYLOAD_OFFSET" \
  FW_PAYLOAD_FDT_ADDR="$RV64_OPENSBI_FDT_ADDR" \
  CROSS_COMPILE="$AETHERCORE_RV64_LINUX_CROSS_COMPILE" \
  -j"${RV64_LINUX_JOBS:-4}" > "$out/opensbi-build.log" 2>&1 || {
    tail -60 "$out/opensbi-build.log" >&2
    die 'separate polling firmware build failed'
  }
bin="$out/opensbi/platform/$RV64_OPENSBI_PLATFORM/firmware/fw_payload.bin"
elf="$out/opensbi/platform/$RV64_OPENSBI_PLATFORM/firmware/fw_payload.elf"
[[ -s "$bin" && -s "$elf" ]] || die 'distinct polling firmware missing'
[[ "$(sha "$bin")" != "$(sha "$old_fw")" ]] ||
  die 'polling firmware unexpectedly identical to original GPIO-CS firmware'
"${AETHERCORE_RV64_LINUX_CROSS_COMPILE}readelf" -h "$elf" |
  grep -Eq 'Machine:[[:space:]]*RISC-V' || die 'not RISC-V firmware'
cp -- "$bin" "$out/boot/fw_payload-polling.bin"
cp -- "$elf" "$out/boot/fw_payload-polling.elf"
{
  echo 'PRODUCT_SD1_SPI_POLLING_CANDIDATE: status=BUILT_NOT_RTL_QUALIFIED'
  echo 'qualification=DIAGNOSTIC_SPI_POLLING_NEW_DTB_NEW_FIRMWARE_NO_DEBIAN_PASS'
  echo "image_sha256=$(sha "$image")"
  echo "media_sha256=$(sha "$sd")"
  echo "original_dts_sha256=$source_sha"
  echo "original_dtb_sha256=$(sha "$old_dtb")"
  echo "candidate_dts_sha256=$(sha "$out/boot/product-spi-polling.dts")"
  echo "candidate_dtb_sha256=$(sha "$out/boot/product-spi-polling.dtb")"
  echo "original_firmware_sha256=$(sha "$old_fw")"
  echo "candidate_firmware_sha256=$(sha "$bin")"
  echo 'spi_irq=absent'
  echo 'spi_polling=1'
  echo 'old_checkpoint_compatibility=FORBIDDEN'
} | tee "$out/manifest.txt"
