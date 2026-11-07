#!/usr/bin/env bash
set -euo pipefail

# Explicit host-overhead experiment: the same Product GSIM native executable,
# same frozen G3 OpenSBI firmware, same 30M cycles, strict then fast. Neither
# mode re-elaborates Product RTL, rebuilds GSIM, or boots Debian/Linux PID1.
# This proves only retained final CPU/RAM/UART/IRQ functional state is equal.
# It does NOT prove a G4 Verilator same-cycle trace or permit fast G3 promotion.
#
# AETHERCORE_ALLOW_PRODUCT_GSIM_FAST_AB=1 bash tools/ci/product_gsim_fast_ab.sh run

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in preflight|run) ;; *)
  echo 'usage: product_gsim_fast_ab.sh preflight|run' >&2; exit 2 ;;
esac

fail() { echo "PRODUCT_GSIM_FAST_AB_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
check_integer() {
  local raw="$1" lower="$2" upper="$3" name="$4"
  [[ "$raw" =~ ^[0-9]+$ ]] || fail "$name must be decimal integer"
  (( raw >= lower && raw <= upper )) || fail "$name out of range $lower..$upper"
}
has_field() { grep -Fxq "$1=$2" "$3" || fail "stale $1 in $3"; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'SD1/Debian media forbidden in bounded G3 host-overhead benchmark'
CYCLES=30000000
WALL_SECONDS="${AETHERCORE_PRODUCT_GSIM_FAST_WALL_SECONDS:-300}"
check_integer "$WALL_SECONDS" 30 300 AETHERCORE_PRODUCT_GSIM_FAST_WALL_SECONDS
G3_EVIDENCE="$ROOT/build/product-gsim-g3-ab/qualification.txt"
FW_RESULT="$ROOT/build/rv64-opensbi-first-exec/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-opensbi-first-exec/evidence/sha256.txt"
GSIM_ROOT="${PRODUCT_GSIM_OUT:-$ROOT/build/product-gsim-bridge}"
GSIM="$GSIM_ROOT/product-gsim-prefix"
OUT="${PRODUCT_GSIM_FAST_OUT:-$ROOT/build/product-gsim-fast-ab}"

[[ -s "$G3_EVIDENCE" && ! -e "$ROOT/build/product-gsim-g3-ab/inprogress" ]] ||
  fail 'strict G3 qualification absent or still running'
has_field qualification PRODUCT_GSIM_G3_IDENTICAL_NOT_LINUX_PASS "$G3_EVIDENCE"
has_field top AetherCoreProductOpenSbiRV64SimTop "$G3_EVIDENCE"
has_field cycles "$CYCLES" "$G3_EVIDENCE"
[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" ]] || fail 'frozen real G3 firmware manifest absent'
has_field opensbi_version 1.6 "$FW_RESULT"
has_field milestone 'RV64 OpenSBI S-mode payload PASS' "$FW_RESULT"
sha256sum --check --status "$FW_SHA_LIST" || fail 'real G3 firmware checksums changed'
FIRMWARE="$(field firmware_bin "$FW_RESULT")"
[[ -n "$FIRMWARE" && -s "$FIRMWARE" ]] || fail 'real G3 firmware binary missing'
FW_SHA="$(sha_file "$FIRMWARE")"
has_field firmware_sha256 "$FW_SHA" "$G3_EVIDENCE"
[[ -x "$GSIM" && -s "$GSIM_ROOT/build-identity.txt" ]] || fail 'cached GSIM Product native model missing'
GSIM_SHA="$(sha_file "$GSIM")"
ID_SHA="$(sha_file "$GSIM_ROOT/build-identity.txt")"
has_field gsim_sha256 "$GSIM_SHA" "$G3_EVIDENCE"
has_field gsim_identity_sha256 "$ID_SHA" "$G3_EVIDENCE"
has_field model_sha256 "$GSIM_SHA" "$GSIM_ROOT/build-identity.txt"
G3_SHA="$(sha_file "$G3_EVIDENCE")"

if [[ "$ACTION" == preflight ]]; then
  echo "PRODUCT_GSIM_FAST_AB_PREFLIGHT_PASS cycles=$CYCLES same_model=$GSIM_SHA no_debian=1 no_pid1=1"
  exit 0
fi
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_FAST_AB:-0}" == 1 ]] ||
  fail 'operator must set AETHERCORE_ALLOW_PRODUCT_GSIM_FAST_AB=1'

mkdir -p "$OUT/logs"
touch "$OUT/inprogress"
rm -f "$OUT/qualification.txt"
run_one() {
  local label="$1" log="$2" start end rc
  shift 2
  start="$(date +%s%N)"
  set +e
  timeout --signal=TERM --kill-after=5s "${WALL_SECONDS}s" \
    "$GSIM" "$FIRMWARE" "$CYCLES" '__PRODUCT_SIM_AB_NEVER_MATCH__' "$@" > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  if (( rc != 0 )); then
    tail -50 "$log" >&2
    fail "$label G3 real Product run stopped rc=$rc"
  fi
  awk -v a="$start" -v b="$end" 'BEGIN{printf "%.6f",(b-a)/1000000000}'
}

STRICT_SECONDS="$(run_one strict "$OUT/logs/strict.run.log")"
FAST_SECONDS="$(run_one fast "$OUT/logs/fast.run.log" --fast-g4-unqualified)"
python3 tools/ci/product_gsim_fast_ab.py \
  --strict "$OUT/logs/strict.run.log" --fast "$OUT/logs/fast.run.log" \
  --cycles "$CYCLES" > "$OUT/compare.txt" || {
    cat "$OUT/compare.txt" >&2
    fail 'strict/fast final CPU/RAM/UART/IRQ state mismatch'
  }
[[ "$(sha_file "$GSIM")" == "$GSIM_SHA" &&
   "$(sha_file "$GSIM_ROOT/build-identity.txt")" == "$ID_SHA" &&
   "$(sha_file "$FIRMWARE")" == "$FW_SHA" &&
   "$(sha_file "$G3_EVIDENCE")" == "$G3_SHA" ]] ||
  fail 'compiled Product model, firmware, or G3 strict qualification changed while measuring'

SPEEDUP="$(awk -v strict="$STRICT_SECONDS" -v fast="$FAST_SECONDS" \
  'BEGIN{if(strict<=0||fast<=0) exit 2; printf "%.3f",strict/fast}')"
awk -v cycles="$CYCLES" -v strict="$STRICT_SECONDS" -v fast="$FAST_SECONDS" \
  'BEGIN{if(strict<=0||fast<=0) exit 2;
         printf "mode\tseconds\tcycles_per_second\nstrict\t%.6f\t%.1f\nfast-unqualified\t%.6f\t%.1f\n", strict,cycles/strict,fast,cycles/fast}' \
  > "$OUT/results.tsv"
{
  printf 'qualification=PRODUCT_GSIM_FAST_AB_FUNCTIONAL_STATE_MATCH_NOT_G4_VERILATOR_AB_NOT_PID1\n'
  printf 'cycles=%s\nstrict_seconds=%s\nfast_seconds=%s\nspeedup=%s\n' \
    "$CYCLES" "$STRICT_SECONDS" "$FAST_SECONDS" "$SPEEDUP"
  printf 'gsim_sha256=%s\nfirmware_sha256=%s\ng3_qualification_sha256=%s\n' \
    "$GSIM_SHA" "$FW_SHA" "$G3_SHA"
  printf 'strict_trace_mode=strict-same-cycle\nfast_trace_mode=fast-g4-unqualified\n'
  printf 'retained_state_equal=1\nfull_trace_equal=NOT_TESTED\n'
  printf 'strict_log_sha256=%s\nfast_log_sha256=%s\n' \
    "$(sha_file "$OUT/logs/strict.run.log")" "$(sha_file "$OUT/logs/fast.run.log")"
} > "$OUT/qualification.txt.$$.tmp"
mv -f "$OUT/qualification.txt.$$.tmp" "$OUT/qualification.txt"
rm -f "$OUT/inprogress"
cat "$OUT/compare.txt"
echo "PRODUCT_GSIM_FAST_AB_DONE cycles=$CYCLES host_speedup=${SPEEDUP}x (same Product model; not G4 Verilator A/B or PID1)"
