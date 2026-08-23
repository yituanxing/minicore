#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

FW_BIN="${FW_BIN:-build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
MAX_CYCLES="${MAX_CYCLES:-12000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-10000000}"
OUT_ROOT="${OUT_ROOT:-build/v2-p8-runtime-ab}"
TOP="${TOP:-AetherCoreV2OpenSbiRV64SimTop}"
ELABORATE_MAIN="${ELABORATE_MAIN:-aethercore.ElaborateV2OpenSbiRV64}"

[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware: $FW_BIN" >&2; exit 2; }
mkdir -p "$OUT_ROOT"
SEED_BUILD="$OUT_ROOT/rtl-seed"

make -f Makefile.l32-linux-boot \
  BUILD_DIR="$SEED_BUILD" \
  TOP="$TOP" \
  ELABORATE_MAIN="$ELABORATE_MAIN" \
  rtl

required_fields='cycles commits dispatch_accepted dispatch_blocked rob0 rob1 rob2 rob3 rob4 issue_int issue_mul issue_div issue_branch issue_mem system_completion selective_candidate selective_bypass bypass_compute_head bypass_branch_head bypass_memory_head bypass_other_head lsu_compute_overlap head_not_ready head_ready_not_issued commit_idle_nonempty compute_head branch_head memory_head system_head interrupt_hold wfi_halted lsu_busy memory_launch_blocked mem_req mem_resp ptw_active completion_collision completion_backpressure'

build_variant() {
  local variant="$1"
  local extra=""
  [[ "$variant" == requestguard ]] && extra='-DAETHERCORE_SIM_REQUEST_GUARD_SETTLE'
  [[ "$variant" == adaptive || "$variant" == requestguard ]] || { echo "bad variant: $variant" >&2; return 2; }

  local obj="$OUT_ROOT/obj-$variant"
  local log="$OUT_ROOT/$variant.compile.log"
  rm -rf "$obj"
  mkdir -p "$obj"

  local cflags="-std=c++20 -O3 -march=native -DAETHERCORE_V2_PERF $extra -I$ROOT/sim/v2_rv64_opensbi_shim -I$ROOT/sim"
  verilator -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --cc --exe --build -Wall -Wno-fatal \
    --top-module "$TOP" -Mdir "$obj" \
    -CFLAGS "$cflags" \
    "$SEED_BUILD"/rtl/*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
    -j "$(nproc)" 2>&1 | tee "$log"
}

extract_snapshot() {
  local run_log="$1"
  local out="$2"
  python3 - "$run_log" "$out" $required_fields <<'PY'
from pathlib import Path
import re, sys
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
out = Path(sys.argv[2])
required = sys.argv[3:]
snapshots = []
current = None
for line in lines:
    if 'AETHERCORE_V2_PERF reason=' in line:
        if current is not None:
            snapshots.append(current)
        current = {}
    if current is not None and 'AETHERCORE_V2_PERF' in line:
        for key, raw in re.findall(r'([a-z0-9_]+)=\s*([0-9]+)', line):
            current[key] = int(raw)
if current is not None:
    snapshots.append(current)
complete = [s for s in snapshots if all(k in s for k in required)]
if not complete:
    raise SystemExit(f'no complete P8 snapshot: snapshots={len(snapshots)}')
s = complete[-1]
out.write_text('\n'.join(f'{k}={s[k]}' for k in required) + '\n')
print(f'AETHERCORE_RUNTIME_AB_SNAPSHOT cycles={s["cycles"]} commits={s["commits"]}')
PY
}

RESULTS="$OUT_ROOT/results.tsv"
printf 'variant\trunner_rc\trun_seconds\twall_cycles_per_second\tprogress_cycles_per_second\n' > "$RESULTS"

for variant in adaptive requestguard; do
  build_variant "$variant"
  sim="$OUT_ROOT/obj-$variant/V$TOP"
  [[ -x "$sim" ]] || { echo "ERROR: missing simulator $sim" >&2; exit 3; }
  log="$OUT_ROOT/$variant.run.log"

  start_ns="$(date +%s%N)"
  set +e
  "$sim" "$FW_BIN" "$MAX_CYCLES" '__AETHERCORE_RUNTIME_AB_NEVER_MATCH__' \
    0 0 '' '' 0 "$PROGRESS_INTERVAL_CYCLES" 0 0 0 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e
  end_ns="$(date +%s%N)"

  run_seconds="$(python3 - "$start_ns" "$end_ns" <<'PY'
import sys
print(f'{(int(sys.argv[2]) - int(sys.argv[1])) / 1e9:.6f}')
PY
)"
  wall_cps="$(python3 - "$MAX_CYCLES" "$run_seconds" <<'PY'
import sys
print(f'{int(sys.argv[1]) / float(sys.argv[2]):.3f}')
PY
)"
  progress_cps="$(grep 'L32_SIM_PROGRESS ' "$log" | tail -n 1 | sed -n 's/.*cycles-per-second=\([^ ]*\).*/\1/p')"
  [[ -n "$progress_cps" ]] || { echo "ERROR: no progress throughput for $variant" >&2; exit 4; }
  extract_snapshot "$log" "$OUT_ROOT/$variant.snapshot.txt"
  printf '%s\t%s\t%s\t%s\t%s\n' "$variant" "$rc" "$run_seconds" "$wall_cps" "$progress_cps" >> "$RESULTS"
done

adaptive_rc="$(awk -F '\t' '$1=="adaptive" {print $2}' "$RESULTS")"
guard_rc="$(awk -F '\t' '$1=="requestguard" {print $2}' "$RESULTS")"
[[ "$adaptive_rc" == "$guard_rc" ]] || {
  echo "AETHERCORE_RUNTIME_AB_RC_MISMATCH adaptive=$adaptive_rc requestguard=$guard_rc" >&2
  exit 19
}

diff -u "$OUT_ROOT/adaptive.snapshot.txt" "$OUT_ROOT/requestguard.snapshot.txt" \
  > "$OUT_ROOT/adaptive-vs-requestguard.diff" || {
    echo 'AETHERCORE_RUNTIME_AB_COUNTER_MISMATCH' >&2
    cat "$OUT_ROOT/adaptive-vs-requestguard.diff" >&2
    exit 20
  }
echo 'AETHERCORE_RUNTIME_AB_COUNTERS_MATCH fields=38'

python3 - "$RESULTS" <<'PY'
import csv, sys
with open(sys.argv[1], newline='') as f:
    rows = {r['variant']: r for r in csv.DictReader(f, delimiter='\t')}
adaptive = float(rows['adaptive']['wall_cycles_per_second'])
guard = float(rows['requestguard']['wall_cycles_per_second'])
speedup = guard / adaptive
print(f'AETHERCORE_REQUEST_GUARD_AB_RESULT adaptive_cps={adaptive:.0f} requestguard_cps={guard:.0f} speedup={speedup:.4f}x')
if speedup >= 1.03:
    print(f'AETHERCORE_REQUEST_GUARD_AB_PROMOTE_CANDIDATE speedup={speedup:.4f}x')
else:
    print(f'AETHERCORE_REQUEST_GUARD_AB_NO_PROMOTION speedup={speedup:.4f}x')
PY
