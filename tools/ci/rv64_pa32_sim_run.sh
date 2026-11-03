#!/usr/bin/env bash
# Single-engine daily entry. Differential signoff remains in the caller.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
if (( $# < 3 || $# > 4 )); then
  echo "usage: $0 gsim|verilator FIRMWARE|--bare|--memory CYCLES [--linux]" >&2
  exit 2
fi
engine="$1"; firmware="$2"; cycles="$3"; mode="${4:-}"
[[ "$engine" == gsim || "$engine" == verilator ]] || exit 2
[[ "$cycles" =~ ^[1-9][0-9]*$ ]] || exit 2
[[ -z "$mode" || "$mode" == "--linux" ]] || exit 2
[[ -z "$mode" || "$firmware" != --* ]] || exit 2
if [[ "$firmware" != "--bare" && "$firmware" != "--memory" ]]; then
  test -s "$firmware" || { echo "missing firmware: $firmware" >&2; exit 2; }
fi
GSIM_OUT="${GSIM_OUT:-build/rv64-pa32-gsim-host-bridge}"
VERILATOR_OUT="${VERILATOR_OUT:-build/rv64-pa32-gsim-bare-diff}"
SIM_TIMEOUT_SECONDS="${SIM_TIMEOUT_SECONDS:-900}"
SIM_LOG="${SIM_LOG:-build/rv64-pa32-sim-run/$engine.log}"
[[ "$SIM_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || exit 2
TOP=AetherCoreV2OpenSbiRV64SimTop
if [[ "$engine" == gsim ]]; then
  exe="$GSIM_OUT/pa32-gsim-prefix"
  command=("$exe" "$firmware" "$cycles")
else
  exe="$VERILATOR_OUT/obj/V$TOP"
  if [[ "$firmware" == "--bare" ]]; then
    command=("$exe" "$cycles")
  else
    command=("$exe" "$firmware" "$cycles")
  fi
fi
[[ -z "$mode" ]] || command+=("$mode")
test -x "$exe" || { echo "missing simulator: $exe" >&2; exit 3; }
mkdir -p "$(dirname "$SIM_LOG")"
printf 'AETHERCORE_PA32_SIM_RUN engine=%s status=START target_cycles=%s timeout_seconds=%s log=%s\n' \
  "$engine" "$cycles" "$SIM_TIMEOUT_SECONDS" "$SIM_LOG"
# Keep every digest/checkpoint on disk, flush only lightweight progress to CI.
set +e
timeout --signal=TERM --kill-after=10s "${SIM_TIMEOUT_SECONDS}s" \
  "${command[@]}" 2>&1 | tee "$SIM_LOG" |
  awk '/^AETHERCORE_PA32_SIM_(STAGE|PROGRESS|MILESTONE) / {print; fflush()}'
statuses=("${PIPESTATUS[@]}")
set -e
if (( statuses[0] != 0 || statuses[1] != 0 || statuses[2] != 0 )); then
  printf 'AETHERCORE_PA32_SIM_RUN engine=%s status=FAIL exit=%d tee_exit=%d filter_exit=%d\n' \
    "$engine" "${statuses[0]}" "${statuses[1]}" "${statuses[2]}" >&2
  tail -60 "$SIM_LOG" >&2
  if (( statuses[0] != 0 )); then exit "${statuses[0]}"; fi
  if (( statuses[1] != 0 )); then exit "${statuses[1]}"; fi
  exit "${statuses[2]}"
fi
printf 'AETHERCORE_PA32_SIM_RUN engine=%s status=END log=%s\n' "$engine" "$SIM_LOG"
