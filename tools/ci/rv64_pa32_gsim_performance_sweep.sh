#!/usr/bin/env bash
# Compare SuperNode 15/24/35/48 on one host and one Verilator+firmware baseline.
# The 20M checkpoint/RAM/commit differential, not throughput, is the gate.
# IMPORTANT: verify_size is also called within "if ... && ..."; Bash disables
# errexit for function bodies in conditional tests. Every correctness command
# therefore needs an explicit "|| return" to prevent false PASS results.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
BASE="${GSIM_PERF_BASE:-build/rv64-pa32-gsim-performance}"
FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
JOBS="${JOBS:-4}"
test -s "$FW_BIN"
mkdir -p "$BASE"
sha256sum "$FW_BIN" > "$BASE/firmware.sha256"
reference="$BASE/verilator"
printf 'size\tgsim_seconds\tverilator_seconds\tspeedup\tstatus\n' > "$BASE/summary.tsv"
build_size() {
  local size="$1"
  echo "AETHERCORE_PA32_GSIM_SWEEP_STAGE size=$size step=BUILD"
  OUT="$BASE/gsim-$size" GSIM_HOST_BRIDGE_EXPERIMENT=1 \
    GSIM_SUPERNODE_MAX_SIZE="$size" GSIM_PREFIX_OPT=-O3 JOBS="$JOBS" \
    bash tools/ci/rv64_pa32_gsim_source_patch.sh
}
verify_size() {
  local size="$1" model="$BASE/gsim-$1" result="$BASE/firmware-$1"
  echo "AETHERCORE_PA32_GSIM_SWEEP_STAGE size=$size step=RV64I_20K"
  python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
    "$model/prefix-bare.log" "$reference/verilator-bare.log" \
    > "$BASE/bare-diff-$size.log" || return $?
  echo "AETHERCORE_PA32_GSIM_SWEEP_STAGE size=$size step=FIRMWARE_20M"
  GSIM_OUT="$model" VERILATOR_OUT="$reference" OUT="$result" \
    FW_BIN="$FW_BIN" CYCLES=20000000 TIMEOUT_SECONDS=900 \
    bash tools/ci/rv64_pa32_gsim_firmware_diff.sh || return $?
  grep -q '^AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS ' "$result/diff-result.txt" || return $?
  python3 - "$size" "$result/performance.txt" "$BASE/summary.tsv" <<'PY' || return $?
import pathlib,re,sys
size,path,dst=sys.argv[1:]
rows=[r for r in pathlib.Path(path).read_text().splitlines()
      if r.startswith("AETHERCORE_PA32_GSIM_FIRMWARE_AB_RESULT ")]
assert len(rows)==1
d=dict(re.findall(r"([\w-]+)=([^\s]+)",rows[0]))
assert d["cycles"]=="20000000"
with open(dst,"a") as f:
    f.write(f"{size}\t{d['gsim_seconds']}\t{d['verilator_seconds']}\t"
            f"{d['gsim_speedup']}\tPASS\n")
print(f"AETHERCORE_PA32_GSIM_SUPERNODE_20M_PASS size={size} "
      f"gsim_seconds={d['gsim_seconds']} speedup={d['gsim_speedup']}")
PY
}
build_size 35
echo "AETHERCORE_PA32_GSIM_SWEEP_STAGE size=35 step=REFERENCE_BUILD"
GSIM_OUT="$BASE/gsim-35" OUT="$reference" JOBS="$JOBS" \
  bash tools/ci/rv64_pa32_gsim_bare_diff.sh
verify_size 35
failures=0
for size in 15 24 48; do
  if build_size "$size" && verify_size "$size"; then
    :
  else
    printf '%s\t\t\t\tFAIL\n' "$size" >> "$BASE/summary.tsv"
    echo "AETHERCORE_PA32_GSIM_SWEEP_CANDIDATE_FAIL size=$size" >&2
    failures=$((failures+1))
  fi
done
best="$(python3 - "$BASE/summary.tsv" <<'PY'
import csv,sys
with open(sys.argv[1]) as f:
    rows=[r for r in csv.DictReader(f,delimiter="\t") if r["status"]=="PASS"]
assert rows
print(min(rows,key=lambda r:float(r["gsim_seconds"]))["size"])
PY
)"
echo "AETHERCORE_PA32_GSIM_SWEEP_BEST size=$best"
GSIM_MODEL_OUT="$BASE/gsim-$best" VERILATOR_OUT="$reference" \
  FW_BIN="$FW_BIN" OUT="$BASE/pgo-$best" \
  bash tools/ci/rv64_pa32_gsim_pgo_ab.sh
cat "$BASE/summary.tsv"
if (( failures != 0 )); then
  echo "AETHERCORE_PA32_GSIM_SWEEP_FAIL candidates=$failures" >&2
  exit 8
fi
echo "AETHERCORE_PA32_GSIM_SWEEP_20M_PASS sizes=15,24,35,48"
