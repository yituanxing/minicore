#!/usr/bin/env bash
# Train GSIM on real firmware using LLVM instrprof, then require strict
# 20k bare + 20M firmware differential against the SAME Verilator binary.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
MODEL="${GSIM_MODEL_OUT:?set GSIM_MODEL_OUT}"
REF="${VERILATOR_OUT:?set VERILATOR_OUT}"
FW_BIN="${FW_BIN:?set FW_BIN}"
OUT="${OUT:-build/rv64-pa32-gsim-pgo}"
CYCLES="${PGO_TRAIN_CYCLES:-20000000}"
[[ "$CYCLES" =~ ^[1-9][0-9]*$ ]] || exit 2
test -s "$FW_BIN"
test -x "$MODEL/pa32-gsim-prefix"
test -s "$MODEL/gsim-configuration.txt"
test -x "$REF/obj/VAetherCoreV2OpenSbiRV64SimTop"
command -v llvm-profdata-19 >/dev/null
mkdir -p "$OUT"
sha256sum "$FW_BIN" > "$OUT/firmware.sha256"
cp "$MODEL/gsim-configuration.txt" "$OUT/gsim-configuration.txt"
gen="$MODEL/generated"
test -s "$gen/AetherCoreV2OpenSbiRV64SimTop.h"
echo "AETHERCORE_PA32_GSIM_PGO_STAGE step=INSTRUMENT_BUILD"
timeout --signal=TERM --kill-after=10s 1200s \
  clang++-19 -std=gnu++20 -O3 -march=native -pthread -lz \
    -DAETHERCORE_GSIM_HOST_BRIDGE -fprofile-instr-generate \
    -I"$gen" -I"$ROOT/sim" \
    "$gen/"*.cpp "$ROOT/sim/pa32_gsim_prefix_main.cpp" \
    -o "$OUT/pa32-gsim-instrumented" > "$OUT/instrument-build.log" 2>&1 || {
      tail -90 "$OUT/instrument-build.log" >&2; exit 3;
    }
echo "AETHERCORE_PA32_GSIM_PGO_STAGE step=TRAIN cycles=$CYCLES"
LLVM_PROFILE_FILE="$OUT/train-%p.profraw" \
  timeout --signal=TERM --kill-after=10s 1200s \
    "$OUT/pa32-gsim-instrumented" "$FW_BIN" "$CYCLES" \
    > "$OUT/train.log" 2>&1 || {
      tail -90 "$OUT/train.log" >&2; exit 4;
    }
grep -q "^AETHERCORE_PA32_GSIM_PREFIX guest_cycles=$CYCLES " "$OUT/train.log" || exit 4
shopt -s nullglob
profiles=("$OUT"/*.profraw)
(( ${#profiles[@]} > 0 )) || { echo "missing LLVM PGO profile" >&2; exit 5; }
llvm-profdata-19 merge -output="$OUT/merged.profdata" "${profiles[@]}"
test -s "$OUT/merged.profdata"
echo "AETHERCORE_PA32_GSIM_PGO_STAGE step=OPTIMIZED_BUILD"
timeout --signal=TERM --kill-after=10s 1200s \
  clang++-19 -std=gnu++20 -O3 -march=native -pthread -lz \
    -DAETHERCORE_GSIM_HOST_BRIDGE \
    -fprofile-instr-use="$OUT/merged.profdata" \
    -I"$gen" -I"$ROOT/sim" \
    "$gen/"*.cpp "$ROOT/sim/pa32_gsim_prefix_main.cpp" \
    -o "$OUT/pa32-gsim-prefix" > "$OUT/optimized-build.log" 2>&1 || {
      tail -90 "$OUT/optimized-build.log" >&2; exit 6;
    }
echo "AETHERCORE_PA32_GSIM_PGO_STAGE step=RV64I_20K"
timeout --signal=TERM --kill-after=10s 120s \
  "$OUT/pa32-gsim-prefix" --bare 20000 > "$OUT/prefix-bare.log" 2>&1
grep -qx 'AETHERCORE_PA32_GSIM_BARE_PASS' \
  <(grep '^AETHERCORE_PA32_GSIM_BARE_PASS' "$OUT/prefix-bare.log")
python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
  "$OUT/prefix-bare.log" "$REF/verilator-bare.log" > "$OUT/bare-diff.log"
echo "AETHERCORE_PA32_GSIM_PGO_STAGE step=FIRMWARE_20M"
GSIM_OUT="$OUT" VERILATOR_OUT="$REF" OUT="$OUT/firmware" \
  FW_BIN="$FW_BIN" CYCLES=20000000 TIMEOUT_SECONDS=900 \
  bash tools/ci/rv64_pa32_gsim_firmware_diff.sh
grep -q '^AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS ' \
  "$OUT/firmware/diff-result.txt"
python3 - "$OUT/firmware/performance.txt" <<'PY'
import pathlib,re,sys
rows=[r for r in pathlib.Path(sys.argv[1]).read_text().splitlines()
      if r.startswith("AETHERCORE_PA32_GSIM_FIRMWARE_AB_RESULT ")]
assert len(rows)==1
d=dict(re.findall(r"([\w-]+)=([^\s]+)",rows[0]))
assert d["cycles"]=="20000000"
print(f"AETHERCORE_PA32_GSIM_PGO_20M_PASS "
      f"gsim_seconds={d['gsim_seconds']} "
      f"verilator_seconds={d['verilator_seconds']} "
      f"speedup_vs_verilator={d['gsim_speedup']}")
PY
