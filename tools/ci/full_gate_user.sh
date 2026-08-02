#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/build/ci/logs"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/rv32imu-syscalls"
SOFTWARE_DIR="$BUILD_DIR/software"

mkdir -p "$LOG_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$RV32_PATH_FILE" ]] || fail "missing shared RV32 NEMU path: $RV32_PATH_FILE"
RV32_NEMU_SO="$(cat "$RV32_PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing shared RV32 NEMU reference: $RV32_NEMU_SO"

make -f Makefile.rv32imu-syscalls software contract

grep -q '^march=rv32im_zicsr$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^mabi=ilp32$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^privileges=MU$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^message=hello from U-mode via SYS_WRITE$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^message_bytes=32$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^binary_bytes=340$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^binary_words=85$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^binary_sha256=36f70e0c6b86b1cc2c3c75deb2f55a413b6955b1f1bee526a4b62bcea7d7602e$' \
  "$SOFTWARE_DIR/manifest.txt"
grep -q '^trap_handler 0x80000034$' "$SOFTWARE_DIR/labels.txt"
grep -q '^sys_write 0x8000006c$' "$SOFTWARE_DIR/labels.txt"
grep -q '^sys_get_ticks 0x80000098$' "$SOFTWARE_DIR/labels.txt"
grep -q '^sys_exit 0x800000a8$' "$SOFTWARE_DIR/labels.txt"
grep -q '^return_to_user 0x800000cc$' "$SOFTWARE_DIR/labels.txt"
grep -q '^user_main 0x800000dc$' "$SOFTWARE_DIR/labels.txt"
grep -q '^message 0x80000134$' "$SOFTWARE_DIR/labels.txt"
grep -q '^status=PASS$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_start=0x800000dc$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_end=0x80000134$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_bytes=88$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_store_sites=0$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_sys_write_store_sites=1$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_ecall_sites=5$' "$SOFTWARE_DIR/contract.txt"
grep -q '^first_user_ecall=0x800000ec$' "$SOFTWARE_DIR/contract.txt"
grep -q '^first_user_ecall_event=12$' "$SOFTWARE_DIR/contract.txt"

set -o pipefail
make -f Makefile.rv32imu-syscalls local-reference \
  2>&1 | tee "$LOG_DIR/rv32imu-syscalls-local.log"
make -f Makefile.rv32imu-syscalls run RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-syscalls.log"
make -f Makefile.rv32imu-syscalls mismatch-probe RV32_NEMU_SO="$RV32_NEMU_SO"

for stall in 0 5; do
  test "$(grep -c '^hello from U-mode via SYS_WRITE$' "$BUILD_DIR/logs/stall-$stall.log")" -eq 1
done
grep -Fxq 'PASS: self-check exit=0 after 395 cycles, 254 committed instructions, difftest=254, zicsr-shadow=10, trap-shadow=3, mret-shadow=3' \
  "$BUILD_DIR/logs/stall-0.log"
grep -Fxq 'PASS: self-check exit=0 after 427 cycles, 254 committed instructions, stall-period=5, difftest=254, zicsr-shadow=10, trap-shadow=3, mret-shadow=3' \
  "$BUILD_DIR/logs/stall-5.log"
grep -q 'RV32 DiffTest mismatch after 12 matched events' "$BUILD_DIR/logs/mismatch.log"
grep -q 'x31' "$BUILD_DIR/logs/mismatch.log"

echo "PASS: RV32IMU write/get_ticks/exit syscall gate"
