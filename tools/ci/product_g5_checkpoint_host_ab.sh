#!/usr/bin/env bash
set -euo pipefail

# Exact-model G5 archived-checkpoint host A/B differential. This script NEVER
# builds RTL, changes the live runner, or promotes the candidate binary.
# Both executables restore the exact same host/RTL files in new directories.
#
# Usage: product_g5_checkpoint_host_ab.sh \
#   ARCHIVE_DIR CANDIDATE_BIN INPUT_PREFIX FRESH_OUT_DIR \
#   EXPECTED_CANDIDATE_SHA256 EXPECTED_INPUT_HOST_SHA256 \
#   EXPECTED_INPUT_RTL_SHA256 START_CYCLES [WINDOW_CYCLES=500000]
#
# A/A smoke requires AETHERCORE_ALLOW_G5_HOST_AA_SMOKE=1 and reports A/A only.

reject() { echo "G5_HOST_AB_REJECTED reason=$*" >&2; exit 3; }
[[ $# == 8 || $# == 9 ]] || {
  echo 'usage: script ARCHIVE_DIR CANDIDATE_BIN INPUT_PREFIX FRESH_OUT_DIR CANDIDATE_SHA INPUT_HOST_SHA INPUT_RTL_SHA START_CYCLES [WINDOW_CYCLES=500000]' >&2
  exit 2
}

ARCHIVE="$(realpath -e -- "$1")"
CANDIDATE="$(realpath -e -- "$2")"
INPUT_HOST="$(realpath -e -- "$3.restored.host")"
INPUT_RTL="$(realpath -e -- "$3.restored.rtl")"
OUT="$4"
EXPECTED_CAND_SHA="$5"
EXPECTED_HOST_SHA="$6"
EXPECTED_RTL_SHA="$7"
START_CYCLES="$8"
WINDOW_CYCLES="${9:-500000}"
BASELINE="$ARCHIVE/product-g5-checkpoint"
IDENTITY="$ARCHIVE/bridge-identity.txt"
VMLINUX="$ARCHIVE/vmlinux"
SYSTEM_MAP="$ARCHIVE/System.map"

[[ -d "$ARCHIVE" && -f "$IDENTITY" && -f "$VMLINUX" && -f "$SYSTEM_MAP" &&
   -x "$BASELINE" && -x "$CANDIDATE" && -s "$INPUT_HOST" && -s "$INPUT_RTL" ]] ||
  reject 'missing exact archive, executables or checkpoint inputs'
[[ ! -e "$OUT" && ! -L "$OUT" && "$OUT" != '.' && "$OUT" != '/' ]] ||
  reject 'output must be a fresh, specific directory'
[[ "$START_CYCLES" =~ ^[0-9]+$ && "$WINDOW_CYCLES" =~ ^[0-9]+$ ]] ||
  reject 'cycle values must be decimal'
(( START_CYCLES >= 900000000 && START_CYCLES < 1800000000 &&
   WINDOW_CYCLES >= 1 && WINDOW_CYCLES <= 1000000 &&
   START_CYCLES + WINDOW_CYCLES <= 1800000000 )) ||
  reject 'outside strict archived G5 cycle window (max 1M continuation)'

hexsha() { [[ "$1" =~ ^[0-9a-f]{64}$ ]]; }
for value in "$EXPECTED_CAND_SHA" "$EXPECTED_HOST_SHA" "$EXPECTED_RTL_SHA"; do
  hexsha "$value" || reject 'expected SHA256 must be 64 lowercase hex digits'
done

bridge_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$IDENTITY"
}
[[ "$(bridge_value source_commit)" == '99a5c86e99f52ed12f95b304635d77613c9505c4' &&
   "$(bridge_value golden_rtl_host_identical)" == 1 ]] ||
  reject 'source model or golden identity drifted'
ORIGINAL_SHA="$(bridge_value archived_model_sha256)"
BASELINE_SHA="$(bridge_value rebuilt_model_sha256)"
FW_SHA="$(bridge_value firmware_sha256)"
for value in "$ORIGINAL_SHA" "$BASELINE_SHA" "$FW_SHA"; do
  hexsha "$value" || reject 'bad archive identity SHA'
done
[[ "$(sha256sum "$BASELINE" | cut -d' ' -f1)" == "$BASELINE_SHA" &&
   "$(sha256sum "$CANDIDATE" | cut -d' ' -f1)" == "$EXPECTED_CAND_SHA" &&
   "$(sha256sum "$INPUT_HOST" | cut -d' ' -f1)" == "$EXPECTED_HOST_SHA" &&
   "$(sha256sum "$INPUT_RTL" | cut -d' ' -f1)" == "$EXPECTED_RTL_SHA" ]] ||
  reject 'candidate, reference, or initial state SHA mismatch'

AA=0
if [[ "$BASELINE_SHA" == "$EXPECTED_CAND_SHA" ]]; then
  [[ "${AETHERCORE_ALLOW_G5_HOST_AA_SMOKE:-0}" == 1 ]] ||
    reject 'same executable is only valid for explicit A/A smoke testing'
  AA=1
