#!/usr/bin/env bash
set -euo pipefail

# The only permitted fast-simulator qualification for the *real* Debian Top.
# Compare the same native Debian firmware on independent Verilator 5.024 and
# 5.054 reference models for an identical, bounded number of RTL cycles.
# This never launches PID1 qualification and never accepts a G3/G4 proof.
#
# Usage: product_g5_short_ab.sh preflight|run|compare BASELINE_LOG CANDIDATE_LOG
# The selected 5.054 model is prepared and identity-checked by the caller.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
TOP=AetherCoreProductDebianRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductDebianRV64SimTop
AB_ROOT="${AETHERCORE_G5_AB_ROOT:-$ROOT/build/product-g5-short-ab}"
BASELINE="${AETHERCORE_G5_AB_BASELINE_MODEL:-$AB_ROOT/model-verilator-5.024-reference}"
CANDIDATE="${AETHERCORE_G5_AB_CANDIDATE_MODEL:-$AB_ROOT/model-verilator-5.054-reference}"
FW_BIN="${AETHERCORE_G5_AB_FW_BIN:-$ROOT/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
CYCLES="${AETHERCORE_G5_AB_CYCLES:-30000000}"
WALL_SECONDS="${AETHERCORE_G5_AB_WALL_SECONDS:-600}"
VERILATOR_5024="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.024/bin/verilator"
VERILATOR_5054="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.054/bin/verilator"

