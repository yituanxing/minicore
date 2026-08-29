#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD_DIR="$ROOT/build/rv32imu-isolated-scheduler"
SOFTWARE_DIR="$BUILD_DIR/software"
LOG_DIR="$ROOT/build/ci/logs"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"

# A retained self-hosted worktree must not reuse an older image or simulator.
rm -rf "$BUILD_DIR"
rm -f "$LOG_DIR"/rv32imu-isolated-scheduler*.log
mkdir -p "$BUILD_DIR" "$LOG_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$RV32_PATH_FILE" ]] || fail "missing shared RV32 NEMU path: $RV32_PATH_FILE"
RV32_NEMU_SO="$(cat "$RV32_PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing shared RV32 NEMU reference: $RV32_NEMU_SO"

# Keep all three goals in one make process so elaboration and the Verilator
# binary are shared instead of being rebuilt for each verification layer.
make -f Makefile.rv32imu-isolated-scheduler \
  local-reference run mismatch-probe \
  RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-isolated-scheduler.log"

grep -q '^expected_task_a_state=killed$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^expected_task_b_state=exited$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^expected_cross_task_faults=1$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^task_a_attack_b_data 0x80004124$' "$SOFTWARE_DIR/labels.txt"

{
  printf 'checkout_sha=%s\n' "$(git rev-parse HEAD)"
  printf 'github_sha=%s\n' "${GITHUB_SHA:-local}"
  printf 'github_head_ref=%s\n' "${GITHUB_HEAD_REF:-local}"
  printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-local}"
  printf 'github_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-local}"
  printf 'nemu_so=%s\n' "$RV32_NEMU_SO"
  sha256sum \
    "$SOFTWARE_DIR/isolated-scheduler.bin" \
    "$SOFTWARE_DIR/labels.txt" \
    "$SOFTWARE_DIR/manifest.txt"
} > "$SOFTWARE_DIR/provenance.txt"

for stall in 0 5; do
  local_log="$BUILD_DIR/logs/stall-$stall.log"
  difftest_log="$BUILD_DIR/logs/difftest-stall-$stall.log"

  for log in "$local_log" "$difftest_log"; do
    test "$(grep -c '^task A isolated$' "$log")" -eq 1
    test "$(grep -c '^task B survived$' "$log")" -eq 1
    test "$(grep -c '^PMP_EXCEPTION pc=0x80004124 inst=0x2a303 cause=0x5 value=0x80007000$' "$log")" -eq 1
    grep -Fq 'PASS: self-check exit=0' "$log"
    ! grep -Fq 'FAIL:' "$log"
  done

done

# The local reference is deterministic for the frozen image and timer model.
grep -Fq 'PASS: self-check exit=0 after 4928 cycles, 4116 committed instructions' \
  "$BUILD_DIR/logs/stall-0.log"
grep -Fq 'PASS: self-check exit=0 after 15383 cycles, 11556 committed instructions, stall-period=5' \
  "$BUILD_DIR/logs/stall-5.log"

for stall in 0 5; do
  log="$BUILD_DIR/logs/difftest-stall-$stall.log"
  grep -Eq 'difftest=[1-9][0-9]*' "$log"
  grep -Eq 'zicsr-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'trap-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'mret-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'interrupt-shadow=[1-9][0-9]*' "$log"
done

grep -q 'RV32 DiffTest mismatch after 0 matched events' "$BUILD_DIR/logs/mismatch.log"
grep -q 'x31' "$BUILD_DIR/logs/mismatch.log"

echo "PASS: RV32IMU isolated preemptive scheduler gate"
