#!/usr/bin/env bash
set -euo pipefail

# QEMU *system* oracle: execute the exact qualified D2 Linux Image, including
# its embedded native Debian riscv64 LP64D initramfs, on an independent virt
# board. QEMU supplies its own OpenSBI and DTB. This cannot qualify Product
# RTL, Product's DTB, FPGA devices, an SD1 disk, or Debian D3 init/login.
# No QEMU-user binaries, state transplant, or multi-billion-cycle RTL here.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
[[ "$ACTION" == run || "$ACTION" == check-log ]] || {
  echo 'usage: product_g5_qemu_oracle.sh run | check-log LOGFILE (mock log selftests only)' >&2
  exit 2
}

fail() { echo "PRODUCT_G5_QEMU_ORACLE_REJECTED: $*" >&2; exit 3; }

verify_log() {
  local log="$1" label
  [[ -s "$log" ]] || fail 'guest serial log absent/empty'
  if grep -Fq 'AETHERCORE_DEBIAN_D2_FAIL:' "$log" ||
     grep -Eq '(Kernel panic|Oops:|Unable to mount root fs)' "$log"; then
    fail 'D2 script or kernel reported a fatal error'
  fi
  for label in \
      'Linux version 6.6.143' \
      'AETHERCORE_DEBIAN_D2_START' \
      'AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK' \
      'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK'; do
    grep -Fq "$label" "$log" || fail "guest never reached $label"
  done
}

if [[ "$ACTION" == check-log ]]; then
  [[ $# == 2 ]] || fail 'check-log requires exactly one mock logfile'
  verify_log "$2"
  echo 'PRODUCT_G5_QEMU_LOG_SELFTEST_PASS markers_only=1 no_system_execution_claim=1'
  exit 0
fi

[[ $# == 1 && "${AETHERCORE_ALLOW_G5_QEMU_SYSTEM_ORACLE:-0}" == 1 ]] ||
  fail 'QEMU system guest requires explicit opt-in; never implicit RTL replacement'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'Product physical SD1/DTB must not be silently injected into QEMU virt'

QEMU="${AETHERCORE_G5_QEMU_SYSTEM_BIN:-qemu-system-riscv64}"
QEMU_PATH="$(command -v "$QEMU" || true)"
[[ -n "$QEMU_PATH" && -x "$QEMU_PATH" ]] || fail 'qemu-system-riscv64 not installed (qemu-system-misc on Ubuntu)'
WALL="${AETHERCORE_G5_QEMU_WALL_SECONDS:-180}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 10 && WALL <= 600 )) ||
  fail 'QEMU system run must be bounded to 10..600 wall seconds'

ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
ROOTFS="$ROOT/build/rv64-debian-rootfs/rootfs"
IMAGE="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
CONFIG="$ROOT/build/rv64-debian-linux/obj/.config"
[[ -s "$ROOTFS_RESULT" && -s "$LINUX_RESULT" && -s "$IMAGE" && -s "$CONFIG" ]] ||
  fail 'real qualified Debian D2 Linux Image/rootfs/config must be built first'
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'rootfs not qualified'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || fail 'Linux Image not qualified'
for check in "stage=d2" "rootfs=$ROOTFS"; do
  grep -Fxq "$check" "$ROOTFS_RESULT" && grep -Fxq "$check" "$LINUX_RESULT" ||
    fail "D2 rootfs/Linux input contract mismatched: $check"
done
[[ -x "$ROOTFS/init" ]] || fail 'Debian D2 /init absent'
cmp -s "$ROOTFS/init" "$ROOT/software/rv64_userspace/debian_d2_init.sh" ||
  fail 'D2 /init differs from reviewed native Debian script'
grep -Fxq "image=$IMAGE" "$LINUX_RESULT" || fail 'qualified Image path mismatch'
for cfg in 'CONFIG_64BIT=y' 'CONFIG_MMU=y' 'CONFIG_RISCV_ISA_C=y' \
    'CONFIG_FPU=y' 'CONFIG_SERIAL_8250_CONSOLE=y' \
    "CONFIG_INITRAMFS_SOURCE=\"$ROOTFS\""; do
  grep -Fxq "$cfg" "$CONFIG" || fail "D2 kernel lacks $cfg"
done
[[ "$(sed -n 's/^image_sha256=//p' "$LINUX_RESULT" | head -1)" == \
   "$(sha256sum "$IMAGE" | awk '{print $1}')" ]] ||
  fail 'kernel Image SHA drifted'
[[ "$(sed -n 's/^rootfs_result_sha256=//p' "$LINUX_RESULT" | head -1)" == \
   "$(sha256sum "$ROOTFS_RESULT" | awk '{print $1}')" ]] ||
  fail 'rootfs manifest SHA differs from qualified Image'
[[ "$(sed -n 's/^config_sha256=//p' "$LINUX_RESULT" | head -1)" == \
   "$(sha256sum "$ROOT/build/rv64-debian-linux/evidence/resolved.config" | awk '{print $1}')" ]] ||
  fail 'resolved kernel configuration SHA drifted'

source "$ROOT/software/rv64/linux_early.env"
OUT="${PRODUCT_G5_QEMU_ORACLE_OUT:-$ROOT/build/product-g5-qemu-system-oracle}"
mkdir -p "$OUT"
LOG="$OUT/d2-serial.log"
RESULT="$OUT/result.txt"
rm -f "$RESULT"
echo 'PRODUCT_G5_QEMU_SYSTEM_START native_riscv64=1 linux_image_identical=1 product_rtl=0 product_dtb=0'

# The Product fw_payload firmware/DTB must NOT be reused on the QEMU virt
# machine: PLIC geometry and physical board devices differ. Boot QEMU's own
# packaged generic OpenSBI + QEMU-generated virt DTB and the exact D2 Image.
# `rdinit=/init` is already the Product D2 boot convention.
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "$QEMU_PATH" -machine virt -cpu rv64 -smp 1 -m 256M \
  -bios default -kernel "$IMAGE" \
  -append "$RV64_LINUX_BOOTARGS rdinit=/init" \
  -display none -monitor none -serial stdio -no-reboot -no-shutdown \
  > "$LOG" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true' EXIT

done_marker='DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK'
while kill -0 "$PID" 2>/dev/null; do
  if grep -Fq "$done_marker" "$LOG"; then break; fi
  if grep -Fq 'AETHERCORE_DEBIAN_D2_FAIL:' "$LOG"; then break; fi
  sleep 1
done
if ! grep -Fq "$done_marker" "$LOG"; then
  wait "$PID" || true
  verify_log "$LOG"  # prints the specific missing/failure marker
  fail 'guest exited without finishing native Debian D2'
fi
verify_log "$LOG"

printf 'qualification=QEMU_SYSTEM_DEBIAN_D2_ORACLE_NOT_PRODUCT_RTL_PASS\n' > "$RESULT"
printf 'stage=d2\nproduct_rtl=0\nqemu_board=virt\nqemu_firmware=generic_own_opensbi\nqemu_dtb=generated_virt\n' >> "$RESULT"
printf 'linux_image_sha256=%s\nrootfs_manifest_sha256=%s\nqemu_sha256=%s\n' \
  "$(sha256sum "$IMAGE" | awk '{print $1}')" \
  "$(sha256sum "$ROOTFS_RESULT" | awk '{print $1}')" \
  "$(sha256sum "$QEMU_PATH" | awk '{print $1}')" >> "$RESULT"
echo 'PRODUCT_G5_QEMU_D2_SYSTEM_ORACLE_PASS same_linux_image=1 same_debian_rootfs=1 native_fp_process_signal_fs=1 no_product_rtl_claim=1'
