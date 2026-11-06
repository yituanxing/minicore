#!/usr/bin/env bash
set -euo pipefail

# Isolated SD1 diagnostic: reuse byte-verified official Debian ext4 media,
# compile ONLY a new Linux Image and its matching OpenSBI payload. No guest
# cycles, official Image replacement or frozen-checkpoint compatibility claim.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source software/rv64/linux_early.env

fail() { printf 'SD1_GPIO_CS_DIAG_REJECTED: %s\n' "$*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

[[ "${AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_BUILD:-0}" == 1 ]] ||
  fail 'set explicit isolated diagnostic opt-in'
: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?activate pinned Bootlin GCC 13.3 first}"
[[ "$("${AETHERCORE_RV64_LINUX_CROSS_COMPILE}gcc" -dumpfullversion)" == 13.3.0 ]] ||
  fail 'cross compiler version differs from frozen GCC 13.3'

# IMPORTANT: verify the original immutable signed media BEFORE constructing a
# distinct kernel and payload. Never reseal the official seed as diagnostic.
python3 tools/ci/product_sd1_official_seed.py verify ||
  fail 'official full SD1 media must verify independently first'
official_rootfs_result="$ROOT/build/rv64-debian-rootfs/result.txt"
official_linux_result="$ROOT/build/rv64-debian-linux/result.txt"
official_linux_image="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
official_config="$ROOT/build/rv64-debian-linux/obj/.config"
official_sd_result="$ROOT/build/rv64-debian-sd1-sd/result.txt"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
[[ -s "$official_linux_image" && -s "$official_config" && -s "$sd" ]] ||
  fail 'required signed SD1 media or Linux configuration missing'
[[ "$(field stage "$official_linux_result")" == sd1 &&
   "$(field stage "$official_rootfs_result")" == sd1 ]] ||
  fail 'official source must be SD1 block-root (not D1/D2/D3 initramfs)'
[[ "$(sha "$official_linux_image")" == "$(field image_sha256 "$official_linux_result")" &&
   "$(sha "$sd")" == "$(field sd_image_sha256 "$official_sd_result")" ]] ||
  fail 'official SD1 bytes changed after signed source verification'

cache="${AETHERCORE_CACHE_ROOT:-$HOME/.cache/aethercore}"
source_dir="$cache/rv64/linux/linux-${RV64_LINUX_VERSION}"
[[ -d "$source_dir" &&
   "$(< "$source_dir/.aethercore-linux-source-sha256")" == "$RV64_LINUX_SHA256" ]] ||
  fail 'exact upstream Linux 6.6.143 source cache absent or unqualified'
patch="$ROOT/tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
patch_sha=ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638
[[ -s "$patch" && "$(sha "$patch")" == "$patch_sha" ]] ||
  fail 'GPIO-CS patch deviates from reviewed frozen bytes'

out="$ROOT/build/product-sd1-gpio-cs-diagnostic"
[[ ! -e "$out" ]] || fail 'diagnostic output already exists; never overwrite'
mkdir -p "$out"
diagnostic_source="$out/linux-source"
cp -a --reflink=auto "$source_dir" "$diagnostic_source"
git -C "$diagnostic_source" apply --check "$patch" ||
  fail 'patch does not apply to exact upstream Linux source'
git -C "$diagnostic_source" apply "$patch"

linux="$out/linux"
obj="$linux/obj"
mkdir -p "$obj" "$linux/evidence"
cp "$official_config" "$obj/.config"
export KBUILD_BUILD_USER="$RV64_LINUX_BUILD_USER"
export KBUILD_BUILD_HOST="$RV64_LINUX_BUILD_HOST"
export KBUILD_BUILD_VERSION="$RV64_LINUX_BUILD_VERSION"
export KBUILD_BUILD_TIMESTAMP="$RV64_LINUX_BUILD_TIMESTAMP"
export TZ="$RV64_LINUX_BUILD_TZ"
make -C "$diagnostic_source" O="$obj" ARCH=riscv \
  CROSS_COMPILE="$AETHERCORE_RV64_LINUX_CROSS_COMPILE" olddefconfig \
  2>&1 | tee "$out/linux-config.log"
cmp -s "$official_config" "$obj/.config" ||
  fail 'kernel configuration changed; only reviewed mmc_spi source patch permitted'
make -C "$diagnostic_source" O="$obj" ARCH=riscv \
  CROSS_COMPILE="$AETHERCORE_RV64_LINUX_CROSS_COMPILE" \
  -j"${RV64_LINUX_JOBS:-4}" Image 2>&1 | tee "$out/linux-build.log"
