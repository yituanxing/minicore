#!/usr/bin/env bash
# Independent QEMU virt oracle for the SAME pinned SD1 Debian ext4 media.
# QEMU's virtio block device is intentionally NOT the Product SPI-SD bus.
# This is a bounded source/guest-PID1 smoke test; never an RTL or FPGA PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT}/software/rv64/linux_early.env"
ACTION="${1:-}"
case "$ACTION" in prepare|run) ;; *)
  echo 'usage: product_sd1_qemu_init_oracle.sh prepare|run' >&2; exit 2 ;;
esac

SD_DIR="${ROOT}/build/rv64-debian-sd1-sd"
SD_RESULT="${SD_DIR}/result.txt"
SD_IMAGE="${SD_DIR}/sd-rootfs.img"
ROOTFS_RESULT="${ROOT}/build/rv64-debian-rootfs/result.txt"
PRODUCT_LINUX_DIR="${ROOT}/build/rv64-debian-linux"
PRODUCT_LINUX_RESULT="${PRODUCT_LINUX_DIR}/result.txt"
PRODUCT_CONFIG="${PRODUCT_LINUX_DIR}/obj/.config"
ORACLE_DIR="${ROOT}/build/rv64-debian-sd1-qemu-oracle"
OBJ_DIR="${ORACLE_DIR}/obj"
ORACLE_RESULT="${ORACLE_DIR}/result.txt"
ORACLE_IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
SOURCE_DIR="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/rv64/linux/linux-${RV64_LINUX_VERSION}"

fail() { printf 'ERROR: SD1 QEMU oracle: %s\n' "$*" >&2; exit 1; }
field() {
  local key="$1" file="$2" value
  [[ -s "$file" ]] || fail "missing qualified manifest: $file"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || fail "missing/duplicate $key in $file"
  printf '%s\n' "$value"
}
check_sd() {
  grep -Fxq 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "$SD_RESULT" || fail 'SD1 media not qualified'
  grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'SD1 rootfs not qualified'
  grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$PRODUCT_LINUX_RESULT" || fail 'Product SD1 Linux not qualified'
  for manifest in "$SD_RESULT" "$ROOTFS_RESULT" "$PRODUCT_LINUX_RESULT"; do
    [[ "$(field stage "$manifest")" == sd1 ]] || fail "wrong stage in $manifest"
  done
  [[ "$(field sd_image "$SD_RESULT")" == "$SD_IMAGE" && -s "$SD_IMAGE" ]] || fail 'wrong/missing immutable SD image'
  [[ "$(field partition_table "$SD_RESULT")" == MBR && "$(field partition1_first_lba "$SD_RESULT")" == 2048 &&
     "$(field partition1_fs "$SD_RESULT")" == ext4 ]] || fail 'QEMU disk requires exact Product SD1 MBR/ext4 geometry'
  [[ "$(field disk_bytes "$SD_RESULT")" == "$(stat -c%s "$SD_IMAGE")" ]] || fail 'SD media extent differs from manifest'
  printf '%s  %s\n' "$(field sd_image_sha256 "$SD_RESULT")" "$SD_IMAGE" | sha256sum --check --status || fail 'SD bytes differ from manifest'
  [[ "$(field rootfs_result_sha256 "$SD_RESULT")" == "$(sha256sum "$ROOTFS_RESULT" | cut -d' ' -f1)" ]] || fail 'SD/rootfs manifest drift'
  [[ "$(field rootfs_result_sha256 "$PRODUCT_LINUX_RESULT")" == "$(sha256sum "$ROOTFS_RESULT" | cut -d' ' -f1)" ]] || fail 'Product Linux/rootfs manifest drift'
  [[ "$(field config_sha256 "$PRODUCT_LINUX_RESULT")" == "$(sha256sum "$PRODUCT_CONFIG" | cut -d' ' -f1)" ]] || fail 'Product SD1 Kconfig drift'
}

