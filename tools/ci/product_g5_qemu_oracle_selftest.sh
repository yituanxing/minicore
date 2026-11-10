#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/tools/ci/product_g5_qemu_oracle.sh"
WORK="$(mktemp -d -p "$ROOT" .product-g5-qemu-selftest.XXXXXXXX)"
trap 'rm -r -- "$WORK"' EXIT

# No actual guest execution: exercise the fail-closed UART milestone parser
# separately from the real, explicitly opt-in QEMU system runner.
printf '%s\n' \
  'Linux version 6.6.143' \
  'AETHERCORE_DEBIAN_D2_START' \
  'AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK' \
  'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK' > "$WORK/valid.log"
bash "$SCRIPT" check-log "$WORK/valid.log" > "$WORK/valid.out"
grep -Fxq 'PRODUCT_G5_QEMU_LOG_SELFTEST_PASS markers_only=1 no_system_execution_claim=1' "$WORK/valid.out"

expect_reject() {
  local label="$1" logfile="$2"
  if bash "$SCRIPT" check-log "$logfile" > "$WORK/$label.out" 2>&1; then
    echo "SELFTEST_FAIL $label accepted" >&2; exit 1
  fi
  grep -Fq 'PRODUCT_G5_QEMU_ORACLE_REJECTED:' "$WORK/$label.out" || {
    echo "SELFTEST_FAIL $label did not reject explicitly" >&2; exit 1;
  }
}

sed '/AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK/d' "$WORK/valid.log" > "$WORK/missing-fpu.log"
expect_reject missing-fpu "$WORK/missing-fpu.log"
sed '/DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK/d' "$WORK/valid.log" > "$WORK/no-pid1.log"
expect_reject no-pid1 "$WORK/no-pid1.log"
printf 'Kernel panic - not syncing\n' >> "$WORK/panic.log"
sed -n '1,4p' "$WORK/valid.log" >> "$WORK/panic.log"
expect_reject panic "$WORK/panic.log"
printf 'AETHERCORE_DEBIAN_D2_FAIL:proc\n' > "$WORK/fail.log"
sed -n '1,4p' "$WORK/valid.log" >> "$WORK/fail.log"
expect_reject d2-fail "$WORK/fail.log"

if AETHERCORE_ALLOW_G5_QEMU_SYSTEM_ORACLE=0 bash "$SCRIPT" run > "$WORK/implicit.out" 2>&1; then
  echo 'SELFTEST_FAIL implicit QEMU system run accepted' >&2; exit 1
fi
grep -Fq 'explicit opt-in' "$WORK/implicit.out"
echo 'PRODUCT_G5_QEMU_SYSTEM_ORACLE_SELFTEST_PASS positive=1 negatives=4 implicit_run_rejected=1 guest_executions=0'
