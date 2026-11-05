#!/usr/bin/env bash
set -euo pipefail

# Run the actual Product G5 OpenSBI fw_payload and its Product DTB on QEMU
# virt's independently implemented CPU/devices.  This is a board-binding
# *compatibility oracle*, NOT a Product CPU/RTL, physical FPGA, or SD1 test.
#
# Unlike the generic QEMU-system D2 oracle, no independent -kernel/-append is
# supplied: the exact Product fw_payload owns Linux at 0x80200000, the built-in
# Product DTB owns bootargs, and the second DTB copy is loaded at the fixed
# hardware contract address 0x87f00000.  QEMU's -dtb informs its boot ROM.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
[[ "$ACTION" == run || "$ACTION" == check-log ]] || {
  echo 'usage: product_g5_qemu_product_firmware_oracle.sh run | check-log LOGFILE' >&2
  exit 2
}

fail() { echo "PRODUCT_G5_QEMU_PRODUCT_FW_DTB_REJECTED: $*" >&2; exit 3; }
verify_log() {
  local log="$1" required
  [[ -s "$log" ]] || fail 'guest serial absent'
  if grep -Fq 'AETHERCORE_DEBIAN_D2_FAIL:' "$log" ||
     grep -Eq '(Kernel panic|Oops:|Unable to mount root fs)' "$log"; then
    fail 'guest reported a kernel or D2 userspace failure'
  fi
  for required in \
    'Linux version 6.6.143' \
    'AETHERCORE_DEBIAN_D2_START' \
    'AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK' \
    'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK'; do
    grep -Fq "$required" "$log" || fail "missing Product firmware guest milestone: $required"
  done
}