fi

PROGRESS="$(cd "$(dirname "$0")" && pwd)/product_g5_initramfs_progress.py"
report="$(python3 "$PROGRESS" --vmlinux "$VMLINUX" --system-map "$SYSTEM_MAP" "$INPUT_HOST")" ||
  reject 'input checkpoint cannot be decoded with archived Linux image'
line="$(sed -n '/^G5_INITRAMFS_PROGRESS /p' <<< "$report" | tail -1)"
[[ " $line " == *" cycles=$START_CYCLES "* && "$line" == *" commits="* &&
   "$line" != *'phase=PID1_MARKER_PRESENT'* ]] ||
  reject 'input state cycle/phase does not match declared start'

mkdir -p -- "$OUT"
OUT="$(realpath -- "$OUT")"
cat_info="$(printf 'source_commit=%s\nsource_model_sha256=%s\nreference_binary_sha256=%s\ncandidate_binary_sha256=%s\ninitial_host_sha256=%s\ninitial_rtl_sha256=%s\nstart_cycles=%s\nwindow_cycles=%s\n' \
  "$(bridge_value source_commit)" "$ORIGINAL_SHA" "$BASELINE_SHA" "$EXPECTED_CAND_SHA" \
  "$EXPECTED_HOST_SHA" "$EXPECTED_RTL_SHA" "$START_CYCLES" "$WINDOW_CYCLES")"
printf '%s\n' "$cat_info" > "$OUT/identity.txt"

run_one() {
  local kind="$1" bin="$2"
  local prefix="$OUT/$kind"
  ln -s -- "$INPUT_HOST" "$prefix.pre.host"
  ln -s -- "$INPUT_RTL" "$prefix.pre.rtl"
  local start_ns end_ns
  start_ns="$(date +%s%N)"
  if ! timeout --signal=TERM --kill-after=5s 90s \
    "$bin" restore /dev/null "$prefix" "$ORIGINAL_SHA" "$FW_SHA" \
    1800000000 'Linux version 6.6.143' "$WINDOW_CYCLES" \
    > "$prefix.log" 2>&1; then
    tail -20 "$prefix.log" >&2
    reject "$kind reference/candidate execution failed; see $prefix.log"
  fi
  end_ns="$(date +%s%N)"
  [[ -s "$prefix.restored.host" && -s "$prefix.restored.rtl" ]] ||
    reject "$kind did not save both complete host and RTL states"
  local summary
  summary="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$prefix.log" | tail -1)"
  [[ " $summary " == *" cycles=$((START_CYCLES + WINDOW_CYCLES)) "* ]] ||
    reject "$kind did not reach the exact target cycle"
  printf '%s\n' "$summary" > "$prefix.summary"
  printf '%s\n' "$((end_ns - start_ns))" > "$prefix.wall_ns"
  sha256sum "$prefix.restored.host" "$prefix.restored.rtl" > "$prefix.sha256"
}

run_one reference "$BASELINE"
run_one candidate "$CANDIDATE"

if ! cmp -s "$OUT/reference.summary" "$OUT/candidate.summary" ||
   ! cmp -s "$OUT/reference.restored.host" "$OUT/candidate.restored.host" ||
   ! cmp -s "$OUT/reference.restored.rtl" "$OUT/candidate.restored.rtl"; then
  echo 'G5_HOST_AB_DIFFERENTIAL_FAIL reference and candidate state or commit/UART/trace differs' >&2
  exit 5
fi

ref_ms="$(( $(< "$OUT/reference.wall_ns") / 1000000 ))"
cand_ms="$(( $(< "$OUT/candidate.wall_ns") / 1000000 ))"
[[ "$cand_ms" -gt 0 ]] || reject 'candidate elapsed time was zero'
ratio="$(awk -v ref="$ref_ms" -v cand="$cand_ms" 'BEGIN {printf "%.3f", ref / cand}')"
if (( AA )); then
  kind='A_A_SMOKE_ONLY_NOT_ADAPTIVE_QUALIFICATION'
else
  kind='A_B_STATE_EQUAL_SHORT_WINDOW_NOT_LONG_LINUX_QUALIFICATION'
fi
printf 'qualification=%s\nstart_cycles=%s\nwindow_cycles=%s\nreference_wall_ms=%s\ncandidate_wall_ms=%s\nobserved_reference_over_candidate_wall_ratio=%s\n' \
  "$kind" "$START_CYCLES" "$WINDOW_CYCLES" "$ref_ms" "$cand_ms" "$ratio" > "$OUT/result.txt"
printf 'G5_HOST_AB_PASS qualification=%s window=%s reference_ms=%s candidate_ms=%s speed_ratio=%s\n' \
  "$kind" "$WINDOW_CYCLES" "$ref_ms" "$cand_ms" "$ratio"
