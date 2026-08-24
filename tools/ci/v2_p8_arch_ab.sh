#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BASELINE_SHA="${BASELINE_SHA:-040cb17de319a0c5ab8d4b34ed5e957b158d9673}"
TARGET_SHA="${TARGET_SHA:-$(git rev-parse HEAD)}"
FW_BIN="${FW_BIN:-$ROOT/build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
MILESTONE="${MILESTONE:-clocksource: riscv_clocksource}"
MAX_CYCLES="${MAX_CYCLES:-120000000}"
PROGRESS_INTERVAL_CYCLES="${PROGRESS_INTERVAL_CYCLES:-10000000}"
OUT_ROOT="${OUT_ROOT:-$ROOT/build/v2-p8-arch-ab}"
TOP="${TOP:-AetherCoreV2OpenSbiRV64SimTop}"
ELABORATE_MAIN="${ELABORATE_MAIN:-aethercore.ElaborateV2OpenSbiRV64}"

[[ "$(git rev-parse HEAD)" == "$TARGET_SHA" ]] || {
  echo "ERROR: target checkout mismatch expected=$TARGET_SHA actual=$(git rev-parse HEAD)" >&2
  exit 2
}
git cat-file -e "$BASELINE_SHA^{commit}"
[[ -s "$FW_BIN" ]] || { echo "ERROR: missing firmware: $FW_BIN" >&2; exit 3; }

mkdir -p "$OUT_ROOT"
FW_BIN="$(realpath "$FW_BIN")"
sha256sum "$FW_BIN" | tee "$OUT_ROOT/workload.sha256"
printf 'baseline_sha=%s\ntarget_sha=%s\nmilestone=%s\nmax_cycles=%s\nmeasurement_overlay=host-only-marker\n' \
  "$BASELINE_SHA" "$TARGET_SHA" "$MILESTONE" "$MAX_CYCLES" \
  | tee "$OUT_ROOT/identity.txt"

