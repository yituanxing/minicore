#!/usr/bin/env bash
# Engine-only A/B: identical PA32 design and fixed zero-response stimulus.
# Do not confuse this with successful OpenSBI or Linux execution.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
BASE="${BASE:-build/rv64-pa32-gsim-source-patch}"
OUT="${OUT:-build/rv64-pa32-gsim-engine-ab}"
CYCLES="${CYCLES:-250000}"
REPEATS="${REPEATS:-3}"
JOBS="${JOBS:-4}"
GSIM_GEN="$BASE/generated"
TOP=AetherCoreV2OpenSbiRV64SimTop
V5="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.054/bin/verilator"
test -s "$GSIM_GEN/$TOP.h"
test -x "$V5"
mkdir -p "$OUT"
sha256sum "$GSIM_GEN/$TOP.h" > "$OUT/gsim-header.sha256"
"$V5" --version > "$OUT/verilator.txt"
make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl" \
  TOP="$TOP" ELABORATE_MAIN=aethercore.ElaborateV2VirtualFpgaBoardPA32RV64 rtl
sha256sum "$OUT/rtl/rtl/"*.sv > "$OUT/rtl.sha256"
clang++-19 -std=gnu++20 -O3 -pthread -lz -DAETHERCORE_USE_GSIM \
  -I"$GSIM_GEN" -I"$ROOT/sim" \
  "$GSIM_GEN/"*.cpp "$ROOT/sim/pa32_sim_engine_ab_main.cpp" \
  -o "$OUT/gsim" > "$OUT/gsim-build.log" 2>&1 || {
    tail -100 "$OUT/gsim-build.log" >&2; exit 4;
  }
"$V5" --compiler clang -LDFLAGS '-ldl -latomic' -O3 \
  -MAKEFLAGS 'CC=clang-19 CXX=clang++-19 OPT=-march=native OPT_FAST=-O3 OPT_GLOBAL=-O3' \
  --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
  -Mdir "$OUT/obj-verilator" \
  -CFLAGS "-std=c++20 -O3 -march=native -I$ROOT/sim" \
  "$OUT/rtl/rtl/"*.sv "$ROOT/sim/pa32_sim_engine_ab_main.cpp" -j "$JOBS" \
  > "$OUT/verilator-build.log" 2>&1 || {
    tail -100 "$OUT/verilator-build.log" >&2; exit 5;
  }
printf 'engine\tround\thost_cps\n' > "$OUT/metrics.tsv"
for ((round=1; round<=REPEATS; round++)); do
  if ((round%2)); then order=(gsim verilator); else order=(verilator gsim); fi
  for engine in "${order[@]}"; do
    if [[ "$engine" == gsim ]]; then exe="$OUT/gsim"; else exe="$OUT/obj-verilator/V$TOP"; fi
    "$exe" "$CYCLES" > "$OUT/$engine.$round.log" 2>&1
    grep '^AETHERCORE_PA32_ENGINE_RESULT ' "$OUT/$engine.$round.log" | tail -1 \
      > "$OUT/$engine.$round.result"
    test -s "$OUT/$engine.$round.result"
    python3 - "$engine" "$round" "$OUT/$engine.$round.result" "$OUT/metrics.tsv" <<'PY'
import pathlib,re,sys
engine,r,path,dst=sys.argv[1:]
fields=dict(re.findall(r'([\w-]+)=([^\s]+)',pathlib.Path(path).read_text()))
with open(dst,'a') as f:
    f.write(f"{engine}\t{r}\t{fields['host_cps']}\n")
PY
  done
done
python3 - "$OUT" <<'PY'
import pathlib,re,statistics,sys
root=pathlib.Path(sys.argv[1])
keys=('guest_cycles','mtime','commits','exceptions','interrupts','digest')
states={}
for kind in ('gsim','verilator'):
    result=next(root.glob(f'{kind}.*.result')).read_text()
    states[kind]=dict(re.findall(r'([\w-]+)=([^\s]+)',result))
mismatch={k:(states['gsim'].get(k),states['verilator'].get(k))
          for k in keys if states['gsim'].get(k)!=states['verilator'].get(k)}
if mismatch:
    print(f"AETHERCORE_PA32_ENGINE_AB_STATE_MISMATCH fields={mismatch}",file=sys.stderr)
    raise SystemExit(8)
print("AETHERCORE_PA32_ENGINE_AB_STATE_MATCH")
with (root/'metrics.tsv').open() as f:
    import csv
    rows=list(csv.DictReader(f,delimiter='\t'))
med={e:statistics.median(float(x['host_cps']) for x in rows if x['engine']==e)
     for e in ('gsim','verilator')}
print(f"AETHERCORE_PA32_ENGINE_AB_RESULT gsim_cps={med['gsim']:.0f} verilator_5054_cps={med['verilator']:.0f} gsim_over_verilator={med['gsim']/med['verilator']:.5f}x workload=zero-response-not-linux")
PY
