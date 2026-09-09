#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
MAX_CYCLES="${MAX_CYCLES:-10000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-$MAX_CYCLES}"
REPEATS="${REPEATS:-3}"
OUT_ROOT="${OUT_ROOT:-build/v2-fast-host-compiler-matrix}"
TOP="AetherCoreV2OpenSbiRV64SimTop"
ELABORATE_MAIN="aethercore.ElaborateV2OpenSbiRV64Fast"

[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware $FW_BIN" >&2; exit 2; }
command -v g++ >/dev/null
command -v clang++ >/dev/null

rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT"

make -f Makefile.l32-linux-boot \
  BUILD_DIR="$OUT_ROOT/rtl-seed" \
  TOP="$TOP" \
  ELABORATE_MAIN="$ELABORATE_MAIN" \
  rtl

printf 'compiler\n' > "$OUT_ROOT/compiler-versions.txt"
g++ --version | head -1 >> "$OUT_ROOT/compiler-versions.txt"
clang++ --version | head -1 >> "$OUT_ROOT/compiler-versions.txt"
verilator --version >> "$OUT_ROOT/compiler-versions.txt"

variants=(gcc-o3 gcc-os clang-o3 clang-os)

variant_fields() {
  case "$1" in
    gcc-o3)   printf '%s\t%s\t%s\t%s\n' gcc gcc g++ -O3 ;;
    gcc-os)   printf '%s\t%s\t%s\t%s\n' gcc gcc g++ -Os ;;
    clang-o3) printf '%s\t%s\t%s\t%s\n' clang clang clang++ -O3 ;;
    clang-os) printf '%s\t%s\t%s\t%s\n' clang clang clang++ -Os ;;
    *) echo "ERROR: unknown variant $1" >&2; exit 10 ;;
  esac
}

printf 'variant\tcompiler\topt\tcompile_seconds\n' > "$OUT_ROOT/build-results.tsv"

build_variant() {
  local variant="$1"
  local compiler_kind cc cxx opt
  IFS=$'\t' read -r compiler_kind cc cxx opt < <(variant_fields "$variant")
  local obj="$OUT_ROOT/obj-$variant"
  mkdir -p "$obj"

  local start_ns end_ns secs
  start_ns="$(date +%s%N)"
  verilator --compiler "$compiler_kind" \
    -LDFLAGS -ldl \
    -O3 \
    -MAKEFLAGS "CC=$cc CXX=$cxx OPT=-march=native OPT_FAST=$opt OPT_GLOBAL=$opt" \
    --cc --exe --build -Wall -Wno-fatal \
    --top-module "$TOP" -Mdir "$obj" \
    -CFLAGS "-std=c++20 $opt -march=native -DAETHERCORE_SIM_ADAPTIVE_SETTLE -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim" \
    "$OUT_ROOT/rtl-seed/rtl/"*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
    -j "$(nproc)" 2>&1 | tee "$OUT_ROOT/$variant.compile.log"
  end_ns="$(date +%s%N)"
  secs="$(python3 - "$start_ns" "$end_ns" <<'PY'
import sys
print(f"{(int(sys.argv[2])-int(sys.argv[1]))/1e9:.6f}")
PY
)"
  printf '%s\t%s\t%s\t%s\n' "$variant" "$compiler_kind" "$opt" "$secs" >> "$OUT_ROOT/build-results.tsv"
}

for variant in "${variants[@]}"; do
  build_variant "$variant"
done

printf 'variant\tround\trc\tseconds\tcps\tprogress_cps\n' > "$OUT_ROOT/run-results.tsv"

run_variant() {
  local variant="$1"
  local round="$2"
  local sim="$OUT_ROOT/obj-$variant/V$TOP"
  local log="$OUT_ROOT/$variant.round-$round.run.log"
  local snapshot="$OUT_ROOT/$variant.round-$round.snapshot.txt"
  local start_ns end_ns rc secs cps progress

  start_ns="$(date +%s%N)"
  set +e
  "$sim" "$FW_BIN" "$MAX_CYCLES" '__AETHERCORE_HOST_MATRIX_NEVER_MATCH__' \
    0 0 '' '' 0 "$PROGRESS_INTERVAL_CYCLES" 0 0 0 >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"

  [[ "$rc" == 2 ]] || {
    echo "ERROR: unexpected runner rc=$rc for $variant round=$round" >&2
    tail -80 "$log"
    exit 20
  }

  grep '^L32_OPENSBI_TIMEOUT ' "$log" | tail -1 > "$snapshot"
  [[ -s "$snapshot" ]] || {
    echo "ERROR: missing timeout snapshot for $variant round=$round" >&2
    exit 21
  }

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
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$variant" "$round" "$rc" "$secs" "$cps" "$progress" >> "$OUT_ROOT/run-results.tsv"
}

for round in $(seq 1 "$REPEATS"); do
  if (( round % 2 == 1 )); then
    order=("${variants[@]}")
  else
    order=(clang-os clang-o3 gcc-os gcc-o3)
  fi
  for variant in "${order[@]}"; do
    run_variant "$variant" "$round"
  done
done

reference_snapshot="$OUT_ROOT/gcc-o3.round-1.snapshot.txt"
for snapshot in "$OUT_ROOT"/*.snapshot.txt; do
  diff -u "$reference_snapshot" "$snapshot" > "$snapshot.diff" || {
    echo "AETHERCORE_HOST_MATRIX_STATE_MISMATCH reference=$reference_snapshot candidate=$snapshot" >&2
    cat "$snapshot.diff" >&2
    exit 30
  }
done
echo "AETHERCORE_HOST_MATRIX_STATE_MATCH"

python3 - "$OUT_ROOT/run-results.tsv" "$OUT_ROOT/build-results.tsv" <<'PY'
import csv
import statistics
import sys

run_path, build_path = sys.argv[1:3]

with open(run_path, newline='') as f:
    rows = list(csv.DictReader(f, delimiter='\t'))

by_variant = {}
for row in rows:
    by_variant.setdefault(row['variant'], []).append(float(row['cps']))

with open(build_path, newline='') as f:
    builds = {row['variant']: row for row in csv.DictReader(f, delimiter='\t')}

medians = {k: statistics.median(v) for k, v in by_variant.items()}
baseline = medians['gcc-o3']
best = max(medians, key=medians.get)

print("AETHERCORE_HOST_MATRIX_SUMMARY")
for variant in ('gcc-o3','gcc-os','clang-o3','clang-os'):
    vals = by_variant[variant]
    median = medians[variant]
    speedup = median / baseline
    compile_s = float(builds[variant]['compile_seconds'])
    print(
        f"variant={variant} runs={len(vals)} "
        f"median_cps={median:.0f} speedup_vs_gcc_o3={speedup:.4f}x "
        f"compile_seconds={compile_s:.3f}"
    )

best_speedup = medians[best] / baseline
print(
    f"AETHERCORE_HOST_MATRIX_BEST variant={best} "
    f"median_cps={medians[best]:.0f} speedup_vs_gcc_o3={best_speedup:.4f}x"
)
if best != 'gcc-o3' and best_speedup >= 1.03:
    print("AETHERCORE_HOST_MATRIX_PROMOTE_CANDIDATE")
else:
    print("AETHERCORE_HOST_MATRIX_NO_PROMOTION")
PY
