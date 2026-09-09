#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
MAX_CYCLES="${MAX_CYCLES:-20000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-$MAX_CYCLES}"
OUT_ROOT="${OUT_ROOT:-build/v2-fast-internal-profile}"
TOP="AetherCoreV2OpenSbiRV64SimTop"
ELABORATE_MAIN="aethercore.ElaborateV2OpenSbiRV64Fast"

[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware $FW_BIN" >&2; exit 2; }
command -v gprof >/dev/null || { echo "ERROR: gprof missing" >&2; exit 3; }
command -v verilator_profcfunc >/dev/null || { echo "ERROR: verilator_profcfunc missing" >&2; exit 4; }

rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT"

make -f Makefile.l32-linux-boot \
  BUILD_DIR="$OUT_ROOT/rtl-seed" \
  TOP="$TOP" \
  ELABORATE_MAIN="$ELABORATE_MAIN" \
  rtl

obj="$OUT_ROOT/obj-profile"
mkdir -p "$obj"

build_start_ns="$(date +%s%N)"
verilator --prof-cfuncs \
  -LDFLAGS -ldl \
  -O3 \
  -MAKEFLAGS 'CC=gcc CXX=g++ OPT=-march=native OPT_FAST=-O3 OPT_GLOBAL=-O3' \
  --cc --exe --build -Wall -Wno-fatal \
  --top-module "$TOP" -Mdir "$obj" \
  -CFLAGS "-std=c++20 -O3 -march=native -DAETHERCORE_SIM_ADAPTIVE_SETTLE -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim" \
  "$OUT_ROOT/rtl-seed/rtl/"*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
  -j "$(nproc)" 2>&1 | tee "$OUT_ROOT/profile.compile.log"
build_end_ns="$(date +%s%N)"

python3 - "$build_start_ns" "$build_end_ns" > "$OUT_ROOT/build-seconds.txt" <<'PY'
import sys
print(f"{(int(sys.argv[2]) - int(sys.argv[1])) / 1e9:.6f}")
PY

sim="$obj/V$TOP"
log="$OUT_ROOT/profile.run.log"
rm -f gmon.out "$OUT_ROOT/gmon.out"

run_start_ns="$(date +%s%N)"
set +e
"$sim" "$FW_BIN" "$MAX_CYCLES" '__AETHERCORE_INTERNAL_PROFILE_NEVER_MATCH__' \
  0 0 '' '' 0 "$PROGRESS_INTERVAL_CYCLES" 0 0 0 >"$log" 2>&1
rc=$?
set -e
run_end_ns="$(date +%s%N)"

[[ "$rc" == 2 ]] || {
  echo "ERROR: unexpected runner rc=$rc" >&2
  tail -100 "$log" >&2
  exit 10
}

grep '^L32_OPENSBI_TIMEOUT ' "$log" | tail -1 > "$OUT_ROOT/snapshot.txt"
[[ -s "$OUT_ROOT/snapshot.txt" ]] || {
  echo "ERROR: missing timeout snapshot" >&2
  exit 11
}

[[ -s gmon.out ]] || {
  echo "ERROR: --prof-cfuncs run did not produce gmon.out" >&2
  exit 12
}
mv gmon.out "$OUT_ROOT/gmon.out"

gprof -b "$sim" "$OUT_ROOT/gmon.out" > "$OUT_ROOT/gprof.out"
verilator_profcfunc "$OUT_ROOT/gprof.out" > "$OUT_ROOT/profcfunc.log"

python3 - "$run_start_ns" "$run_end_ns" "$MAX_CYCLES" > "$OUT_ROOT/run-summary.txt" <<'PY'
import sys
start, end, cycles = map(int, sys.argv[1:])
seconds = (end - start) / 1e9
cps = cycles / seconds
print(f"seconds={seconds:.6f}")
print(f"cycles={cycles}")
print(f"cps={cps:.3f}")
PY

echo "AETHERCORE_INTERNAL_PROFILE_STATE"
cat "$OUT_ROOT/snapshot.txt"
echo "AETHERCORE_INTERNAL_PROFILE_RUNTIME"
cat "$OUT_ROOT/run-summary.txt"
echo "AETHERCORE_INTERNAL_PROFILE_TOP"
head -120 "$OUT_ROOT/profcfunc.log"
