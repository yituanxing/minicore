#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD_DIR="$ROOT/build/rv32imu-isolated-scheduler"
LOG_DIR="$ROOT/build/ci/logs"

# A retained self-hosted worktree must not reuse an older image or simulator.
rm -rf "$BUILD_DIR"
rm -f "$LOG_DIR/rv32imu-isolated-scheduler.log"
mkdir -p "$BUILD_DIR" "$LOG_DIR"

make -f Makefile.rv32imu-isolated-scheduler local-reference \
  2>&1 | tee "$LOG_DIR/rv32imu-isolated-scheduler.log"

grep -q '^expected_task_a_state=killed$' "$BUILD_DIR/software/manifest.txt"
grep -q '^expected_task_b_state=exited$' "$BUILD_DIR/software/manifest.txt"
grep -q '^expected_cross_task_faults=1$' "$BUILD_DIR/software/manifest.txt"

grep -q '^task_a_attack_b_data ' "$BUILD_DIR/software/labels.txt"
for stall in 0 5; do
  log="$BUILD_DIR/logs/stall-$stall.log"
  test "$(grep -c '^task A isolated$' "$log")" -eq 1
  test "$(grep -c '^task B survived$' "$log")" -eq 1
  grep -Fq 'PASS: self-check exit=0' "$log"
done

echo "PASS: RV32IMU isolated preemptive scheduler gate"