image="$obj/arch/riscv/boot/Image"
[[ -s "$image" && -s "$obj/vmlinux" ]] || fail 'patched Image/vmlinux missing'
[[ "$(sha "$image")" != "$(sha "$official_linux_image")" ]] ||
  fail 'patched Image equals unpatched frozen Image; driver build may have been missed'
"${AETHERCORE_RV64_LINUX_CROSS_COMPILE}readelf" -h "$obj/vmlinux" |
  grep -Eq 'Machine:[[:space:]]*RISC-V' || fail 'patched vmlinux is not RISC-V'
cp "$patch" "$linux/evidence/mmc-spi-gpio-cs-off.patch"
cp "$obj/.config" "$linux/evidence/resolved.config"
{
  echo 'RV64_SD1_GPIO_CS_DIAG_LINUX_RESULT: status=PASS'
  echo 'qualification=GPIO_CS_KERNEL_DIAGNOSTIC_NOT_PRODUCT_SIGNOFF'
  echo 'stage=sd1'
  echo "image=$image"
  echo "image_sha256=$(sha "$image")"
  echo "config_sha256=$(sha "$obj/.config")"
  echo "patch_sha256=$patch_sha"
  echo "official_image_sha256=$(sha "$official_linux_image")"
  echo "official_sd_image_sha256=$(sha "$sd")"
  echo "rootfs_result_sha256=$(sha "$official_rootfs_result")"
  echo "rootfs_tree_sha256=$(field rootfs_tree_sha256 "$official_rootfs_result")"
} > "$linux/result.txt"

# The generic source-of-truth payload builder accepts a distinct Linux build
# and qualifier and emits an independent OpenSBI+Product DTB under this tree.
export RV64_PAYLOAD_LINUX_BUILD_DIR="$linux"
export RV64_PAYLOAD_BUILD_DIR="$out/boot"
export RV64_PAYLOAD_LINUX_QUALIFIER=RV64_SD1_GPIO_CS_DIAG_LINUX_RESULT
export RV64_PAYLOAD_RESULT_TAG=RV64_SD1_GPIO_CS_DIAG_PAYLOAD_RESULT
export RV64_PAYLOAD_INPUT_TAG=RV64_SD1_GPIO_CS_DIAG_PAYLOAD_INPUT
export RV64_PAYLOAD_PRODUCT_BOARD=1
export RV64_PAYLOAD_ENABLE_SPI_SD=1
export RV64_PAYLOAD_ISA=rv64imafdc_zicsr_zifencei
export RV64_PAYLOAD_ABI=lp64
export RV64_PAYLOAD_BOOTARGS="$RV64_LINUX_BOOTARGS root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init"
bash tools/ci/rv64_minimal_init_payload_build.sh
fw_result="$out/boot/result.txt"
grep -Fxq 'RV64_SD1_GPIO_CS_DIAG_PAYLOAD_RESULT: status=PASS' "$fw_result" ||
  fail 'matched diagnostic firmware/DTB build not qualified'
fw="$(field firmware_bin "$fw_result")"
dtb="$(field fdt "$fw_result")"
[[ "$(field linux_image_sha256 "$fw_result")" == "$(sha "$image")" &&
   "$(field spi_sd "$fw_result")" == 1 &&
   "$(field product_board "$fw_result")" == 1 ]] ||
  fail 'diagnostic firmware is bound to wrong Linux Image or board'
{
  echo 'PRODUCT_SD1_GPIO_CS_DIAG_ARTIFACTS: status=READY_FOR_SHORT_RTL_ONLY'
  echo 'qualification=GPIO_CS_DIAGNOSTIC_KERNEL_FIRMWARE_NOT_DEBIAN_PASS'
  echo "source_sha256=$RV64_LINUX_SHA256"
  echo "patch_sha256=$patch_sha"
  echo "original_linux_sha256=$(sha "$official_linux_image")"
  echo "linux_image_sha256=$(sha "$image")"
  echo "config_sha256=$(sha "$obj/.config")"
  echo "firmware_sha256=$(sha "$fw")"
  echo "dtb_sha256=$(sha "$dtb")"
  echo "sd_image_sha256=$(sha "$sd")"
  echo "rootfs_result_sha256=$(sha "$official_rootfs_result")"
} | tee "$out/diagnostic-manifest.txt"
