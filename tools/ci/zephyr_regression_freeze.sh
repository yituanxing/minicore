#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FREEZE_ROOT="${AETHERCORE_ZEPHYR_FREEZE_ROOT:-$ROOT/build/zephyr-freeze}"
EVIDENCE_DIR="$FREEZE_ROOT/evidence"
Z2_RESULT="$ROOT/build/zephyr-z2/evidence/result.txt"
Z2_LOG="$ROOT/build/zephyr-z2/evidence/boot.log"
Z3_RESULT="$ROOT/build/zephyr-z3/evidence/result.txt"
Z3_MATRIX="$ROOT/build/zephyr-z3/evidence/matrix.tsv"
Z4_RESULT="$ROOT/build/zephyr-z4/evidence/result.txt"
Z4_LOG0="$ROOT/build/zephyr-z4/evidence/positive-stall-0.log"
Z4_LOG3="$ROOT/build/zephyr-z4/evidence/positive-stall-3.log"

rm -rf "$FREEZE_ROOT"
mkdir -p "$EVIDENCE_DIR/baseline" "$EVIDENCE_DIR/replay"

for file in "$Z2_RESULT" "$Z2_LOG" "$Z3_RESULT" "$Z3_MATRIX" "$Z4_RESULT" "$Z4_LOG0" "$Z4_LOG3"; do
  test -s "$file" || {
    echo "ERROR: missing prerequisite evidence: $file" >&2
    exit 1
  }
done

cp "$Z2_RESULT" "$EVIDENCE_DIR/baseline/z2-result.txt"
cp "$Z2_LOG" "$EVIDENCE_DIR/baseline/z2-boot.log"
cp "$Z3_RESULT" "$EVIDENCE_DIR/baseline/z3-result.txt"
cp "$Z3_MATRIX" "$EVIDENCE_DIR/baseline/z3-matrix.tsv"
cp "$Z4_RESULT" "$EVIDENCE_DIR/baseline/z4-result.txt"
cp "$Z4_LOG0" "$EVIDENCE_DIR/baseline/z4-stall-0.log"
cp "$Z4_LOG3" "$EVIDENCE_DIR/baseline/z4-stall-3.log"

AETHERCORE_ZEPHYR_BUILD_DIR="$ROOT/build/zephyr-stage/host-build" \
  bash "$ROOT/tools/ci/zephyr_z2_boot.sh"
AETHERCORE_ZEPHYR_BUILD_DIR="$ROOT/build/zephyr-stage/host-build" \
  bash "$ROOT/tools/ci/zephyr_z3_timer_schedule.sh"
bash "$ROOT/tools/ci/zephyr_z4_external_irq.sh"

cp "$Z2_RESULT" "$EVIDENCE_DIR/replay/z2-result.txt"
cp "$Z2_LOG" "$EVIDENCE_DIR/replay/z2-boot.log"
cp "$Z3_RESULT" "$EVIDENCE_DIR/replay/z3-result.txt"
cp "$Z3_MATRIX" "$EVIDENCE_DIR/replay/z3-matrix.tsv"
cp "$Z4_RESULT" "$EVIDENCE_DIR/replay/z4-result.txt"
cp "$Z4_LOG0" "$EVIDENCE_DIR/replay/z4-stall-0.log"
cp "$Z4_LOG3" "$EVIDENCE_DIR/replay/z4-stall-3.log"

extract_commits() {
  grep -E 'PASS: self-check exit=0 after [0-9]+ cycles, [0-9]+ committed instructions' "$1" \
    | tail -n 1 \
    | sed -E 's/.*, ([0-9]+) committed instructions.*/\1/'
}

baseline_z2_commits="$(extract_commits "$EVIDENCE_DIR/baseline/z2-boot.log")"
replay_z2_commits="$(extract_commits "$EVIDENCE_DIR/replay/z2-boot.log")"
[[ -n "$baseline_z2_commits" && "$baseline_z2_commits" == "$replay_z2_commits" ]] || {
  echo "ERROR: Z2 committed instruction count drifted: $baseline_z2_commits != $replay_z2_commits" >&2
  exit 20
}

cmp -s "$EVIDENCE_DIR/baseline/z3-matrix.tsv" "$EVIDENCE_DIR/replay/z3-matrix.tsv" || {
  echo "ERROR: Z3 stall matrix changed during freeze replay" >&2
  diff -u "$EVIDENCE_DIR/baseline/z3-matrix.tsv" "$EVIDENCE_DIR/replay/z3-matrix.tsv" || true
  exit 21
}

for stall in 0 3; do
  baseline="$EVIDENCE_DIR/baseline/z4-stall-${stall}.log"
  replay="$EVIDENCE_DIR/replay/z4-stall-${stall}.log"
  baseline_commits="$(extract_commits "$baseline")"
  replay_commits="$(extract_commits "$replay")"
  [[ -n "$baseline_commits" && "$baseline_commits" == "$replay_commits" ]] || {
    echo "ERROR: Z4 stall=$stall committed instruction count drifted: $baseline_commits != $replay_commits" >&2
    exit 22
  }
  grep -Fq 'AETHERCORE ZEPHYR IRQ PASS bytes=2 isr=2 work=2' "$baseline"
  grep -Fq 'AETHERCORE ZEPHYR IRQ PASS bytes=2 isr=2 work=2' "$replay"
done

for stage in z2 z3 z4; do
  grep -Fxq 'status=PASS' "$EVIDENCE_DIR/baseline/${stage}-result.txt"
  grep -Fxq 'status=PASS' "$EVIDENCE_DIR/replay/${stage}-result.txt"
done

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-regression-freeze-v1
passes=2
z2_committed_instructions=$baseline_z2_commits
z3_matrix=stable
z4_stall_periods=0,3
z4_interrupt_counts=isr-2,work-2
fast_gate=not-triggered
full_gate=not-triggered
exit_code=0
EOF

find "$EVIDENCE_DIR" -type f ! -name artifacts.sha256 -print0 \
  | sort -z \
  | xargs -0 sha256sum > "$EVIDENCE_DIR/artifacts.sha256"

cat "$EVIDENCE_DIR/result.txt"
cat "$EVIDENCE_DIR/artifacts.sha256"
