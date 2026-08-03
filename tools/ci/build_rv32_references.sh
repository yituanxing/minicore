#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROBE_DIR="$ROOT/build/rv32-nemu-probe"
OPT_ARCHIVE_DIR="$ROOT/build/rv32-nemu-abi"
LOG_DIR="$ROOT/build/ci/logs"
PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"

rm -rf "$PROBE_DIR" "$OPT_ARCHIVE_DIR"
mkdir -p "$LOG_DIR"

set -o pipefail
bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-optimized.log"
grep -q '^status=PASS$' "$PROBE_DIR/evidence/result.txt"
grep -q '^reproducible=true$' "$PROBE_DIR/evidence/result.txt"
grep -q '^single_step=0$' "$PROBE_DIR/evidence/result.txt"
grep -q '^perf_opt=true$' "$PROBE_DIR/evidence/result.txt"
grep -q '^reference_sha256=0e9dc52aeb2f02c399beaa6c5415ff2f4b6c54cfc9aec84f5be0282fe608cd8a$' \
  "$PROBE_DIR/evidence/result.txt"
mv "$PROBE_DIR" "$OPT_ARCHIVE_DIR"

NEMU_SINGLE_STEP=1 bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-single-step.log"
grep -q '^status=PASS$' "$PROBE_DIR/evidence/result.txt"
grep -q '^revision=8601834e4889e6bf3b6113eb5f824ba7689126f5$' \
  "$PROBE_DIR/evidence/result.txt"
grep -q '^reference_sha256=e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e$' \
  "$PROBE_DIR/evidence/result.txt"
grep -q '^reproducible=true$' "$PROBE_DIR/evidence/result.txt"
grep -q '^single_step=1$' "$PROBE_DIR/evidence/result.txt"
grep -q '^perf_opt=false$' "$PROBE_DIR/evidence/result.txt"
grep -q '^CONFIG_ENABLE_INSTR_CNT=y$' "$PROBE_DIR/evidence/generated.config"
grep -q '^# CONFIG_PERF_OPT is not set$' "$PROBE_DIR/evidence/generated.config"

reference_so="$(find "$PROBE_DIR/nemu/build" -maxdepth 1 -type f \
  -name 'riscv32-nemu-interpreter-so*' -print -quit)"
test -n "$reference_so"
test -f "$reference_so"

cached_reference="$(
  AETHERCORE_RV32_NEMU_CANDIDATE="$reference_so" \
    bash tools/ensure_rv32_nemu_single_step.sh
)"
test -f "$cached_reference"
printf '%s\n' "$cached_reference" > "$PATH_FILE"
