#!/usr/bin/env bash
set -euo pipefail

# G5 D2 kernel, G4's tiny /init: isolate the cost of Debian's embedded rootfs.
# Never publish this as a Debian userland milestone.  Both images are compiled
# from one upstream source, one D2 Kconfig and one Product SoC/DTB definition.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/software/rv64/linux_early.env"
: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?pin RV64 GCC first}"
CROSS="$AETHERCORE_RV64_LINUX_CROSS_COMPILE"
SOURCE="${AETHERCORE_CACHE_ROOT:-$HOME/.cache/aethercore}/rv64/linux/linux-${RV64_LINUX_VERSION}"
FULL="$ROOT/build/rv64-debian-linux"
FULL_BOOT="$ROOT/build/rv64-debian-boot"
CONTROL="$ROOT/build/product-g5-tiny-control"
LINUX="$CONTROL/linux"
OBJ="$LINUX/obj"
INIT="$CONTROL/init"
EVIDENCE="$CONTROL/evidence"
SPEC="$INIT/initramfs.list"

fail() { echo "PRODUCT_G5_TINY_REJECTED: $*" >&2; exit 2; }
field() { sed -n "s/^$2=//p" "$1" | head -1; }

grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$FULL/result.txt" ||
  fail 'build the official G5 D2 Linux Image first'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$FULL_BOOT/result.txt" ||
  fail 'build the matching official G5 D2 Product payload first'
[[ "$(field "$FULL/result.txt" stage)" == d2 &&
   "$(field "$ROOT/build/rv64-debian-rootfs/result.txt" stage)" == d2 ]] ||
  fail 'the control must derive from the exact G5 D2 configuration'
[[ "$(field "$FULL/result.txt" config_sha256)" == "$(sha256sum "$FULL/obj/.config" | awk '{print $1}')" &&
   "$(field "$FULL/result.txt" image_sha256)" == "$(sha256sum "$FULL/obj/arch/riscv/boot/Image" | awk '{print $1}')" ]] ||
  fail 'G5 D2 kernel identity has changed'
[[ "$(field "$FULL_BOOT/result.txt" linux_image_sha256)" == "$(field "$FULL/result.txt" image_sha256)" ]] ||
  fail 'G5 D2 boot payload and kernel do not match'
[[ -s "$SOURCE/scripts/config" &&
   "$(< "$SOURCE/.aethercore-linux-source-sha256")" == "$RV64_LINUX_SHA256" ]] ||
  fail 'frozen upstream Linux source missing or changed'
[[ "$(${CROSS}gcc -dumpfullversion)" == 13.3.0 ]] || fail 'GCC 13.3.0 required'

[[ ! -e "$CONTROL" ]] || fail 'refuse to overwrite an existing Tiny control'
mkdir -p "$INIT" "$EVIDENCE" "$LINUX"
cp -a --reflink=auto "$FULL/obj" "$OBJ"

# Exact historical G4 libc-free PID1, with deterministic object and link paths.
"${CROSS}gcc" -march=rv64ima_zicsr_zifencei -mabi=lp64 \
  -c "$ROOT/software/rv64_userspace/minimal_init.S" -o "$INIT/minimal_init.o"
"${CROSS}gcc" -march=rv64ima_zicsr_zifencei -mabi=lp64 \
  -nostdlib -static -no-pie -Wl,--build-id=none -Wl,-e,_start \
  -Wl,-Ttext=0x00010000 -Wl,-z,max-page-size=4096 \
  "$INIT/minimal_init.o" -o "$INIT/init"
"${CROSS}readelf" -h -l -A "$INIT/init" > "$EVIDENCE/init-readelf.txt"
grep -q 'Machine:[[:space:]]*RISC-V' "$EVIDENCE/init-readelf.txt" || fail 'Tiny init is not RISC-V'
grep -q 'Type:[[:space:]]*EXEC' "$EVIDENCE/init-readelf.txt" || fail 'Tiny init is not static EXEC'
if grep -q 'Requesting program interpreter' "$EVIDENCE/init-readelf.txt"; then
  fail 'Tiny control unexpectedly depends on a dynamic loader'
fi
printf 'dir /dev 0755 0 0\nnod /dev/console 0600 0 0 c 5 1\nfile /init %s 0755 0 0\n' \
  "$INIT/init" > "$SPEC"

# This is a *kernel-config-controlled* comparison, not a G4 kernel rebuild.
# The only permitted resolved Kconfig change is the cpio source filename.
"$SOURCE/scripts/config" --file "$OBJ/.config" --set-str INITRAMFS_SOURCE "$SPEC"
export KBUILD_BUILD_USER="$RV64_LINUX_BUILD_USER"
export KBUILD_BUILD_HOST="$RV64_LINUX_BUILD_HOST"
export KBUILD_BUILD_VERSION="$RV64_LINUX_BUILD_VERSION"
export KBUILD_BUILD_TIMESTAMP="$RV64_LINUX_BUILD_TIMESTAMP"
export TZ="$RV64_LINUX_BUILD_TZ"
make -C "$SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" olddefconfig \
  2>&1 | tee "$CONTROL/config.log"
