#!/usr/bin/env bash
# Real RV64I+A/host-transport differential: SW, LW, LR.W, SC.W, AMOADD.W.
# Unmodified CPU RTL and single copy of host byte-addressed Memory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
GSIM_OUT="${GSIM_OUT:-build/rv64-pa32-gsim-host-bridge}"
VERILATOR_OUT="${VERILATOR_OUT:-build/rv64-pa32-gsim-bare-diff}"
OUT="${OUT:-build/rv64-pa32-gsim-memory-diff}"
TOP=AetherCoreV2OpenSbiRV64SimTop
mkdir -p "$OUT"
test -x "$GSIM_OUT/pa32-gsim-prefix"
test -x "$VERILATOR_OUT/obj/V$TOP"
timeout --signal=TERM --kill-after=10s 120s \
  "$GSIM_OUT/pa32-gsim-prefix" --memory 20000 \
  > "$OUT/gsim-memory.log" 2>&1 || {
    tail -80 "$OUT/gsim-memory.log" >&2; exit 5;
  }
timeout --signal=TERM --kill-after=10s 120s \
  "$VERILATOR_OUT/obj/V$TOP" --memory 20000 \
  > "$OUT/verilator-memory.log" 2>&1 || {
    tail -80 "$OUT/verilator-memory.log" >&2; exit 6;
  }
grep -qx 'AETHERCORE_PA32_GSIM_MEMORY_PASS final_word=85' "$OUT/gsim-memory.log"
grep -qx 'AETHERCORE_PA32_VERILATOR_MEMORY_PASS final_word=85' "$OUT/verilator-memory.log"
python3 tools/ci/rv64_pa32_gsim_memory_diff.py \
  "$OUT/gsim-memory.log" "$OUT/verilator-memory.log" \
  | tee "$OUT/diff-result.txt"
