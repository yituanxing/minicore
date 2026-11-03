#!/usr/bin/env bash
# Final bounded A-B: one host, identical GSIM generated graph and firmware,
# same Verilator oracle, A-B-B-A trials; every trial strict differential.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
GSIM_OUT="${GSIM_OUT:-build/rv64-pa32-gsim-host-bridge}"
REFERENCE="${REFERENCE:-build/rv64-pa32-gsim-firmware-diff/verilator-firmware.log}"
FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
OUT="${OUT:-build/rv64-pa32-gsim-thinlto}"
CYCLES=20000000
mkdir -p "$OUT"
test -x "$GSIM_OUT/pa32-gsim-prefix"
test -s "$REFERENCE"
test -s "$FW_BIN"
test -s "$GSIM_OUT/generated/AetherCoreV2OpenSbiRV64SimTop.h"
command -v clang++-19 >/dev/null
LTO_LINKER="$(command -v ld.lld-19)"
test -x "$LTO_LINKER"
sha256sum "$FW_BIN" > "$OUT/firmware.sha256"
echo "AETHERCORE_PA32_GSIM_LTO_STAGE step=BUILD"
timeout --signal=TERM --kill-after=10s 1200s \
  clang++-19 -std=gnu++20 -O3 -march=native -pthread -lz \
  -DAETHERCORE_GSIM_HOST_BRIDGE -flto=thin -fuse-ld="$LTO_LINKER" \
  -I"$GSIM_OUT/generated" -I"$ROOT/sim" \
  "$GSIM_OUT/generated/"*.cpp "$ROOT/sim/pa32_gsim_prefix_main.cpp" \
  -o "$OUT/pa32-gsim-prefix" > "$OUT/build.log" 2>&1 || {
    tail -90 "$OUT/build.log" >&2; exit 3;
  }
test -x "$OUT/pa32-gsim-prefix"
BASELINE_DIR="$(dirname "$REFERENCE")"
test -s "$BASELINE_DIR/firmware.sha256"
if ! cmp -s "$BASELINE_DIR/firmware.sha256" "$OUT/firmware.sha256"; then
  echo "AETHERCORE_PA32_GSIM_LTO_FAIL firmware_hash_mismatch" >&2
  exit 4
fi
printf 'order\tvariant\thost_seconds\tguest_cycles\tcommits\n' > "$OUT/measurements.tsv"
for trial in baseline-1 thinlto-1 thinlto-2 baseline-2; do
  case "$trial" in
    baseline-*) binary_dir="$GSIM_OUT"; variant=baseline ;;
    thinlto-*) binary_dir="$OUT"; variant=thinlto ;;
  esac
  echo "AETHERCORE_PA32_GSIM_LTO_STAGE trial=$trial step=STRICT_20M"
  AETHERCORE_GSIM_PHASE_PROFILE=0 GSIM_OUT="$binary_dir" \
    SIM_TIMEOUT_SECONDS=900 SIM_LOG="$OUT/$trial.log" \
    bash tools/ci/rv64_pa32_sim_run.sh gsim "$FW_BIN" "$CYCLES" || exit 5
  python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
    "$OUT/$trial.log" "$REFERENCE" --firmware \
    > "$OUT/$trial.diff.txt" || {
      tail -60 "$OUT/$trial.log" >&2; exit 6;
    }
  grep -q '^AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS ' \
    "$OUT/$trial.diff.txt" || exit 7
  python3 - "$OUT/$trial.log" "$OUT/measurements.tsv" "$trial" "$variant" <<'PY'
import pathlib,re,sys
log,tsv,trial,variant=sys.argv[1:]
rows=[line for line in pathlib.Path(log).read_text().splitlines()
      if line.startswith("AETHERCORE_PA32_GSIM_PREFIX ")]
assert len(rows)==1
kv=dict(re.findall(r"([\w-]+)=([^\s]+)",rows[0]))
assert kv["guest_cycles"]=="20000000" and int(kv["retires"])>=100
elapsed=float(kv["host_seconds"])
assert elapsed>0.0
with open(tsv,"a") as f:
    f.write(f"{trial}\t{variant}\t{elapsed:.6f}\t"
            f"{kv['guest_cycles']}\t{kv['retires']}\n")
print(f"AETHERCORE_PA32_GSIM_LTO_TRIAL_PASS trial={trial} "
      f"seconds={elapsed:.6f} cycles={kv['guest_cycles']}")
PY
done
python3 - "$OUT/measurements.tsv" "$OUT/result.txt" <<'PY'
import csv,statistics,sys,pathlib
with open(sys.argv[1],newline="") as f:
    rows=list(csv.DictReader(f,delimiter="\t"))
assert [r["order"] for r in rows]==[
    "baseline-1","thinlto-1","thinlto-2","baseline-2"]
assert all(r["guest_cycles"]=="20000000" for r in rows)
assert len({r["commits"] for r in rows})==1
a=statistics.median(float(r["host_seconds"]) for r in rows
                    if r["variant"]=="baseline")
b=statistics.median(float(r["host_seconds"]) for r in rows
                    if r["variant"]=="thinlto")
speedup=a/b
line=(f"AETHERCORE_PA32_GSIM_LTO_STRICT_20M_PASS "
      f"baseline_median_seconds={a:.6f} "
      f"thinlto_median_seconds={b:.6f} speedup={speedup:.5f}x "
      f"trials=4 cycles=20000000 "
      f"decision={'CANDIDATE' if speedup>=1.05 else 'NO_PROMOTION'}")
pathlib.Path(sys.argv[2]).write_text(line+"\n")
print(line)
PY
cat "$OUT/measurements.tsv"
