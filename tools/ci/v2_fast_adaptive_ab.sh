#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FW_BIN="${FW_BIN:-build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
MAX_CYCLES="${MAX_CYCLES:-20000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-20000000}"
OUT_ROOT="${OUT_ROOT:-build/v2-fast-adaptive-ab}"
TOP="AetherCoreV2OpenSbiRV64SimTop"
ELABORATE_MAIN="aethercore.ElaborateV2OpenSbiRV64Fast"

[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware $FW_BIN" >&2; exit 2; }
rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT"

make -f Makefile.l32-linux-boot \
  BUILD_DIR="$OUT_ROOT/rtl-seed" \
  TOP="$TOP" \
  ELABORATE_MAIN="$ELABORATE_MAIN" \
  rtl

build_variant() {
  local variant="$1"
  local extra=""
  [[ "$variant" == adaptive ]] && extra="-DAETHERCORE_SIM_ADAPTIVE_SETTLE"
  local obj="$OUT_ROOT/obj-$variant"
  mkdir -p "$obj"
  verilator -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --cc --exe --build -Wall -Wno-fatal \
    --top-module "$TOP" -Mdir "$obj" \
    -CFLAGS "-std=c++20 -O3 -march=native $extra -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim" \
    "$OUT_ROOT/rtl-seed/rtl/"*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
    -j "$(nproc)" 2>&1 | tee "$OUT_ROOT/$variant.compile.log"
}

printf 'variant\trc\tseconds\tcps\tprogress_cps\n' > "$OUT_ROOT/results.tsv"
for variant in reference adaptive; do
  build_variant "$variant"
  sim="$OUT_ROOT/obj-$variant/V$TOP"
  log="$OUT_ROOT/$variant.run.log"
  start_ns="$(date +%s%N)"
  set +e
  "$sim" "$FW_BIN" "$MAX_CYCLES" '__AETHERCORE_FAST_ADAPTIVE_NEVER_MATCH__' \
    0 0 '' '' 0 "$PROGRESS_INTERVAL_CYCLES" 0 0 0 >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"
  [[ "$rc" == 2 ]] || { echo "ERROR: unexpected runner rc=$rc for $variant" >&2; tail -80 "$log"; exit 3; }
  grep '^L32_OPENSBI_TIMEOUT ' "$log" | tail -1 > "$OUT_ROOT/$variant.snapshot.txt"
  progress="$(grep 'L32_SIM_PROGRESS ' "$log" | tail -1 | sed -n 's/.*cycles-per-second=\([^ ]*\).*/\1/p' || true)"
  [[ -n "$progress" ]] || progress="NA"
  secs="$(python3 - "$start_ns" "$end_ns" <<'PY'
import sys
print(f"{(int(sys.argv[2])-int(sys.argv[1]))/1e9:.6f}")
PY
)"
  cps="$(python3 - "$MAX_CYCLES" "$secs" <<'PY'
import sys
print(f"{int(sys.argv[1])/float(sys.argv[2]):.3f}")
PY
)"
  printf '%s\t%s\t%s\t%s\t%s\n' "$variant" "$rc" "$secs" "$cps" "$progress" >> "$OUT_ROOT/results.tsv"
done

diff -u "$OUT_ROOT/reference.snapshot.txt" "$OUT_ROOT/adaptive.snapshot.txt" > "$OUT_ROOT/snapshot.diff" || {
  echo "AETHERCORE_FAST_ADAPTIVE_STATE_MISMATCH" >&2
  cat "$OUT_ROOT/snapshot.diff" >&2
  exit 4
}
echo "AETHERCORE_FAST_ADAPTIVE_STATE_MATCH"

python3 - "$OUT_ROOT/results.tsv" <<'PY'
import csv,sys
with open(sys.argv[1], newline='') as f:
    rows={r['variant']:r for r in csv.DictReader(f,delimiter='\t')}
ref=float(rows['reference']['cps'])
ada=float(rows['adaptive']['cps'])
s=ada/ref
print(f"AETHERCORE_FAST_ADAPTIVE_AB_RESULT reference_cps={ref:.0f} adaptive_cps={ada:.0f} speedup={s:.4f}x")
print("AETHERCORE_FAST_ADAPTIVE_PROMOTE_CANDIDATE" if s>=1.03 else "AETHERCORE_FAST_ADAPTIVE_NO_PROMOTION")
PY
