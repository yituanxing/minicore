#!/usr/bin/env bash
set -euo pipefail

# Product G4 Linux 6.6.143/initramfs PID1 accelerator qualification, not Debian.
# Reuse the three EXACT Product OpenSBI binaries built by the small G3 A/B:
#   G3 three-profile short test -> G4 same-cycle early Linux comparison
#   -> ONE selected real PID1/STIP/SEIP run. No new Verilator build here.
# All actions are operator-only; ordinary source updates/CI must never run it.
#
#   bash tools/ci/product_sim_pid1_ab.sh preflight
#   AETHERCORE_ALLOW_PRODUCT_PID1_EARLY=1 \
#     bash tools/ci/product_sim_pid1_ab.sh early-compare
#   AETHERCORE_ALLOW_PRODUCT_PID1_LONGRUN=1 \
#     bash tools/ci/product_sim_pid1_ab.sh selected-pid1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/tools/ci/product_model_identity.sh"
ACTION="${1:-}"
case "$ACTION" in preflight|early-compare|selected-pid1) ;; *)
  echo 'usage: product_sim_pid1_ab.sh preflight|early-compare|selected-pid1' >&2
  exit 2
  ;;
esac

TOP=AetherCoreProductOpenSbiRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
AB_ROOT="${AETHERCORE_G3_AB_ROOT:-$ROOT/build/product-g3-short-ab}"
SMALL_EVIDENCE="$AB_ROOT/small-qualification.txt"
EARLY_ROOT="$AB_ROOT/pid1-early"
EARLY_EVIDENCE="$EARLY_ROOT/early-qualification.txt"
FW_RESULT="$ROOT/build/rv64-minimal-init-boot/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-minimal-init-boot/evidence/sha256.txt"
G3_RESULT="${G3_RESULT:-$ROOT/build/rv64-opensbi-first-exec/result.txt}"
EARLY_CYCLES="${AETHERCORE_PID1_EARLY_CYCLES:-200000000}"
PID1_CYCLES="${AETHERCORE_PID1_MAX_CYCLES:-1200000000}"
EARLY_WALL_TIMEOUT="${AETHERCORE_PID1_EARLY_WALL_TIMEOUT_SECONDS:-3600}"
PID1_WALL_TIMEOUT="${AETHERCORE_PID1_WALL_TIMEOUT_SECONDS:-10800}"
SIM_FLAGS="-std=c++20 -O3 -march=native -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"

fail() { echo "PRODUCT_G4_PID1_AB_REJECTED: $*" >&2; exit 3; }
field() {
  local key="$1" file="$2" value
  [[ -s "$file" ]] || fail "missing evidence/manifest $file"
  value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
  [[ -n "$value" ]] || fail "missing $key in $file"
  printf '%s\n' "$value"
}
has_field() { grep -Fxq "$1=$2" "$3" || fail "stale/mismatched $1 in $3"; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }

profile_info() {
  local profile="$1"
  case "$profile" in
    5.024:reference) version=5.024; mode=reference; name=verilator-5.024-reference; suffix=5024_baseline ;;
    5.054:reference) version=5.054; mode=reference; name=verilator-5.054-reference; suffix=5054_reference ;;
    5.054:adaptive) version=5.054; mode=adaptive; name=verilator-5.054-adaptive; suffix=5054_adaptive ;;
    *) fail "unsupported qualified G3 simulator profile $profile" ;;
  esac
  MODEL="$AB_ROOT/model-$name"
  SIM="$MODEL/obj/V$TOP"
}