TMP_ROOT="$(mktemp -d "${RUNNER_TEMP:-/tmp}/aethercore-v2-p8-arch-ab.XXXXXX")"
BASE_SRC="$TMP_ROOT/baseline-src"
TARGET_SRC="$TMP_ROOT/target-src"
cleanup() {
  git worktree remove --force "$BASE_SRC" >/dev/null 2>&1 || true
  git worktree remove --force "$TARGET_SRC" >/dev/null 2>&1 || true
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

git worktree add --detach "$BASE_SRC" "$BASELINE_SHA"
git worktree add --detach "$TARGET_SRC" "$TARGET_SHA"
[[ "$(git -C "$BASE_SRC" rev-parse HEAD)" == "$BASELINE_SHA" ]]
[[ "$(git -C "$TARGET_SRC" rev-parse HEAD)" == "$TARGET_SHA" ]]

# The P8 host hook normally snapshots at the full PID1 proof marker. This A/B
# intentionally stops earlier at a bounded Linux milestone, so install the same
# observation-only marker string into both detached source trees. No production
# RTL or simulator memory ordering is changed, and the main checkout stays clean.
install_marker_overlay() {
  local src="$1"
  local hook="$src/sim/v2_rv64_opensbi_shim/v2_perf_host_hook.h"
  python3 - "$hook" "$MILESTONE" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
marker = sys.argv[2]
try:
    marker.encode('ascii')
except UnicodeEncodeError as exc:
    raise SystemExit(f'performance marker must be ASCII: {marker!r}') from exc

old = 'constexpr char kMarker[] = "RV64 USER UART IRQ OK";'
text = path.read_text()
if text.count(old) != 1:
    raise SystemExit(f'expected exactly one qualified P8 host marker in {path}')
new = 'constexpr char kMarker[] = ' + json.dumps(marker) + ';'
path.write_text(text.replace(old, new))
print(f'AETHERCORE_ARCH_AB_MARKER_OVERLAY path={path} marker={marker}')
PY
}

install_marker_overlay "$BASE_SRC"
install_marker_overlay "$TARGET_SRC"

required_fields='cycles commits dispatch_accepted dispatch_blocked rob0 rob1 rob2 rob3 rob4 issue_int issue_mul issue_div issue_branch issue_mem system_completion selective_candidate selective_bypass bypass_compute_head bypass_branch_head bypass_memory_head bypass_other_head lsu_compute_overlap head_not_ready head_ready_not_issued commit_idle_nonempty compute_head branch_head memory_head system_head interrupt_hold wfi_halted lsu_busy memory_launch_blocked mem_req mem_resp ptw_active completion_collision completion_backpressure'

extract_marker_snapshot() {
  local run_log="$1"
  local out="$2"
  python3 - "$run_log" "$out" $required_fields <<'PY'
from pathlib import Path
import re, sys

log = Path(sys.argv[1])
out = Path(sys.argv[2])
required = sys.argv[3:]
lines = log.read_text(errors='replace').splitlines()
snapshots = []
current = None
reason = None
for line in lines:
    if 'AETHERCORE_V2_PERF reason=' in line:
        if current is not None:
            snapshots.append((reason, current))
        m = re.search(r'AETHERCORE_V2_PERF reason=([^ ]+)', line)
        reason = m.group(1) if m else None
        current = {}
    if current is not None and 'AETHERCORE_V2_PERF' in line:
        for key, raw in re.findall(r'([a-z0-9_]+)=\s*([0-9]+)', line):
            current[key] = int(raw)
if current is not None:
    snapshots.append((reason, current))

complete = [(r, s) for r, s in snapshots if all(k in s for k in required)]
marker = [(r, s) for r, s in complete if r == 'marker']
if not marker:
    raise SystemExit(f'no complete marker P8 snapshot: total={len(snapshots)} complete={len(complete)}')
s = marker[-1][1]
out.write_text('\n'.join(f'{k}={s[k]}' for k in required) + '\n')
print(f'AETHERCORE_ARCH_AB_SNAPSHOT cycles={s["cycles"]} commits={s["commits"]}')
PY
}

run_variant() {
  local variant="$1"
  local src="$2"
  local sha="$3"
  local build="$OUT_ROOT/build-$variant"
  local run_log="$OUT_ROOT/$variant.run.log"

  rm -rf "$build"
  mkdir -p "$build"
  echo "AETHERCORE_ARCH_AB_BEGIN variant=$variant sha=$sha"

  make -C "$src" -f Makefile.l32-linux-boot \
    BUILD_DIR="$build" \
    TOP="$TOP" \
    ELABORATE_MAIN="$ELABORATE_MAIN" \
    FW_BIN="$FW_BIN" \
    JOBS="$(nproc)" \
    MAX_CYCLES="$MAX_CYCLES" \
    MILESTONE="$MILESTONE" \
    PROGRESS_INTERVAL_CYCLES="$PROGRESS_INTERVAL_CYCLES" \
    VERILATOR='verilator -LDFLAGS -ldl' \
    SIM_CXXFLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_V2_PERF -I$src/sim/v2_rv64_opensbi_shim -I$src/sim" \
    run-local 2>&1 | tee "$run_log"

  grep -q '^L32_RUNTIME_MILESTONE_PASS ' "$run_log"
  grep -Fq "$MILESTONE" "$run_log"
  extract_marker_snapshot "$run_log" "$OUT_ROOT/$variant.snapshot.txt"
  echo "AETHERCORE_ARCH_AB_END variant=$variant sha=$sha"
}

run_variant baseline "$BASE_SRC" "$BASELINE_SHA"
run_variant target "$TARGET_SRC" "$TARGET_SHA"

python3 - "$OUT_ROOT/baseline.snapshot.txt" "$OUT_ROOT/target.snapshot.txt" "$OUT_ROOT/result.txt" <<'PY'
from pathlib import Path
import sys

def load(path):
    vals = {}
    for line in Path(path).read_text().splitlines():
        if not line.strip():
            continue
        k, v = line.split('=', 1)
        vals[k] = int(v)
    return vals

b = load(sys.argv[1])
t = load(sys.argv[2])
out = Path(sys.argv[3])

bc, tc = b['cycles'], t['cycles']
bm, tm = b['commits'], t['commits']
cycle_reduction = (bc - tc) / bc
speedup = bc / tc
bipc, tipc = bm / bc, tm / tc

lines = [
    f'baseline_cycles={bc}',
    f'target_cycles={tc}',
    f'baseline_commits={bm}',
    f'target_commits={tm}',
    f'baseline_ipc={bipc:.9f}',
    f'target_ipc={tipc:.9f}',
    f'cycle_reduction_pct={100.0 * cycle_reduction:.6f}',
    f'architectural_speedup={speedup:.6f}',
]
for key in ('dispatch_blocked','rob4','ptw_active','lsu_busy','completion_collision','completion_backpressure'):
    lines.append(f'baseline_{key}_pct={100.0 * b[key] / bc:.6f}')
    lines.append(f'target_{key}_pct={100.0 * t[key] / tc:.6f}')
out.write_text('\n'.join(lines) + '\n')
print('AETHERCORE_ARCH_AB_RESULT ' + ' '.join(lines[:8]))
if cycle_reduction >= 0.01:
    print(f'AETHERCORE_ARCH_AB_PROMOTE_CANDIDATE cycle_reduction_pct={100.0 * cycle_reduction:.3f}')
elif cycle_reduction <= 0.0:
    print(f'AETHERCORE_ARCH_AB_REJECT_CANDIDATE cycle_reduction_pct={100.0 * cycle_reduction:.3f}')
else:
    print(f'AETHERCORE_ARCH_AB_REVIEW_CANDIDATE cycle_reduction_pct={100.0 * cycle_reduction:.3f}')
PY
