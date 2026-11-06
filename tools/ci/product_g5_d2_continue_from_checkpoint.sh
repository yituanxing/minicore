#!/usr/bin/env bash
set -euo pipefail

# Continue the *archived, exact-model* G5 Debian D2 state in 5M slices.
# Never performs a cold boot, compile, or broad CI. No existing state is edited.
# Usage: bash tools/ci/product_g5_d2_continue_from_checkpoint.sh ARCHIVE_DIR INPUT_STATE_PREFIX NEW_OUT_DIR START_CYCLES STOP_CYCLES

[[ "$#" == 5 ]] || { echo 'usage: script ARCHIVE_DIR INPUT_STATE_PREFIX NEW_OUT_DIR START_CYCLES STOP_CYCLES' >&2; exit 2; }
ARCHIVE="$(realpath -- "$1")"
START="$(realpath -- "$2.restored.host")"
START_RTL="$(realpath -- "$2.restored.rtl")"
OUT="$3"
START_CYCLES="$4"
STOP_CYCLES="$5"
[[ "$START_CYCLES" =~ ^[0-9]+$ && "$STOP_CYCLES" =~ ^[0-9]+$ ]] && \
  (( STOP_CYCLES > START_CYCLES && STOP_CYCLES - START_CYCLES <= 100000000 && \
     (STOP_CYCLES - START_CYCLES) % 5000000 == 0 )) || {
  echo 'G5_D2_CONTINUE_REJECTED: invalid or oversized guest cycle range' >&2; exit 2;
}
[[ -f "$ARCHIVE/product-g5-checkpoint" && -s "$ARCHIVE/vmlinux" && \
   -s "$ARCHIVE/System.map" && -f "$START" && -f "$START_RTL" && \
   ! -e "$OUT" && ! -L "$OUT" ]] || {
  echo 'G5_D2_CONTINUE_REJECTED: missing input or output already exists' >&2
  exit 2
}

ORIGINAL_MODEL_SHA=aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71
FW_SHA=d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c
REBUILT_MODEL_SHA=5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d
VMLINUX_SHA=6f5afbdc6fabd14819219d4928289c163d73f3aee9b38c72c4c866b1c50c5c69
SYSTEM_MAP_SHA=ebfdf95aad4a0a16c5665f3ceaef461b0153b9f2f47eab3bf45f2b478217eb64

[[ "$(sha256sum "$ARCHIVE/product-g5-checkpoint" | cut -d ' ' -f 1)" == "$REBUILT_MODEL_SHA" && \
   "$(sha256sum "$ARCHIVE/vmlinux" | cut -d ' ' -f 1)" == "$VMLINUX_SHA" && \
   "$(sha256sum "$ARCHIVE/System.map" | cut -d ' ' -f 1)" == "$SYSTEM_MAP_SHA" ]] || {
  echo 'G5_D2_CONTINUE_REJECTED: mismatched archived model or Linux image' >&2
  exit 3
}

PROGRESS="$(cd "$(dirname "$0")" && pwd)/product_g5_initramfs_progress.py"
start_report="$(python3 "$PROGRESS" --vmlinux "$ARCHIVE/vmlinux" \
    --system-map "$ARCHIVE/System.map" "$START")"
initial="$(grep '^G5_INITRAMFS_PROGRESS ' <<< "$start_report" | tail -1)"
[[ " $initial " == *" cycles=$START_CYCLES "* ]] || {
  echo 'G5_D2_CONTINUE_REJECTED: start cycle does not match actual snapshot' >&2; exit 3;
}
prev_commits="$(sed -n 's/.* commits=\([0-9]*\) .*/\1/p' <<< "$initial")"
[[ "$prev_commits" =~ ^[0-9]+$ ]] || {
  echo 'G5_D2_CONTINUE_REJECTED: no initial retired instruction count' >&2; exit 3;
}
mkdir -p -- "$OUT"
prev_host="$START"
prev_rtl="$START_RTL"

for ((cycle = START_CYCLES + 5000000; cycle <= STOP_CYCLES; cycle += 5000000)); do
  prefix="$OUT/c${cycle}"
  ln -s -- "$prev_host" "$prefix.pre.host"
  ln -s -- "$prev_rtl" "$prefix.pre.rtl"
  started="$(date +%s)"
  if ! timeout --signal=TERM --kill-after=10s 240s \
      "$ARCHIVE/product-g5-checkpoint" restore /dev/null "$prefix" \
      "$ORIGINAL_MODEL_SHA" "$FW_SHA" 1800000000 \
      'Linux version 6.6.143' 5000000 > "$prefix.log" 2>&1; then
    tail -30 "$prefix.log" >&2
    echo "G5_D2_CONTINUE_FAILED cycle=$cycle log=$prefix.log" >&2
    exit 4
  fi
  line="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$prefix.log" | tail -1)"
  [[ " $line " == *" cycles=$cycle time=$cycle "* ]] || {
    echo "G5_D2_CONTINUE_FAILED: wrong cycle boundary at $cycle" >&2; exit 5;
  }
  commits="$(sed -n 's/.* commits=\([0-9]*\) .*/\1/p' <<< "$line")"
  [[ "$commits" =~ ^[0-9]+$ ]] && (( commits > prev_commits )) || {
    echo "G5_D2_CONTINUE_FAILED: no retirement at $cycle" >&2; exit 5;
  }
  [[ -s "$prefix.restored.host" && -s "$prefix.restored.rtl" ]] || {
    echo "G5_D2_CONTINUE_FAILED: missing snapshots at $cycle" >&2; exit 5;
  }
  python3 "$PROGRESS" --vmlinux "$ARCHIVE/vmlinux" \
    --system-map "$ARCHIVE/System.map" "$prefix.restored.host" \
    > "$prefix.progress.log"
  progress="$(grep '^G5_INITRAMFS_PROGRESS ' "$prefix.progress.log" | tail -1)"
  [[ -n "$progress" ]] || { echo 'G5_D2_CONTINUE_FAILED: no progress report' >&2; exit 5; }
  elapsed=$(( $(date +%s) - started ))
  echo "G5_D2_CONTINUE_POINT cycles=$cycle commits=$commits elapsed_s=$elapsed"
  echo "$progress"
  sha256sum "$prefix.restored.host" "$prefix.restored.rtl" > "$prefix.sha256"
  prev_host="$(realpath -- "$prefix.restored.host")"
  prev_rtl="$(realpath -- "$prefix.restored.rtl")"
  prev_commits="$commits"
  if [[ "$progress" == *'phase=PID1_MARKER_PRESENT'* ]]; then
    echo "G5_D2_PID1_START_MARKER_REACHED cycle=$cycle"
    break
  fi
done
echo "G5_D2_CONTINUE_STOPPED last_cycles=$cycle last_commits=$commits"