# Same host/source/tool identity as the G3 short comparison. Verilator's
# generated C++ depends on the pinned runtime and host ISA, all included in
# product_sim_cache.sh fingerprint. Never reuse a binary by directory name.
verify_model() {
  local profile="$1" identity
  profile_info "$profile"
  [[ -x "$SIM" ]] || fail "missing previously compiled G3 Product model $SIM"
  # Verification itself computes the complete source/tool/executable identity.
  # Reuse that checked value for the G3 evidence instead of hashing the RTL
  # tree again and then independently verifying the same model.
  identity="$(product_model_verified_fingerprint "$version" "$mode" \
    "$MODEL" "$TOP" "$ELABORATE" "$SIM_FLAGS")" ||
    fail "Product compiled model $profile was built with different sources/tools"
  has_field "model_identity_$suffix" "$identity" "$SMALL_EVIDENCE"
  has_field "binary_sha_$suffix" "$(sha_file "$SIM")" "$SMALL_EVIDENCE"
  MODEL_ID="$identity"
}

check_inputs() {
  [[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
     -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
    fail 'G4 initramfs/A/B cannot load Debian SD1 media or an external DTB'
  [[ "${VERILATOR_THREADS:-1}" == 1 &&
     "${VERILATOR_MODEL_OPT:--O3}" == -O3 &&
     "${VERILATOR_MAKE_OPT:--MAKEFLAGS OPT_FAST=-O3 OPT_GLOBAL=-O3}" == '-MAKEFLAGS OPT_FAST=-O3 OPT_GLOBAL=-O3' ]] ||
    fail 'Product G3/G4 reuse needs the frozen single-thread -O3 model settings'
  unset VERILATOR_ROOT
  [[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" ]] ||
    fail 'G4 unchanged Linux initramfs/OpenSBI firmware has not been staged'
  grep -Fxq 'RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT" ||
    fail 'firmware manifest does not identify the G4 minimal initramfs payload'
  grep -Fxq 'spi_sd=0' "$FW_RESULT" || fail 'G4 payload must not be the Debian SPI-SD image'
  [[ "$(field linux_image "$FW_RESULT")" == "$ROOT/build/rv64-linux-initramfs/obj/arch/riscv/boot/Image" ]] ||
    fail 'unexpected Linux Image for the historical G4 initramfs qualification'
  FW_BIN="$(field firmware_bin "$FW_RESULT")"
  [[ -s "$FW_BIN" ]] || fail "missing original G4 fw_payload.bin $FW_BIN"
  sha256sum --check --status "$FW_SHA_LIST" || fail 'G4 firmware/DTB/Linux bytes differ from builder evidence'
  FW_SHA="$(sha_file "$FW_BIN")"
  [[ ! -e "$AB_ROOT/inprogress" ]] ||
    fail 'G3 Product short A/B is incomplete or a newer attempt failed'
  [[ -s "$SMALL_EVIDENCE" ]] || fail "missing G3 small-program qualification $SMALL_EVIDENCE"
  has_field qualification PRODUCT_G3_SHORT_AB_IDENTICAL_NOT_LINUX_PASS "$SMALL_EVIDENCE"
  has_field top "$TOP" "$SMALL_EVIDENCE"
  has_field elaborate "$ELABORATE" "$SMALL_EVIDENCE"
  has_field ab_script_sha256 "$(sha_file tools/ci/product_sim_ab.sh)" "$SMALL_EVIDENCE"
  has_field qualification_script_sha256 "$(sha_file tools/ci/product_g3_short_ab.sh)" "$SMALL_EVIDENCE"
  [[ -s "$G3_RESULT" ]] || fail "missing source G3 firmware manifest $G3_RESULT"
  has_field g3_result_sha256 "$(sha_file "$G3_RESULT")" "$SMALL_EVIDENCE"
  g3_firmware="$(field firmware_bin "$G3_RESULT")"
  [[ -s "$g3_firmware" ]] || fail "missing source G3 S-mode UART firmware $g3_firmware"
  has_field firmware_sha256 "$(sha_file "$g3_firmware")" "$SMALL_EVIDENCE"
  [[ -s "$AB_ROOT/verilator-5.024-reference.state" &&
     -s "$AB_ROOT/verilator-5.054-reference.state" &&
     -s "$AB_ROOT/verilator-5.054-adaptive.state" ]] ||
    fail 'missing small-program same-cycle trace state'
  cmp -s "$AB_ROOT/verilator-5.024-reference.state" "$AB_ROOT/verilator-5.054-reference.state" &&
    cmp -s "$AB_ROOT/verilator-5.024-reference.state" "$AB_ROOT/verilator-5.054-adaptive.state" ||
    fail 'small-program three-profile same-cycle states disagree'
  has_field state_sha256 "$(sha_file "$AB_ROOT/verilator-5.024-reference.state")" "$SMALL_EVIDENCE"
  G3_SELECTED="$(field selected_profile "$SMALL_EVIDENCE")"
  profile_info "$G3_SELECTED"
  if [[ "$G3_SELECTED" != 5.024:reference ]]; then
    has_field "eligible_$suffix" 1 "$SMALL_EVIDENCE"
  fi
  verify_model 5.024:reference
  verify_model "$G3_SELECTED"
}

number_in_range() {
  local value="$1" lo="$2" hi="$3" label="$4"
  [[ "$value" =~ ^[0-9]+$ ]] && (( value >= lo && value <= hi )) ||
    fail "$label must be an integer in $lo..$hi"
}

run_rtl() {
  local profile="$1" max_cycles="$2" marker="$3" timeout_seconds="$4" log="$5"
  profile_info "$profile"
  local start end rc
  start="$(date +%s%N)"
  set +e
  timeout --signal=TERM --kill-after=10s "${timeout_seconds}s" \
    "$SIM" "$FW_BIN" "$max_cycles" "$marker" \
    1 1 '' '' 0 10000000 0 1 0 > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  RUN_RC="$rc"
  RUN_SECONDS="$(awk -v a="$start" -v b="$end" 'BEGIN {printf "%.6f", (b-a)/1000000000}')"
  if (( rc == 124 || rc == 137 )); then
    fail "wall-clock timeout $timeout_seconds seconds for $profile; no qualification"
  fi
}

case "$ACTION" in
  preflight)
    check_inputs
    printf 'PRODUCT_G4_PID1_AB_PREFLIGHT_PASS candidate=%s firmware_sha256=%s: reusing exact G3 models, no RTL build\n' \
      "$G3_SELECTED" "$FW_SHA"
    ;;
  early-compare)
    [[ "${AETHERCORE_ALLOW_PRODUCT_PID1_EARLY:-0}" == 1 ]] ||
      fail 'explicit AETHERCORE_ALLOW_PRODUCT_PID1_EARLY=1 is required'
    number_in_range "$EARLY_CYCLES" 50000000 400000000 AETHERCORE_PID1_EARLY_CYCLES
    number_in_range "$EARLY_WALL_TIMEOUT" 60 7200 AETHERCORE_PID1_EARLY_WALL_TIMEOUT_SECONDS
    check_inputs
    mkdir -p "$EARLY_ROOT"
    runner_sha_before="$(sha_file "$ROOT/tools/ci/product_sim_pid1_ab.sh")"
    # Invalidate previous evidence before starting expensive runs; a failed
    # comparison must never leave a stale selected-profile ticket behind.
    : > "$EARLY_ROOT/inprogress"
    local_profiles=(5.024:reference)
    [[ "$G3_SELECTED" == 5.024:reference ]] || local_profiles+=("$G3_SELECTED")
    printf 'profile\tseconds\tcycles_per_second\n' > "$EARLY_ROOT/results.tsv"
    for candidate in "${local_profiles[@]}"; do
      profile_info "$candidate"
      log="$EARLY_ROOT/$name.run.log"
      verify_model "$candidate"
      run_rtl "$candidate" "$EARLY_CYCLES" '__PRODUCT_G4_EARLY_NO_MILESTONE__' "$EARLY_WALL_TIMEOUT" "$log"
      [[ "$RUN_RC" == 2 ]] || fail "early $candidate returned $RUN_RC, expected same-cycle timeout"
      grep -E '^L32_OPENSBI_TIMEOUT .*ram-fingerprint=0x' "$log" > "$EARLY_ROOT/$name.state" ||
        fail "missing G4 early stable state for $candidate"
      grep -Fq 'Linux version 6.6.143' "$log" ||
        fail "G4 early $candidate did not reach the Linux banner; increase bounded early window only if needed"
      cps="$(awk -v n="$EARLY_CYCLES" -v s="$RUN_SECONDS" 'BEGIN {if (s <= 0) exit 2; printf "%.3f", n/s}')"
      printf '%s\t%s\t%s\n' "$candidate" "$RUN_SECONDS" "$cps" >> "$EARLY_ROOT/results.tsv"
      verify_model "$candidate"
      [[ "$(sha_file "$FW_BIN")" == "$FW_SHA" ]] || fail 'G4 firmware changed during short differential run'
    done
    if [[ "$G3_SELECTED" != 5.024:reference ]]; then
      profile_info "$G3_SELECTED"
      cmp -s "$EARLY_ROOT/verilator-5.024-reference.state" "$EARLY_ROOT/$name.state" ||
        fail 'G4 early Linux CPU/RAM/UART states disagree; keep pinned reference'
    fi
    SELECTED="$G3_SELECTED"
    if [[ "$G3_SELECTED" != 5.024:reference ]]; then
      # Require speedup on this real Linux prefix as well as the short G3
      # program. A faster reset sequence alone is not a qualification.
      SELECTED="$(awk -F '\t' -v candidate="$G3_SELECTED" '
        NR==2 {base=$3} NR==3 {if(base>0 && $3/base>=1.03) print candidate; else print "5.024:reference"}
      ' "$EARLY_ROOT/results.tsv")"
      [[ -n "$SELECTED" ]] || fail 'cannot compute G4 early Linux speedup'
    fi
    verify_model 5.024:reference
    verify_model "$G3_SELECTED"
    [[ "$(sha_file "$FW_BIN")" == "$FW_SHA" &&
       "$(sha_file "$ROOT/tools/ci/product_sim_pid1_ab.sh")" == "$runner_sha_before" ]] ||
      fail 'G4 early firmware or qualification runner changed while comparing models'
    verify_model "$SELECTED"
    profile_info "$SELECTED"
    {
      printf 'qualification=PRODUCT_G4_EARLY_LINUX_PROFILE_SELECTED_NOT_PID1_PASS\n'
      printf 'compared_cycles=%s\n' "$EARLY_CYCLES"
      printf 'firmware_sha256=%s\n' "$FW_SHA"
      printf 'g3_evidence_sha256=%s\n' "$(sha_file "$SMALL_EVIDENCE")"
      printf 'runner_sha256=%s\n' "$runner_sha_before"
      printf 'selected_profile=%s\n' "$SELECTED"
      printf 'baseline_state_sha256=%s\n' "$(sha_file "$EARLY_ROOT/verilator-5.024-reference.state")"
      printf 'selected_state_sha256=%s\n' "$(sha_file "$EARLY_ROOT/$name.state")"
      printf 'selected_model_identity=%s\n' "$MODEL_ID"
      printf 'selected_binary_sha256=%s\n' "$(sha_file "$SIM")"
    } > "$EARLY_EVIDENCE.$$.tmp"
    mv -f "$EARLY_EVIDENCE.$$.tmp" "$EARLY_EVIDENCE"
    rm -f "$EARLY_ROOT/inprogress"
    echo "PRODUCT_G4_EARLY_LINUX_AB_PASS selected=$SELECTED compared_cycles=$EARLY_CYCLES (no PID1 signoff)"
    ;;
  selected-pid1)
    [[ "${AETHERCORE_ALLOW_PRODUCT_PID1_LONGRUN:-0}" == 1 ]] ||
      fail 'explicit AETHERCORE_ALLOW_PRODUCT_PID1_LONGRUN=1 is required'
    number_in_range "$PID1_CYCLES" 100000000 1200000000 AETHERCORE_PID1_MAX_CYCLES
    number_in_range "$PID1_WALL_TIMEOUT" 60 14400 AETHERCORE_PID1_WALL_TIMEOUT_SECONDS
    check_inputs
    [[ ! -e "$EARLY_ROOT/inprogress" && -s "$EARLY_EVIDENCE" ]] ||
      fail 'G4 early Linux selection is absent or incomplete'
    has_field qualification PRODUCT_G4_EARLY_LINUX_PROFILE_SELECTED_NOT_PID1_PASS "$EARLY_EVIDENCE"
    has_field firmware_sha256 "$FW_SHA" "$EARLY_EVIDENCE"
    has_field g3_evidence_sha256 "$(sha_file "$SMALL_EVIDENCE")" "$EARLY_EVIDENCE"
    has_field runner_sha256 "$(sha_file "$ROOT/tools/ci/product_sim_pid1_ab.sh")" "$EARLY_EVIDENCE"
    has_field baseline_state_sha256 "$(sha_file "$EARLY_ROOT/verilator-5.024-reference.state")" "$EARLY_EVIDENCE"
    SELECTED="$(field selected_profile "$EARLY_EVIDENCE")"
    verify_model "$SELECTED"
    profile_info "$SELECTED"
    has_field selected_state_sha256 "$(sha_file "$EARLY_ROOT/$name.state")" "$EARLY_EVIDENCE"
    cmp -s "$EARLY_ROOT/verilator-5.024-reference.state" "$EARLY_ROOT/$name.state" ||
      fail 'qualified early Linux states changed after comparison'
    has_field selected_model_identity "$MODEL_ID" "$EARLY_EVIDENCE"
    has_field selected_binary_sha256 "$(sha_file "$SIM")" "$EARLY_EVIDENCE"
    mkdir -p "$EARLY_ROOT"
    log="$EARLY_ROOT/selected-pid1-$name.run.log"
    run_rtl "$SELECTED" "$PID1_CYCLES" 'RV64 USER UART IRQ OK' "$PID1_WALL_TIMEOUT" "$log"
    [[ "$RUN_RC" == 0 ]] || fail "real Linux PID1 $SELECTED did not reach milestone (rc=$RUN_RC); log=$log"
    grep -Fq 'Linux version 6.6.143' "$log" || fail 'real Linux banner missing'
    grep -Eq '^L32_OPENSBI_BANNER cycles=[0-9]+ commits=[1-9][0-9]*' "$log" || fail 'OpenSBI boot marker missing'
    grep -q '^L32_FIRST_INTERRUPT ' "$log" || fail 'real interrupt retirement missing'
    grep -q '^L32_FIRST_SUPERVISOR_TIMER_INTERRUPT ' "$log" || fail 'STIP retirement missing'
    grep -q '^L32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT ' "$log" || fail 'SEIP retirement missing'
    grep -Eq '^L32_RUNTIME_MILESTONE_PASS cycles=[0-9]+ commits=[1-9][0-9]*' "$log" || fail 'real user PID1 UART marker missing'
    [[ "$(sha_file "$FW_BIN")" == "$FW_SHA" ]] || fail 'G4 firmware changed during full PID1 run'
    verify_model "$SELECTED"
    cycles="$(sed -n 's/^L32_RUNTIME_MILESTONE_PASS cycles=\([0-9]*\).*/\1/p' "$log" | tail -n 1)"
    printf 'PRODUCT_G4_SELECTED_PID1_PASS profile=%s cycles=%s wall_seconds=%s fw_sha256=%s (one selected candidate only; not Debian)\n' \
      "$SELECTED" "$cycles" "$RUN_SECONDS" "$FW_SHA"
    ;;
esac