check_sd
if [[ "$ACTION" == prepare ]]; then
  # A separate QEMU virt kernel is required: Product SPI/MMC DTB and kernel
  # alone cannot drive QEMU's virtio-mmio virtual block bus.
  : "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?set fixed RV64 GCC first}"
  [[ -s "${SOURCE_DIR}/.aethercore-linux-source-sha256" &&
     "$(< "${SOURCE_DIR}/.aethercore-linux-source-sha256")" == "$RV64_LINUX_SHA256" ]] || fail 'unqualified Linux source'
  [[ ! -e "$ORACLE_DIR" ]] || fail "refusing to overwrite QEMU oracle build: $ORACLE_DIR"
  mkdir -p "$ORACLE_DIR"
  cp -a --reflink=auto "${PRODUCT_LINUX_DIR}/obj" "$OBJ_DIR"
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
    -e VIRTIO -e VIRTIO_MMIO -e VIRTIO_BLK -e BLOCK -e MSDOS_PARTITION -e EXT4_FS
  export KBUILD_BUILD_USER="$RV64_LINUX_BUILD_USER" KBUILD_BUILD_HOST="$RV64_LINUX_BUILD_HOST"
  export KBUILD_BUILD_VERSION="$RV64_LINUX_BUILD_VERSION" KBUILD_BUILD_TIMESTAMP="$RV64_LINUX_BUILD_TIMESTAMP"
  export TZ="$RV64_LINUX_BUILD_TZ"
  make -C "$SOURCE_DIR" O="$OBJ_DIR" ARCH=riscv \
    CROSS_COMPILE="$AETHERCORE_RV64_LINUX_CROSS_COMPILE" olddefconfig
  for setting in VIRTIO VIRTIO_MMIO VIRTIO_BLK BLOCK MSDOS_PARTITION EXT4_FS SERIAL_8250_CONSOLE; do
    grep -Fxq "CONFIG_${setting}=y" "$OBJ_DIR/.config" || fail "QEMU kernel missing built-in $setting"
  done
  grep -Fxq 'CONFIG_INITRAMFS_SOURCE=""' "$OBJ_DIR/.config" || fail 'QEMU rootfs accidentally embedded in kernel'
  make -C "$SOURCE_DIR" O="$OBJ_DIR" ARCH=riscv \
    CROSS_COMPILE="$AETHERCORE_RV64_LINUX_CROSS_COMPILE" \
    -j"${RV64_SD1_QEMU_BUILD_JOBS:-4}" Image
  [[ -s "$ORACLE_IMAGE" ]] || fail 'QEMU virt Image missing'
  {
    echo 'PRODUCT_SD1_QEMU_ORACLE_KERNEL_RESULT: status=READY_UNVERIFIED'
    echo 'qualification=QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL'
    echo 'stage=sd1'
    echo "source_kernel_sha256=$RV64_LINUX_SHA256"
    echo "product_config_sha256=$(sha256sum "$PRODUCT_CONFIG" | cut -d' ' -f1)"
    echo "rootfs_result_sha256=$(sha256sum "$ROOTFS_RESULT" | cut -d' ' -f1)"
    echo "sd_image_sha256=$(field sd_image_sha256 "$SD_RESULT")"
    echo "oracle_config_sha256=$(sha256sum "$OBJ_DIR/.config" | cut -d' ' -f1)"
    echo "oracle_image_sha256=$(sha256sum "$ORACLE_IMAGE" | cut -d' ' -f1)"
  } > "$ORACLE_RESULT"
  echo 'PRODUCT_SD1_QEMU_VIRT_KERNEL_READY_UNVERIFIED'
  exit 0
fi

[[ -s "$ORACLE_RESULT" && -s "$ORACLE_IMAGE" ]] || fail 'run prepare first; no implicit rebuild on boot'
grep -Fxq 'PRODUCT_SD1_QEMU_ORACLE_KERNEL_RESULT: status=READY_UNVERIFIED' "$ORACLE_RESULT" || fail 'QEMU kernel manifest not qualified'
grep -Fxq 'qualification=QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL' "$ORACLE_RESULT" || fail 'QEMU/Product qualification confused'
for spec in \
  "source_kernel_sha256:$RV64_LINUX_SHA256" \
  "product_config_sha256:$(sha256sum "$PRODUCT_CONFIG" | cut -d' ' -f1)" \
  "rootfs_result_sha256:$(sha256sum "$ROOTFS_RESULT" | cut -d' ' -f1)" \
  "sd_image_sha256:$(field sd_image_sha256 "$SD_RESULT")" \
  "oracle_config_sha256:$(sha256sum "$OBJ_DIR/.config" | cut -d' ' -f1)" \
  "oracle_image_sha256:$(sha256sum "$ORACLE_IMAGE" | cut -d' ' -f1)"; do
  [[ "$(field "${spec%%:*}" "$ORACLE_RESULT")" == "${spec#*:}" ]] || fail "QEMU qualification changed: ${spec%%:*}"
done
command -v qemu-system-riscv64 >/dev/null || fail 'missing qemu-system-riscv64 (install qemu-system-misc externally)'
timeout_seconds="${RV64_SD1_QEMU_TIMEOUT_SECONDS:-240}"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && (( timeout_seconds >= 10 && timeout_seconds <= 900 )) || fail 'QEMU wall timeout must be 10..900 seconds'
python3 "$ROOT/tools/ci/product_sd1_qemu_watch.py" \
  --kernel "$ORACLE_IMAGE" --sd "$SD_IMAGE" --timeout "$timeout_seconds" \
  --log "$ORACLE_DIR/boot-uart.log" --result "$ORACLE_DIR/boot-result.txt"
# A -snapshot overlay must NEVER modify the exact pinned Product SD1 source.
printf '%s  %s\n' "$(field sd_image_sha256 "$SD_RESULT")" "$SD_IMAGE" | sha256sum --check --status || fail 'QEMU modified source SD despite snapshot=on'
printf '%s  %s\n' "$(field oracle_image_sha256 "$ORACLE_RESULT")" "$ORACLE_IMAGE" | sha256sum --check --status || fail 'QEMU kernel was changed during oracle'
echo 'PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL'
