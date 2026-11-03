#!/usr/bin/env bash
# 2M-cycle same-RTL PA32 GSIM/Verilator FULL OPEN SBI/LINUX firmware prefix.
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

timeout --signal=TERM --kill-after=10s 240s \
  "$GSIM_OUT/pa32-gsim-prefix" "$FW_BIN" "$CYCLES" \
  > "$OUT/gsim-firmware.log" 2>&1 || {
    tail -90 "$OUT/gsim-firmware.log" >&2; exit 5;
  }
timeout --signal=TERM --kill-after=10s 240s \
  "$VERILATOR_OUT/obj/V$TOP" "$FW_BIN" "$CYCLES" \
  > "$OUT/verilator-firmware.log" 2>&1 || {
    tail -90 "$OUT/verilator-firmware.log" >&2; exit 6;
  }

python3 tools/ci/rv64_pa32_gsim_bare_diff.py \
  "$OUT/gsim-firmware.log" "$OUT/verilator-firmware.log" --firmware \
  | tee "$OUT/diff-result.txt"