fail() { echo "PRODUCT_G5_SHORT_AB_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
model_cache() {
  local version="$1" mode="$2" dir="$3"
  env AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh "$mode" "$dir" "$TOP" "$ELABORATE" "$FLAGS"
}

check_inputs() {
  [[ "${SIM_SETTLE_MODE:-reference}" == reference ]] ||
    fail 'adaptive memory settle requires a separate exact Debian Top A/B'
  [[ "${VERILATOR_THREADS:-1}" == 1 ]] || fail 'only single-thread models are qualified'
  [[ "$CYCLES" =~ ^[0-9]+$ ]] && (( CYCLES >= 1000000 && CYCLES <= 60000000 )) ||
    fail 'bounded A/B cycles must be 1000000..60000000'
  [[ "$WALL_SECONDS" =~ ^[0-9]+$ ]] && (( WALL_SECONDS >= 30 && WALL_SECONDS <= 1200 )) ||
    fail 'bounded A/B wall seconds must be 30..1200'
  [[ -s "$FW_BIN" ]] || fail "missing unchanged native Debian firmware $FW_BIN"
  grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' \
    build/rv64-debian-boot/result.txt || fail 'not an independently built real Debian payload'
  [[ -x "$VERILATOR_5024" && -x "$VERILATOR_5054" ]] ||
    fail 'both pinned Verilator 5.024/5.054 toolchains are required'
  [[ "$("$VERILATOR_5024" --version)" == 'Verilator 5.024'* ]] || fail '5.024 generator mismatch'
  [[ "$("$VERILATOR_5054" --version)" == 'Verilator 5.054'* ]] || fail '5.054 generator mismatch'
  [[ "$BASELINE" != "$CANDIDATE" ]] || fail 'independent models cannot share the same path'
  [[ -x "$CANDIDATE/obj/V$TOP" ]] || fail 'selected 5.054 model is not built'
  model_cache 5.054 verify "$CANDIDATE" >/dev/null || fail 'selected Product G5 model identity changed'
}

compare_logs() {
  local baseline_log="$1" candidate_log="$2" base_state="$3" candidate_state="$4"
  local expected="L32_OPENSBI_TIMEOUT cycles=$CYCLES "
  [[ -f "$baseline_log" && -f "$candidate_log" ]] || fail 'both real runtime traces required'
  grep -F "$expected" "$baseline_log" | tail -1 > "$base_state" || true
  grep -F "$expected" "$candidate_log" | tail -1 > "$candidate_state" || true
  [[ -s "$base_state" && -s "$candidate_state" ]] || fail 'missing exact-cycle Debian RTL timeout state'
  grep -Fq ' ram-fingerprint=0x' "$base_state" || fail 'baseline lacks DDR state fingerprint'
  grep -Fq ' uart-fingerprint=0x' "$base_state" || fail 'baseline lacks UART byte fingerprint'
  grep -Fq ' recent-pcs=' "$base_state" || fail 'baseline lacks retirement PC state'
  cmp -s "$base_state" "$candidate_state" || {
    diff -u "$base_state" "$candidate_state" >&2 || true
    fail '5.024 vs 5.054 Product Debian Top diverged at the same RTL cycle'
  }
}

run_model() {
  local version="$1" dir="$2" log="$3" start end
  [[ -x "$dir/obj/V$TOP" ]] || fail "missing compiled Debian Top $version"
  model_cache "$version" verify "$dir" >/dev/null || fail "$version Product model identity changed"
  start="$(date +%s%N)"
  set +e
  timeout --signal=TERM --kill-after=10s "${WALL_SECONDS}s" \
    "$dir/obj/V$TOP" "$FW_BIN" "$CYCLES" '__PRODUCT_G5_AB_NO_PID1__' \
    0 0 '' '' 0 0 0 0 0 > "$log" 2>&1
  local rc=$?
  set -e
  end="$(date +%s%N)"
  [[ "$rc" == 2 ]] || fail "$version Debian prefix exited $rc (expected exact-cycle boundary rc=2)"
  RUN_SECONDS="$(awk -v a="$start" -v b="$end" 'BEGIN {printf "%.6f", (b-a)/1000000000}')"
  model_cache "$version" verify "$dir" >/dev/null || fail "$version model changed after execution"
}

case "$ACTION" in
  preflight)
    check_inputs
    echo "PRODUCT_G5_SHORT_AB_PREFLIGHT_PASS top=$TOP cycles=$CYCLES no_longrun=1"
    ;;
  compare)
    [[ $# == 3 ]] || fail 'compare needs baseline and candidate runtime logs'
    mkdir -p "$AB_ROOT"
    compare_logs "$2" "$3" "$AB_ROOT/baseline.state" "$AB_ROOT/candidate.state"
    echo "PRODUCT_G5_SHORT_AB_STATE_IDENTICAL cycles=$CYCLES"
    ;;
  run)
    [[ "${AETHERCORE_ALLOW_PRODUCT_G5_SHORT_AB:-0}" == 1 ]] ||
      fail 'explicit AETHERCORE_ALLOW_PRODUCT_G5_SHORT_AB=1 is required'
    check_inputs
    mkdir -p "$AB_ROOT" "$BASELINE"
    firmware_sha="$(sha_file "$FW_BIN")"
    # A missing 5.024 native-model cache is rebuilt on this *same* host to
    # avoid the cross-runner -march=native cache-miss dead end.
    if [[ -x "$BASELINE/obj/V$TOP" ]]; then
      model_cache 5.024 verify "$BASELINE" >/dev/null || fail 'stale 5.024 cached Product model'
    else
      make -f Makefile.l32-linux-boot BUILD_DIR="$BASELINE" \
        TOP="$TOP" ELABORATE_MAIN="$ELABORATE" \
        VERILATOR="$VERILATOR_5024 -LDFLAGS -ldl" JOBS=4 \
        SIM_SETTLE_MODE=reference SIM_CXXFLAGS="$FLAGS" sim
      model_cache 5.024 record "$BASELINE" >/dev/null
    fi
    runner_sha="$(sha_file tools/ci/product_g5_short_ab.sh)"
    run_model 5.024 "$BASELINE" "$AB_ROOT/verilator-5.024-reference.run.log"
    base_seconds="$RUN_SECONDS"
    run_model 5.054 "$CANDIDATE" "$AB_ROOT/verilator-5.054-reference.run.log"
    candidate_seconds="$RUN_SECONDS"
    compare_logs "$AB_ROOT/verilator-5.024-reference.run.log" \
      "$AB_ROOT/verilator-5.054-reference.run.log" \
      "$AB_ROOT/baseline.state" "$AB_ROOT/candidate.state"
    [[ "$(sha_file "$FW_BIN")" == "$firmware_sha" &&
       "$(sha_file tools/ci/product_g5_short_ab.sh)" == "$runner_sha" ]] ||
      fail 'Debian firmware or A/B runner changed during comparison'
    speedup="$(awk -v a="$base_seconds" -v b="$candidate_seconds" \
      'BEGIN {if(a<=0 || b<=0) exit 2; printf "%.3f", a/b}')" || fail 'invalid simulator timings'
    # Equal runtime state is correctness; at least 3% speedup is separately
    # needed before advertising 5.054 as the Debian long-run accelerator.
    awk -v x="$speedup" 'BEGIN {exit !(x >= 1.03)}' ||
      fail "same-state candidate only ${speedup}x; keep 5.024 for expensive Debian"
    {
      printf 'qualification=PRODUCT_G5_DEBIAN_TOP_5054_REFERENCE_SAME_CYCLE_NOT_PID1_PASS\n'
      printf 'top=%s\ncycles=%s\n' "$TOP" "$CYCLES"
      printf 'firmware_sha256=%s\n' "$firmware_sha"
      printf 'state_sha256=%s\n' "$(sha_file "$AB_ROOT/baseline.state")"
      printf 'verilator_5024_seconds=%s\nverilator_5054_seconds=%s\n' "$base_seconds" "$candidate_seconds"
      printf 'speedup=%sx\n' "$speedup"
      printf 'baseline_identity=%s\n' "$(model_cache 5.024 fingerprint "$BASELINE")"
      printf 'candidate_identity=%s\n' "$(model_cache 5.054 fingerprint "$CANDIDATE")"
      printf 'runner_sha256=%s\n' "$runner_sha"
    } > "$AB_ROOT/qualification.txt"
    echo "PRODUCT_G5_DEBIAN_TOP_AB_PASS cycles=$CYCLES speedup=${speedup}x no_pid1=1 no_debian_signoff=1"
    ;;
  *) fail 'usage: product_g5_short_ab.sh preflight|run|compare BASELINE_LOG CANDIDATE_LOG' ;;
esac
