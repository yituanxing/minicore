#!/usr/bin/env bash
# Optional, bounded guest-level test of the generic C1 /init HANDOFF only.
# Uses the SAME fully verified ext4 SD image via QEMU virtio, not Product SPI
# RTL; it cannot qualify a cross-process Verilator C1 snapshot/rebind.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/software/rv64/linux_early.env"
ACTION="${1:-}"
[[ $# == 1 && ( "$ACTION" == prepare || "$ACTION" == run ) ]] || {
  echo 'usage: product_sd1_c1_qemu_oracle.sh prepare|run' >&2; exit 2;
}
fail() { echo "PRODUCT_SD1_C1_QEMU_REJECTED: $*" >&2; exit 2; }
field() {
  local value
  value="$(sed -n "s/^$1=//p" "$2")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || fail "missing or duplicated $1 in $2"
  printf '%s' "$value"
}
SD_DIR="$ROOT/build/rv64-debian-sd1-sd"
SD_RESULT="$SD_DIR/result.txt"
SD_IMAGE="$SD_DIR/sd-rootfs.img"
C1_DIR="$ROOT/build/product-sd1-c1-kernel"
C1_RESULT="$C1_DIR/result.txt"
C1_CONFIG="$C1_DIR/obj/.config"
C1_IMAGE="$C1_DIR/obj/arch/riscv/boot/Image"
INIT_SPEC="$ROOT/build/product-sd1-c1-initramfs/initramfs.list"
SOURCE_DIR="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/rv64/linux/linux-${RV64_LINUX_VERSION}"
OUT="$ROOT/build/product-sd1-c1-qemu-oracle"
QCONFIG="$OUT/obj/.config"
QIMAGE="$OUT/obj/arch/riscv/boot/Image"
QRESULT="$OUT/result.txt"

[[ -s "$SD_RESULT" && -s "$SD_IMAGE" ]] || fail 'official SD1 ext4 media absent'
grep -Fxq 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "$SD_RESULT" ||
  fail 'SD1 media manifest not qualified'
[[ "$(field stage "$SD_RESULT")" == sd1 &&
   "$(field sd_image "$SD_RESULT")" == "$SD_IMAGE" &&
   "$(field partition1_fs "$SD_RESULT")" == ext4 ]] ||
  fail 'unexpected SD geometry or root stage'
printf '%s  %s\n' "$(field sd_image_sha256 "$SD_RESULT")" "$SD_IMAGE" | \
  sha256sum --check --status || fail 'actual SD image bytes differ from manifest'
[[ -s "$C1_RESULT" && -s "$C1_IMAGE" && -s "$INIT_SPEC" ]] ||
  fail 'build generic, rootfs-independent C1 Linux Image first'
grep -Fxq 'PRODUCT_SD1_C1_KERNEL_BUILD_RESULT: status=BUILT_UNQUALIFIED' "$C1_RESULT" ||
  fail 'C1 Linux kernel manifest not qualified'
grep -Fxq 'rootfs_binding=NONE' "$C1_RESULT" || fail 'C1 kernel incorrectly bound to Debian rootfs'
[[ "$(field kernel_sha256 "$C1_RESULT")" == "$(sha256sum "$C1_IMAGE" | cut -d' ' -f1)" &&
   "$(field config_sha256 "$C1_RESULT")" == "$(sha256sum "$C1_CONFIG" | cut -d' ' -f1)" &&
   "$(field initramfs_spec_sha256 "$C1_RESULT")" == "$(sha256sum "$INIT_SPEC" | cut -d' ' -f1)" ]] ||
  fail 'generic C1 Linux configuration changed since build'

if [[ "$ACTION" == prepare ]]; then
  CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}"
  [[ "$CROSS" == /* && -x "${CROSS}gcc" && -s "$SOURCE_DIR/Makefile" ]] ||
    fail 'verified RV64 cross GCC + Linux source required'
  [[ "$(cat "$SOURCE_DIR/.aethercore-linux-source-sha256" 2>/dev/null)" == "$RV64_LINUX_SHA256" ]] ||
    fail 'Linux source differs from its locked SHA'
  [[ ! -e "$OUT" ]] || fail 'refusing to overwrite any existing C1 QEMU oracle'
  mkdir -p "$OUT"
  cp -a --reflink=auto "$C1_DIR/obj" "$OUT/obj"
  "$SOURCE_DIR/scripts/config" --file "$QCONFIG" \
    -e VIRTIO -e VIRTIO_MMIO -e VIRTIO_BLK -e BLOCK -e MSDOS_PARTITION -e EXT4_FS
  export KBUILD_BUILD_USER="$RV64_LINUX_BUILD_USER" KBUILD_BUILD_HOST="$RV64_LINUX_BUILD_HOST"
  export KBUILD_BUILD_VERSION="$RV64_LINUX_BUILD_VERSION" KBUILD_BUILD_TIMESTAMP="$RV64_LINUX_BUILD_TIMESTAMP"
  export TZ="$RV64_LINUX_BUILD_TZ"
  make -C "$SOURCE_DIR" O="$OUT/obj" ARCH=riscv CROSS_COMPILE="$CROSS" olddefconfig \
    > "$OUT/config.log" 2>&1 || fail 'C1 QEMU Kconfig failed'
  for setting in VIRTIO VIRTIO_MMIO VIRTIO_BLK MSDOS_PARTITION EXT4_FS SERIAL_8250_CONSOLE; do
    grep -Fxq "CONFIG_${setting}=y" "$QCONFIG" ||
      fail "QEMU virt kernel needs built-in $setting"
  done
  grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"$INIT_SPEC\"" "$QCONFIG" ||
    fail 'QEMU kernel accidentally lost independent C1 /init'
  make -C "$SOURCE_DIR" O="$OUT/obj" ARCH=riscv CROSS_COMPILE="$CROSS" \
    -j"${RV64_SD1_QEMU_BUILD_JOBS:-4}" Image > "$OUT/build.log" 2>&1 ||
    fail 'C1 QEMU Linux build failed'
  [[ -s "$QIMAGE" ]] || fail 'C1 QEMU Image is missing'
  {
    echo 'PRODUCT_SD1_C1_QEMU_KERNEL_RESULT: status=READY_UNVERIFIED'
    echo 'qualification=QEMU_VIRTIO_C1_ONLY_NOT_PRODUCT_SPI_RTL'
    echo "source_kernel_sha256=$(field kernel_sha256 "$C1_RESULT")"
    echo "source_config_sha256=$(field config_sha256 "$C1_RESULT")"
    echo "media_sha256=$(field sd_image_sha256 "$SD_RESULT")"
    echo "oracle_kernel_sha256=$(sha256sum "$QIMAGE" | cut -d' ' -f1)"
    echo "oracle_config_sha256=$(sha256sum "$QCONFIG" | cut -d' ' -f1)"
    echo 'rootfs_binding=NONE'
  } > "$QRESULT"
  echo 'PRODUCT_SD1_C1_QEMU_KERNEL_READY_UNVERIFIED'
  exit 0
fi

[[ -s "$QRESULT" && -s "$QIMAGE" ]] || fail 'run prepare first'
grep -Fxq 'PRODUCT_SD1_C1_QEMU_KERNEL_RESULT: status=READY_UNVERIFIED' "$QRESULT" ||
  fail 'QEMU C1 Linux manifest not qualified'
grep -Fxq 'qualification=QEMU_VIRTIO_C1_ONLY_NOT_PRODUCT_SPI_RTL' "$QRESULT" ||
  fail 'a Product RTL PASS cannot be inherited from QEMU'
for entry in \
  "source_kernel_sha256:$(field kernel_sha256 "$C1_RESULT")" \
  "source_config_sha256:$(field config_sha256 "$C1_RESULT")" \
  "media_sha256:$(field sd_image_sha256 "$SD_RESULT")" \
  "oracle_kernel_sha256:$(sha256sum "$QIMAGE" | cut -d' ' -f1)" \
  "oracle_config_sha256:$(sha256sum "$QCONFIG" | cut -d' ' -f1)"; do
  [[ "$(field "${entry%%:*}" "$QRESULT")" == "${entry#*:}" ]] ||
    fail "C1 QEMU qualification drift: ${entry%%:*}"
done
command -v qemu-system-riscv64 >/dev/null || fail 'qemu-system-riscv64 unavailable'
python3 "$ROOT/tools/ci/product_sd1_c1_qemu_watch.py" \
  --kernel "$QIMAGE" --media "$SD_IMAGE" \
  --timeout "${RV64_SD1_C1_QEMU_TIMEOUT_SECONDS:-240}" \
  --log "$OUT/boot-uart.log" --result "$OUT/boot-result.txt"
printf '%s  %s\n' "$(field sd_image_sha256 "$SD_RESULT")" "$SD_IMAGE" | \
  sha256sum --check --status || fail 'QEMU altered frozen SD input'
echo 'PRODUCT_SD1_C1_QEMU_GATED_NATIVE_INIT_PASS_NOT_RTL'
