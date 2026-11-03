#!/usr/bin/env bash
# Real 20,000-cycle RV64I boot-ROM+host-RAM GSIM/Verilator differential.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
GSIM_OUT="${GSIM_OUT:-build/rv64-pa32-gsim-host-bridge}"
OUT="${OUT:-build/rv64-pa32-gsim-bare-diff}"
CYCLES="${CYCLES:-20000}"
JOBS="${JOBS:-4}"
TOP=AetherCoreV2OpenSbiRV64SimTop
TOOLCHAINS="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
mkdir -p "$OUT"

# A genuine GSIM RV64I PASS is the prerequisite for this A/B.
test -s "$GSIM_OUT/prefix-bare.log" || { echo "missing native GSIM bare log" >&2; exit 2; }
grep -qx 'AETHERCORE_PA32_GSIM_BARE_PASS' "$GSIM_OUT/prefix-bare.log"
cp "$GSIM_OUT/prefix-bare.log" "$OUT/gsim-bare.log"
test "$CYCLES" == 20000 || {
  echo "GSIM fixed-prefix log was collected for exactly 20,000 cycles" >&2; exit 3;
}

bash tools/ensure_verilator_5_054.sh
bin="$TOOLCHAINS/verilator-5.054/bin/verilator"
test -x "$bin"
"$bin" --version > "$OUT/version.txt"

make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-model" \
  ELABORATE_MAIN=aethercore.ElaborateV2VirtualFpgaBoardPA32RV64 \
  TOP="$TOP" rtl
sha256sum "$OUT/rtl-model/rtl/"*.sv > "$OUT/rtl.sha256"

"$bin" --compiler clang -LDFLAGS '-ldl -latomic' -O3 \
  -MAKEFLAGS 'CC=clang-19 CXX=clang++-19 OPT=-march=native OPT_FAST=-O3 OPT_GLOBAL=-O3' \
  --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
  -Mdir "$OUT/obj" \
  -CFLAGS "-std=c++20 -O3 -march=native -I$ROOT/sim" \
  "$OUT/rtl-model/rtl/"*.sv "$ROOT/sim/pa32_verilator_prefix_main.cpp" \
  -j "$JOBS" > "$OUT/verilator-build.log" 2>&1 || {
    tail -100 "$OUT/verilator-build.log" >&2; exit 4;
  }

timeout --signal=TERM --kill-after=10s 120s \
  "$OUT/obj/V$TOP" "$CYCLES" > "$OUT/verilator-bare.log" 2>&1 || {
    tail -100 "$OUT/verilator-bare.log" >&2; exit 5;
  }
python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
  "$OUT/gsim-bare.log" "$OUT/verilator-bare.log" \
  | tee "$OUT/diff-result.txt"
