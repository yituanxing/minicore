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
grep -q '^status=PASS$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_store_sites=0$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_sys_write_store_sites=1$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_ecall_sites=5$' "$SOFTWARE_DIR/contract.txt"

set -o pipefail
make -f Makefile.rv32imu-syscalls local-reference \
  2>&1 | tee "$LOG_DIR/rv32imu-syscalls-local.log"
make -f Makefile.rv32imu-syscalls run RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-syscalls.log"
make -f Makefile.rv32imu-syscalls mismatch-probe RV32_NEMU_SO="$RV32_NEMU_SO"

test "$(grep -c '^hello from U-mode via SYS_WRITE$' "$LOG_DIR/rv32imu-syscalls.log")" -eq 2
test "$(grep -c 'PASS: self-check exit=0' "$LOG_DIR/rv32imu-syscalls.log")" -eq 2
grep -Fq 'trap-shadow=3' "$LOG_DIR/rv32imu-syscalls.log"
grep -Fq 'mret-shadow=3' "$LOG_DIR/rv32imu-syscalls.log"

echo "PASS: RV32IMU write/get_ticks/exit syscall gate"
