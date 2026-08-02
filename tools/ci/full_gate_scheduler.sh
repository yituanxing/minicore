#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/build/ci/logs"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/rv32im-scheduler"

mkdir -p "$LOG_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$RV32_PATH_FILE" ]] || fail "missing shared RV32 NEMU path: $RV32_PATH_FILE"
RV32_NEMU_SO="$(cat "$RV32_PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing shared RV32 NEMU reference: $RV32_NEMU_SO"

# Freeze the generated scheduler image and its key control-flow labels.
make -f Makefile.rv32im-scheduler software
diff -u <(cat <<'EOF'
preemptive-scheduler 1432 358 d62b691fe2ae85770418b3292c14e41f4a5ff16f2c9814483287b7a0cefd3eab
EOF
) "$BUILD_DIR/software/manifest.txt"
grep -q '^task_a 0x80000140$' "$BUILD_DIR/software/labels.txt"
grep -q '^task_b 0x800001d0$' "$BUILD_DIR/software/labels.txt"
grep -q '^trap_handler 0x80000254$' "$BUILD_DIR/software/labels.txt"

# Run the independent ordinary-instruction reference across every stall schedule.
set -o pipefail
make -f Makefile.rv32im-scheduler local-reference \
  2>&1 | tee "$LOG_DIR/rv32im-scheduler-local-reference.log"
grep -q '^status=PASS$' "$BUILD_DIR/local-reference/result.txt"
grep -q '^stall_periods=0,3,4,5,7,11$' "$BUILD_DIR/local-reference/result.txt"
grep -q '^negative_event=152$' "$BUILD_DIR/local-reference/result.txt"

# Use the shared exact single-step NEMU reference as the final authority.
make -f Makefile.rv32im-scheduler run RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32im-scheduler.log"
grep -q '^PASS: self-check exit=0 after 1509 cycles, 1345 committed instructions, difftest=1345, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-0.log"
grep -q '^PASS: self-check exit=0 after 1671 cycles, 1252 committed instructions, stall-period=3, difftest=1252, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-3.log"
grep -q '^PASS: self-check exit=0 after 1639 cycles, 1263 committed instructions, stall-period=4, difftest=1263, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-4.log"
grep -q '^PASS: self-check exit=0 after 1578 cycles, 1278 committed instructions, stall-period=5, difftest=1278, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-5.log"
grep -q '^PASS: self-check exit=0 after 1589 cycles, 1326 committed instructions, stall-period=7, difftest=1326, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-7.log"
grep -q '^PASS: self-check exit=0 after 1553 cycles, 1334 committed instructions, stall-period=11, difftest=1334, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' \
  "$BUILD_DIR/logs/stall-11.log"

# A damaged x8 restore must deterministically fail the scheduler self-check.
make -f Makefile.rv32im-scheduler context-corruption-probe

# Freeze all eight precise interrupt boundaries for the representative stall=5 run.
make -f Makefile.rv32im-scheduler trace
mv build/aethercore.vcd "$BUILD_DIR/evidence/scheduler.vcd"
grep -q '^committed_events=1278$' "$BUILD_DIR/evidence/interrupts.txt"
grep -q '^interrupt_events=8$' "$BUILD_DIR/evidence/interrupts.txt"
grep -q '^irq0_event_index=152$' "$BUILD_DIR/evidence/interrupts.txt"
grep -q '^irq7_event_index=1108$' "$BUILD_DIR/evidence/interrupts.txt"

# Corrupt the first reference interrupt event and require exact-boundary rejection.
set +e
AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=152 \
  "$BUILD_DIR/obj/VAetherCoreRV32IMTrapSimTop" \
  "$BUILD_DIR/software/preemptive-scheduler.bin" \
  --max-cycles 30000 --self-check-exit --stall-period 5 \
  --difftest "$RV32_NEMU_SO" \
  > "$BUILD_DIR/scheduler-negative.log" 2>&1
status=$?
set -e
[[ $status -ne 0 ]] || fail "deliberate scheduler mismatch unexpectedly passed"
grep -q 'RV32 timer DiffTest mismatch after 152 matched events' \
  "$BUILD_DIR/scheduler-negative.log"
grep -q 'x31' "$BUILD_DIR/scheduler-negative.log"

echo "PASS: RV32IM timer-preemptive scheduler full gate"
