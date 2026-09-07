#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
MAX_CYCLES="${MAX_CYCLES:-20000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-20000000}"
OUT_ROOT="${OUT_ROOT:-build/v2-thread-count-ab}"
TOP="AetherCoreV2OpenSbiRV64SimTop"
ELABORATE_MAIN="aethercore.ElaborateV2OpenSbiRV64Fast"

[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware $FW_BIN" >&2; exit 2; }
rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT"

make -f Makefile.l32-linux-boot \
  BUILD_DIR="$OUT_ROOT/rtl-seed" TOP="$TOP" ELABORATE_MAIN="$ELABORATE_MAIN" rtl

build_variant() {
  local threads="$1"
  local obj="$OUT_ROOT/obj-t$threads"
  local thread_flags=""
  [[ "$threads" == 1 ]] || thread_flags="--threads $threads"
  mkdir -p "$obj"
  verilator -LDFLAGS -ldl -O3 $thread_flags -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --cc --exe --build -Wall -Wno-fatal \
    --top-module "$TOP" -Mdir "$obj" \
    -CFLAGS "-std=c++20 -O3 -march=native -DAETHERCORE_SIM_ADAPTIVE_SETTLE -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim" \
    "$OUT_ROOT/rtl-seed/rtl/"*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
    -j "$(nproc)" 2>&1 | tee "$OUT_ROOT/t$threads.compile.log"
}

printf 'threads\trc\tseconds\tcps\tprogress_cps\n' > "$OUT_ROOT/results.tsv"
for threads in 1 2 4; do
  build_variant "$threads"
  sim="$OUT_ROOT/obj-t$threads/V$TOP"
  log="$OUT_ROOT/t$threads.run.log"
  start_ns="$(date +%s%N)"
  set +e
  "$sim" "$FW_BIN" "$MAX_CYCLES" '__AETHERCORE_THREAD_AB_NEVER_MATCH__' \
    0 0 '' '' 0 "$PROGRESS_INTERVAL_CYCLES" 0 0 0 >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"
  [[ "$rc" == 2 ]] || { echo "ERROR: unexpected rc=$rc threads=$threads" >&2; tail -80 "$log"; exit 3; }
  grep '^L32_OPENSBI_TIMEOUT ' "$log" | tail -1 > "$OUT_ROOT/t$threads.snapshot.txt"
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
  printf '%s\t%s\t%s\t%s\t%s\n' "$threads" "$rc" "$secs" "$cps" "$progress" >> "$OUT_ROOT/results.tsv"
done

for threads in 2 4; do
  diff -u "$OUT_ROOT/t1.snapshot.txt" "$OUT_ROOT/t$threads.snapshot.txt" > "$OUT_ROOT/t1-vs-t$threads.diff" || {
    echo "AETHERCORE_THREAD_STATE_MISMATCH threads=$threads" >&2
    cat "$OUT_ROOT/t1-vs-t$threads.diff" >&2
    exit 4
  }
done
echo "AETHERCORE_THREAD_STATE_MATCH variants=3"

python3 - "$OUT_ROOT/results.tsv" <<'PY'
import csv,sys
with open(sys.argv[1], newline='') as f:
    rows={int(r['threads']):r for r in csv.DictReader(f,delimiter='\t')}
base=float(rows[1]['cps'])
for t in (1,2,4):
    cps=float(rows[t]['cps'])
    print(f"AETHERCORE_THREAD_AB_POINT threads={t} cps={cps:.0f} speedup={cps/base:.4f}x")
best=max((float(rows[t]['cps']),t) for t in rows)
print(f"AETHERCORE_THREAD_AB_BEST threads={best[1]} cps={best[0]:.0f} speedup={best[0]/base:.4f}x")
PY
