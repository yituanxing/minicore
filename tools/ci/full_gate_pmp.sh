#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/build/ci/logs"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/rv32imu-pmp"
SOFTWARE_DIR="$BUILD_DIR/software"

mkdir -p "$LOG_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$RV32_PATH_FILE" ]] || fail "missing shared RV32 NEMU path: $RV32_PATH_FILE"
RV32_NEMU_SO="$(cat "$RV32_PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing shared RV32 NEMU reference: $RV32_NEMU_SO"

make -f Makefile.rv32imu-pmp software contract

grep -q '^march=rv32im_zicsr$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^mabi=ilp32$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^privileges=MU$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmp_entries=4$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmp_mode=TOR$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmpcfg0=0x000b0d08$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^kernel_region=0x80000000-0x80001000:---$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_text_region=0x80001000-0x80002000:r-x$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_data_region=0x80002000-0x80003000:rw-$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_default=deny$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^expected_fault_stages=6$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^message=PMP isolation via SYS_WRITE$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^attack_byte=0x40$' "$SOFTWARE_DIR/manifest.txt"

grep -q '^status=PASS$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_start=0x80000000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_end=0x80001000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_text_start=0x80001000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_text_end=0x80002000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_data_start=0x80002000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_data_end=0x80003000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^pmp_csr_writes=4$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_store_sites=5$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_load_sites=3$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_jalr_attack_sites=2$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_sys_write_store_sites=1$' "$SOFTWARE_DIR/contract.txt"
grep -q '^expected_fault_stages=6$' "$SOFTWARE_DIR/contract.txt"

set -o pipefail
make -f Makefile.rv32imu-pmp local-reference \
  2>&1 | tee "$LOG_DIR/rv32imu-pmp-local.log"
make -f Makefile.rv32imu-pmp run RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-pmp.log"
make -f Makefile.rv32imu-pmp mismatch-probe RV32_NEMU_SO="$RV32_NEMU_SO"

for stall in 0 5; do
  log="$BUILD_DIR/logs/stall-$stall.log"
  test "$(grep -c '^PMP isolation via SYS_WRITE$' "$log")" -eq 1
  ! grep -Fq '@' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Fq 'trap-shadow=9' "$log"
  grep -Fq 'mret-shadow=8' "$log"
done
grep -q 'RV32 DiffTest mismatch after 0 matched events' "$BUILD_DIR/logs/mismatch.log"
grep -q 'x31' "$BUILD_DIR/logs/mismatch.log"

echo "PASS: RV32IMU PMP isolation gate"