if [[ "$ACTION" == check-log ]]; then
  [[ $# == 2 ]] || fail 'check-log requires exactly one fixture file'
  verify_log "$2"
  echo 'PRODUCT_G5_QEMU_PRODUCT_FW_DTB_SELFTEST_PASS fixture_only=1 no_guest_claim=1'
  exit 0
fi

[[ $# == 1 && "${AETHERCORE_ALLOW_G5_QEMU_PRODUCT_FIRMWARE:-0}" == 1 ]] ||
  fail 'explicit Product firmware QEMU opt-in required'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'physical Product SD1 / arbitrary external DTB injection not allowed'
[[ "${RV64_DEBIAN_STAGE:-d2}" == d2 ]] || fail 'only native Debian D2 is admitted'

QEMU="${AETHERCORE_G5_QEMU_SYSTEM_BIN:-qemu-system-riscv64}"
QEMU_PATH="$(command -v "$QEMU" || true)"
[[ -n "$QEMU_PATH" && -x "$QEMU_PATH" ]] || fail 'qemu-system-riscv64 not available'
WALL="${AETHERCORE_G5_QEMU_WALL_SECONDS:-180}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 10 && WALL <= 600 )) ||
  fail 'wall limit outside 10..600 seconds'

ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
PAYLOAD_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
FW="$ROOT/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="$ROOT/build/rv64-debian-boot/aethercore-rv64-initramfs.dtb"
DTS="$ROOT/build/rv64-debian-boot/aethersoc-v0-initramfs.dts"
IMAGE="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
for path in "$ROOTFS_RESULT" "$LINUX_RESULT" "$PAYLOAD_RESULT" "$FW" "$DTB" "$DTS" "$IMAGE"; do
  [[ -s "$path" ]] || fail "missing Product D2 input: $path"
done
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'rootfs not qualified'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || fail 'Linux not qualified'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$PAYLOAD_RESULT" || fail 'Product payload not qualified'
for token in 'stage=d2' 'fdt_addr=0x87f00000' \
  'isa=rv64imafdc_zicsr_zifencei' 'abi=lp64'; do
  case "$token" in
    stage=*) grep -Fxq "$token" "$ROOTFS_RESULT" && grep -Fxq "$token" "$LINUX_RESULT" || fail 'stage mismatch' ;;
    *) grep -Fxq "$token" "$PAYLOAD_RESULT" || fail "Product firmware contract missing: $token" ;;
  esac
done
grep -Fq 'riscv,ndev = <52>;' "$DTS" || fail 'not the Product 52-source PLIC tree'
grep -Fxq "firmware_bin=$FW" "$PAYLOAD_RESULT" || fail 'Product firmware identity drifted'
grep -Fxq "fdt=$DTB" "$PAYLOAD_RESULT" || fail 'Product DTB identity drifted'
grep -Fxq "linux_image=$IMAGE" "$PAYLOAD_RESULT" || fail 'Product payload Linux image changed'
for item in "fw_text_start=0x80000000" \
  'fw_payload_address=0x80200000' 'fw_payload_fdt_addr=0x87f00000'; do
  grep -Fxq "$item" "$ROOT/build/rv64-debian-boot/evidence/inputs.txt" ||
    fail "physical firmware input differs: $item"
done
[[ "$(sed -n 's/^linux_image_sha256=//p' "$PAYLOAD_RESULT" | head -1)" == \
   "$(sha256sum "$IMAGE" | awk '{print $1}')" ]] || fail 'Linux Image SHA mismatched firmware'
[[ "$(sed -n 's/^image_sha256=//p' "$LINUX_RESULT" | head -1)" == \
   "$(sha256sum "$IMAGE" | awk '{print $1}')" ]] || fail 'D2 Linux SHA drifted'
[[ "$(stat -c%s "$FW")" -lt 133169152 ]] || fail 'firmware crosses reserved Product DTB placement'

OUT="${PRODUCT_G5_QEMU_PRODUCT_ORACLE_OUT:-$ROOT/build/product-g5-qemu-product-fw-dtb}"
mkdir -p "$OUT"
LOG="$OUT/serial.log"
RESULT="$OUT/result.txt"
rm -f "$RESULT"
echo 'PRODUCT_G5_QEMU_PRODUCT_FW_DTB_START actual_product_opensbi=1 actual_product_dtb=1 qemu_cpu=1 product_rtl=0'

# -bios boots the Product OpenSBI + Image at 0x80000000/0x80200000.
# -dtb supplies the same tree at QEMU's boot-ROM pointer; -device loader
# installs an independent copy at the exact Product boot address. The latter
# prevents the firmware from succeeding via a silently substituted QEMU FDT.
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "$QEMU_PATH" -machine virt -cpu rv64 -smp 1 -m 256M \
  -bios "$FW" -dtb "$DTB" \
  -device "loader,file=$DTB,addr=0x87f00000,force-raw=on" \
  -display none -monitor none -serial stdio -no-reboot -no-shutdown \
  > "$LOG" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true' EXIT
done_marker='DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK'
while kill -0 "$PID" 2>/dev/null; do
  if grep -Fq "$done_marker" "$LOG" || grep -Fq 'AETHERCORE_DEBIAN_D2_FAIL:' "$LOG"; then
    break
  fi
  sleep 1
done
if ! grep -Fq "$done_marker" "$LOG"; then
  wait "$PID" || true
  verify_log "$LOG"
  fail 'Product-fw/DTB QEMU guest exited before native D2 completion'
fi
verify_log "$LOG"

printf 'qualification=QEMU_SYSTEM_PRODUCT_FW_DTB_D2_ORACLE_NOT_PRODUCT_RTL_PASS\n' > "$RESULT"
printf 'stage=d2\nqemu_cpu=1\nqemu_devices=virt\nproduct_rtl=0\nproduct_fw_payload=1\nproduct_fdt=1\n' >> "$RESULT"
printf 'fw_sha256=%s\nfdt_sha256=%s\nlinux_image_sha256=%s\nqemu_sha256=%s\n' \
  "$(sha256sum "$FW" | awk '{print $1}')" \
  "$(sha256sum "$DTB" | awk '{print $1}')" \
  "$(sha256sum "$IMAGE" | awk '{print $1}')" \
  "$(sha256sum "$QEMU_PATH" | awk '{print $1}')" >> "$RESULT"
echo 'PRODUCT_G5_QEMU_PRODUCT_FW_DTB_D2_PASS Product-OpenSBI=1 Product-DTB=1 native-Debian-D2=1 Product-RTL=0'