sed '/^CONFIG_INITRAMFS_SOURCE=/d' "$FULL/obj/.config" > "$EVIDENCE/d2-config-normalized.txt"
sed '/^CONFIG_INITRAMFS_SOURCE=/d' "$OBJ/.config" > "$EVIDENCE/tiny-config-normalized.txt"
cmp "$EVIDENCE/d2-config-normalized.txt" "$EVIDENCE/tiny-config-normalized.txt" ||
  fail 'resolved G5 D2 and Tiny Kconfigs differ beyond INITRAMFS_SOURCE'
grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"$SPEC\"" "$OBJ/.config" ||
  fail 'Tiny cpio source was not selected'
make -C "$SOURCE" O="$OBJ" ARCH=riscv CROSS_COMPILE="$CROSS" \
  -j"${RV64_LINUX_JOBS:-4}" Image 2>&1 | tee "$CONTROL/linux-build.log"

# Record the final linked .text identity.  Link-time PC-relative references
# can legitimately change when the embedded cpio shifts downstream section
# addresses; this diagnostic is NOT a byte-identical Image contract.
"${CROSS}objcopy" -O binary --only-section=.text "$FULL/obj/vmlinux" "$EVIDENCE/d2-text.bin"
"${CROSS}objcopy" -O binary --only-section=.text "$OBJ/vmlinux" "$EVIDENCE/tiny-text.bin"
if cmp -s "$EVIDENCE/d2-text.bin" "$EVIDENCE/tiny-text.bin"; then
  text_identity=IDENTICAL
else
  text_identity=RELINKED_DIFFERENT
fi
image="$OBJ/arch/riscv/boot/Image"
[[ -s "$image" ]] || fail 'Tiny Linux Image is absent'
{
  echo 'PRODUCT_G5_TINY_LINUX_BUILD_RESULT: status=PASS'
  echo 'qualification=CONTROL_ONLY_NOT_DEBIAN'
  echo 'stage=d2-tiny-control'
  echo "linux_source_sha256=$RV64_LINUX_SHA256"
  echo "g5_d2_config_sha256=$(sha256sum "$FULL/obj/.config" | awk '{print $1}')"
  echo "normalized_config_sha256=$(sha256sum "$EVIDENCE/d2-config-normalized.txt" | awk '{print $1}')"
  echo "linked_kernel_text_identity=$text_identity"
  echo "d2_linked_kernel_text_sha256=$(sha256sum "$EVIDENCE/d2-text.bin" | awk '{print $1}')"
  echo "kernel_text_sha256=$(sha256sum "$EVIDENCE/tiny-text.bin" | awk '{print $1}')"
  echo "tiny_init_sha256=$(sha256sum "$INIT/init" | awk '{print $1}')"
  echo "tiny_cpio_spec_sha256=$(sha256sum "$SPEC" | awk '{print $1}')"
  echo "official_d2_image_sha256=$(field "$FULL/result.txt" image_sha256)"
  echo "image=$image"
  echo "image_sha256=$(sha256sum "$image" | awk '{print $1}')"
} | tee "$LINUX/result.txt"

# Keep the entire G5 Product platform, DTS and kernel command line unchanged.
export RV64_PAYLOAD_LINUX_BUILD_DIR="$LINUX"
export RV64_PAYLOAD_BUILD_DIR="$CONTROL/boot"
export RV64_PAYLOAD_LINUX_QUALIFIER='PRODUCT_G5_TINY_LINUX_BUILD_RESULT'
export RV64_PAYLOAD_RESULT_TAG='PRODUCT_G5_TINY_PAYLOAD_BUILD_RESULT'
export RV64_PAYLOAD_INPUT_TAG='PRODUCT_G5_TINY_PAYLOAD_INPUT'
export RV64_PAYLOAD_PRODUCT_BOARD=1
export RV64_PAYLOAD_ENABLE_SPI_SD=0
export RV64_PAYLOAD_ISA='rv64imafdc_zicsr_zifencei'
export RV64_PAYLOAD_ABI=lp64
export RV64_PAYLOAD_BOOTARGS="$(field "$FULL_BOOT/result.txt" bootargs)"
[[ "$RV64_PAYLOAD_BOOTARGS" == *'rdinit=/init'* ]] ||
  fail 'official G5 D2 bootargs do not select /init'
bash "$ROOT/tools/ci/rv64_minimal_init_payload_build.sh"
grep -Fxq 'PRODUCT_G5_TINY_PAYLOAD_BUILD_RESULT: status=PASS' "$CONTROL/boot/result.txt" ||
  fail 'Tiny Product payload failed to qualify'
cmp "$FULL_BOOT/aethercore-rv64-initramfs.dtb" "$CONTROL/boot/aethercore-rv64-initramfs.dtb" ||
  fail 'Tiny and D2 Product DTBs/bootargs differ'
echo "PRODUCT_G5_TINY_SAME_KERNEL_CONTROL_PASS: Kconfig_except_cpio=IDENTICAL linked_text=$text_identity Product_DTB=IDENTICAL Debian_PID1_qualified=0"
