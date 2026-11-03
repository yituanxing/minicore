#!/usr/bin/env bash
# Same-RTL PA32 GSIM/Verilator real OpenSBI/Linux firmware prefix.
# Fail-closed: this is a cold firmware-prefix differential, not a Linux PASS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
FW_BIN="${FW_BIN:-build/rv64-linux-early/opensbi/platform/generic/firmware/fw_payload.bin}"
CYCLES="${CYCLES:-2000000}"
GSIM_OUT="${GSIM_OUT:-build/rv64-pa32-gsim-host-bridge}"
VERILATOR_OUT="${VERILATOR_OUT:-build/rv64-pa32-gsim-bare-diff}"
OUT="${OUT:-build/rv64-pa32-gsim-firmware-diff}"
TOP=AetherCoreV2OpenSbiRV64SimTop

test -s "$FW_BIN" || { echo "missing real firmware: $FW_BIN" >&2; exit 2; }
test -x "$GSIM_OUT/pa32-gsim-prefix" || exit 3
test -x "$VERILATOR_OUT/obj/V$TOP" || exit 3
[[ "$CYCLES" =~ ^[1-9][0-9]*$ ]] || exit 4
mkdir -p "$OUT"
sha256sum "$FW_BIN" > "$OUT/firmware.sha256"

timeout --signal=TERM --kill-after=10s 900s \
  "$GSIM_OUT/pa32-gsim-prefix" "$FW_BIN" "$CYCLES" \
  > "$OUT/gsim-firmware.log" 2>&1 || {
    tail -90 "$OUT/gsim-firmware.log" >&2; exit 5;
  }
timeout --signal=TERM --kill-after=10s 900s \
  "$VERILATOR_OUT/obj/V$TOP" "$FW_BIN" "$CYCLES" \
  > "$OUT/verilator-firmware.log" 2>&1 || {
    tail -90 "$OUT/verilator-firmware.log" >&2; exit 6;
  }

python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
  "$OUT/gsim-firmware.log" "$OUT/verilator-firmware.log" --firmware \
  | tee "$OUT/diff-result.txt"
# Compute throughput only after the complete architectural and host-RAM
# differential has passed. The clocks and host are identical.
python3 - "$OUT/gsim-firmware.log" "$OUT/verilator-firmware.log" <<'PY' | tee "$OUT/performance.txt"
import pathlib
import re
import sys
def metrics(path,engine):
    prefix=f"AETHERCORE_PA32_{engine}_PREFIX "
    lines=[line for line in pathlib.Path(path).read_text().splitlines()
           if line.startswith(prefix)]
    if len(lines)!=1: raise SystemExit(f"{engine}: missing unique prefix")
    found=dict(re.findall(r"([\w-]+)=([^\s]+)", lines[0]))
    if "host_seconds" not in found or "guest_cycles" not in found:
        raise SystemExit(f"{engine}: missing performance metrics")
    return float(found["host_seconds"]),float(found["guest_cycles"])
gs,gs_cycles=metrics(sys.argv[1],"GSIM")
vl,vl_cycles=metrics(sys.argv[2],"VERILATOR")
if gs_cycles!=vl_cycles: raise SystemExit("guest-cycle mismatch")
print(f"AETHERCORE_PA32_GSIM_FIRMWARE_AB_RESULT "
      f"cycles={int(gs_cycles)} gsim_seconds={gs:.6f} "
      f"verilator_seconds={vl:.6f} gsim_cps={gs_cycles/gs:.1f} "
      f"verilator_cps={vl_cycles/vl:.1f} "
      f"gsim_speedup={vl/gs:.5f}x "
      "workload=real-firmware-not-full-linux")
PY
