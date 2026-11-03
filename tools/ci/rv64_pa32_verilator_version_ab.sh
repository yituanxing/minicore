#!/usr/bin/env bash
# Compare two pinned Verilator releases on byte-identical PA32 RTL and firmware.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
OUT="${OUT:-build/rv64-pa32-verilator-version-ab}"
CYCLES="${CYCLES:-12000000}"
REPEATS="${REPEATS:-2}"
JOBS="${JOBS:-4}"
TOP=AetherCoreV2OpenSbiRV64SimTop
TOOLCHAINS="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
test -s "$FW_BIN" || { echo "missing exact firmware" >&2; exit 2; }
command -v clang++ >/dev/null
[[ "$CYCLES" =~ ^[1-9][0-9]*$ && "$REPEATS" =~ ^[1-9][0-9]*$ ]]
mkdir -p "$OUT"
sha256sum "$FW_BIN" > "$OUT/firmware.sha256"
make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
  TOP="$TOP" ELABORATE_MAIN=aethercore.ElaborateV2VirtualFpgaBoardPA32RV64 rtl
sha256sum "$OUT/rtl-seed/rtl/"*.sv > "$OUT/rtl.sha256"
printf 'variant\tround\trc\tseconds\tcps\n' > "$OUT/results.tsv"

for version in 5.024 5.054; do
  bin="$TOOLCHAINS/verilator-$version/bin/verilator"
  test -x "$bin" || { echo "missing pinned tool: $bin" >&2; exit 3; }
  "$bin" --version > "$OUT/verilator-$version.txt"
  "$bin" --compiler clang -LDFLAGS "-ldl -latomic" -O3 \
    -MAKEFLAGS "CC=clang CXX=clang++ OPT=-march=native OPT_FAST=-O3 OPT_GLOBAL=-O3" \
    --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
    -Mdir "$OUT/obj-$version" \
    -CFLAGS "-std=c++20 -O3 -march=native -DAETHERCORE_SIM_ADAPTIVE_SETTLE -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim" \
    "$OUT/rtl-seed/rtl/"*.sv "$ROOT/sim/opensbi_boot_main.cpp" -j "$JOBS" \
    > "$OUT/build-$version.log" 2>&1 || {
      tail -100 "$OUT/build-$version.log" >&2; exit 4;
    }
done

run_one() {
  local version="$1" round="$2" rc=0 start end
  local logfile="$OUT/run-$version-$round.log"
  start="$(date +%s%N)"
  "$OUT/obj-$version/V$TOP" "$FW_BIN" "$CYCLES" \
    '__PA32_VERILATOR_VERSION_NEVER_MATCH__' 0 0 '' '' 0 "$CYCLES" 0 0 0 \
    > "$logfile" 2>&1 || rc=$?
  end="$(date +%s%N)"
  [[ "$rc" == 2 ]] || { tail -80 "$logfile" >&2; exit 5; }
  grep '^L32_OPENSBI_TIMEOUT ' "$logfile" | tail -1 > "$OUT/snapshot-$version-$round.txt"
  test -s "$OUT/snapshot-$version-$round.txt"
  python3 - "$version" "$round" "$rc" "$start" "$end" "$CYCLES" "$OUT/results.tsv" <<'PY'
import sys
ver,r,rc,a,b,n,path=sys.argv[1:]
sec=(int(b)-int(a))/1e9
with open(path,'a') as f: f.write(f"{ver}\t{r}\t{rc}\t{sec:.6f}\t{int(n)/sec:.3f}\n")
PY
}
for ((round=1; round<=REPEATS; round++)); do
  if ((round%2)); then order=(5.024 5.054); else order=(5.054 5.024); fi
  for version in "${order[@]}"; do run_one "$version" "$round"; done
done
for file in "$OUT"/snapshot-*.txt; do
  diff -u "$OUT/snapshot-5.024-1.txt" "$file" || {
    echo "AETHERCORE_PA32_VERILATOR_VERSION_STATE_MISMATCH file=$file" >&2; exit 6;
  }
done
echo "AETHERCORE_PA32_VERILATOR_VERSION_STATE_MATCH"
python3 - "$OUT/results.tsv" <<'PY'
import csv,statistics,sys
with open(sys.argv[1],newline='') as f: rows=list(csv.DictReader(f,delimiter='\t'))
m={v:statistics.median(float(r['cps']) for r in rows if r['variant']==v)
   for v in ('5.024','5.054')}
print(f"AETHERCORE_PA32_VERILATOR_VERSION_AB_RESULT cps_5024={m['5.024']:.0f} cps_5054={m['5.054']:.0f} speedup={m['5.054']/m['5.024']:.5f}x")
print("AETHERCORE_PA32_VERILATOR_VERSION_PROMOTE_CANDIDATE" if m['5.054']>=1.03*m['5.024'] else "AETHERCORE_PA32_VERILATOR_VERSION_NO_PROMOTION")
PY
