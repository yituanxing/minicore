#!/usr/bin/env bash
set -euo pipefail

# Independent, bounded Product RV64GC Debian RAM prefix A/B. Never restores
# SD1 checkpoints or changes a running full-Debian Product simulator. Generate
# and compile separately fingerprinted RTL/model directories on one host.
# Usage: product_cache_capacity_short_ab.sh preflight|build|run

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
action="${1:-}"
case "$action" in preflight|build|run) ;; *)
  echo 'usage: product_cache_capacity_short_ab.sh preflight|build|run' >&2; exit 2 ;;
esac

readonly top=AetherCoreProductDebianCacheAbRV64SimTop
readonly emitter=aethercore.sim.ElaborateProductDebianCacheAbRV64SimTop
readonly ab_root="${AETHERCORE_CACHE_AB_ROOT:-$root/build/product-cache-capacity-short-ab}"
readonly fw_bin="${AETHERCORE_CACHE_AB_FW_BIN:-$root/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
readonly cycles="${AETHERCORE_CACHE_AB_CYCLES:-5000000}"
readonly wall_seconds="${AETHERCORE_CACHE_AB_WALL_SECONDS:-600}"
readonly no_milestone=__PRODUCT_CACHE_AB_FIXED_PREFIX__
readonly milestone="${AETHERCORE_CACHE_AB_MILESTONE:-$no_milestone}"
readonly profiles="${AETHERCORE_CACHE_AB_PROFILES:-64 256}"
case "$profiles" in '64 256'|'64 256 512') ;; *)
  echo 'PRODUCT_CACHE_AB_REJECTED: profiles must be "64 256" or "64 256 512"' >&2
  exit 3 ;;
esac
read -r -a selected_profiles <<< "$profiles"
readonly verilator_version=5.054
readonly toolchain_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
readonly verilator="${AETHERCORE_CACHE_AB_VERILATOR:-$toolchain_root/verilator-5.054/bin/verilator}"
readonly cxxflags="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CACHE_AB -I$root/sim/product_rv64_opensbi_shim -I$root/sim"

fail() { echo "PRODUCT_CACHE_AB_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
model_dir() { printf '%s/model-%s' "$ab_root" "$1"; }
model_cache() {
  local mode="$1" dir="$2" profile="$3"
  env AETHERCORE_VERILATOR_VERSION="$verilator_version" SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh "$mode" "$dir" "$top" \
      "$emitter $profile $profile" "$cxxflags"
}

check_inputs() {
  # Makefile.l32-linux-boot marks the tracked Mill launcher executable during
  # model builds. Its *bytes* remain content-hashed; ignore that mode-only
  # transition while still rejecting every actual source-content edit.
  [[ -z "$(git -c core.filemode=false status --porcelain --untracked-files=normal)" ]] ||
    fail 'exact-source A/B requires a clean committed worktree'
  [[ "$cycles" =~ ^[0-9]+$ ]] && (( cycles >= 100000 && cycles <= 30000000 )) ||
    fail 'short prefix cycles must be 100000..30000000'
  [[ "$wall_seconds" =~ ^[0-9]+$ ]] && (( wall_seconds >= 30 && wall_seconds <= 1800 )) ||
    fail 'bounded wall seconds must be 30..1800'
  [[ "${SIM_SETTLE_MODE:-reference}" == reference ]] ||
    fail 'cache A/B requires the same fixed reference settling mode'
  [[ "${VERILATOR_THREADS:-1}" == 1 ]] ||
    fail 'cache A/B requires single-thread models'
  [[ -x "$verilator" ]] || fail "pinned Verilator 5.054 unavailable: $verilator"
  [[ -s "$toolchain_root/verilator-5.054/.aethercore-source-revision" ]] ||
    fail 'missing pinned Verilator source revision marker'
  [[ "$(< "$toolchain_root/verilator-5.054/.aethercore-source-revision")" == bbb60a403932a9f695cb6ff001ceb73de6ad9234 ]] ||
    fail 'Verilator 5.054 source revision differs from qualified toolchain'
  [[ -s "$fw_bin" ]] || fail "missing unchanged native Debian firmware: $fw_bin"
  [[ -f build/rv64-debian-boot/result.txt ]] || fail 'missing real Debian build result'
  grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' build/rv64-debian-boot/result.txt ||
    fail 'firmware is not an independently built Debian RV64GC payload'
  [[ -f sim/product_rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h ]] || fail 'missing dedicated host shim'
}

build_models() {
  local profile dir
  for profile in "${selected_profiles[@]}"; do
    dir="$(model_dir "$profile")"
    if [[ -x "$dir/obj/V$top" ]] && model_cache verify "$dir" "$profile" >/dev/null 2>&1; then
      echo "PRODUCT_CACHE_AB_REUSED entries=$profile model=$dir"
      continue
    fi
    # A stale directory is intentionally not overwritten. Its old model and
    # identity remain available for forensic comparison; choose a new AB_ROOT.
    [[ ! -e "$dir/obj/V$top" ]] || fail "stale cache model: $dir (use a new AETHERCORE_CACHE_AB_ROOT)"
    [[ ! -d "$dir/rtl" ]] || fail "partial old RTL exists: $dir (use a new AETHERCORE_CACHE_AB_ROOT)"
    make -f Makefile.l32-linux-boot \
      BUILD_DIR="$dir" TOP="$top" \
      ELABORATE_MAIN="$emitter $profile $profile" \
      VERILATOR="$verilator -LDFLAGS -ldl" JOBS=4 \
      SIM_SETTLE_MODE=reference SIM_CXXFLAGS="$cxxflags" sim
    model_cache record "$dir" "$profile" >/dev/null
    echo "PRODUCT_CACHE_AB_BUILT entries=$profile model=$dir"
  done
}

run_models() {
  [[ "${AETHERCORE_ALLOW_PRODUCT_CACHE_AB:-0}" == 1 ]] ||
    fail 'set AETHERCORE_ALLOW_PRODUCT_CACHE_AB=1 to run the bounded workload'
  mkdir -p "$ab_root"
  local firmware_sha source_sha runner_sha profile dir log rc expected
  firmware_sha="$(sha_file "$fw_bin")"
  source_sha="$(git rev-parse HEAD)"
  runner_sha="$(sha_file tools/ci/product_cache_capacity_short_ab.sh)"
  for profile in "${selected_profiles[@]}"; do
    dir="$(model_dir "$profile")"
    model_cache verify "$dir" "$profile" >/dev/null || fail "unverified $profile model"
    log="$ab_root/cache-$profile.run.log"
    set +e
    timeout --signal=TERM --kill-after=10s "${wall_seconds}s" \
      "$dir/obj/V$top" "$fw_bin" "$cycles" "$milestone" \
      0 0 '' '' 0 1000000 0 0 0 > "$log" 2>&1
    rc=$?
    set -e
    expected=2
    [[ "$milestone" == "$no_milestone" ]] || expected=0
    [[ "$rc" == "$expected" ]] || fail "entries=$profile exited $rc, expected $expected; inspect $log"
    if [[ "$expected" == 2 ]]; then
      grep -Fq "L32_OPENSBI_TIMEOUT cycles=$cycles " "$log" ||
        fail "entries=$profile did not reach exact cycle boundary"
      grep -Fq 'PRODUCT_CACHE_AB_SNAPSHOT phase=timeout ' "$log" ||
        fail "entries=$profile lacks I/D-cache timeout counters"
    else
      grep -Fq 'L32_RUNTIME_MILESTONE_PASS ' "$log" ||
        fail "entries=$profile lacks identical requested UART milestone"
      grep -Fq 'PRODUCT_CACHE_AB_SNAPSHOT phase=milestone ' "$log" ||
        fail "entries=$profile lacks I/D-cache milestone counters"
    fi
    model_cache verify "$dir" "$profile" >/dev/null || fail "model mutated during $profile run"
    [[ "$(sha_file "$fw_bin")" == "$firmware_sha" ]] || fail 'firmware changed during A/B'
    [[ "$(sha_file tools/ci/product_cache_capacity_short_ab.sh)" == "$runner_sha" ]] ||
      fail 'cache A/B runner changed during measurement'
    echo "PRODUCT_CACHE_AB_EVIDENCE entries=$profile $(grep -F 'PRODUCT_CACHE_AB_SNAPSHOT phase=' "$log" | tail -1)"
  done
  {
    printf 'source_sha=%s\nfirmware_sha256=%s\nrunner_sha256=%s\n' \
      "$source_sha" "$firmware_sha" "$runner_sha"
    printf 'cycles_limit=%s\nmarker=%s\n' "$cycles" "$milestone"
    printf 'profiles=%s\n' "$profiles"
    for profile in "${selected_profiles[@]}"; do
      printf 'profile=%s model_identity=%s\n' "$profile" \
        "$(model_cache fingerprint "$(model_dir "$profile")" "$profile")"
      grep -F 'PRODUCT_CACHE_AB_SNAPSHOT phase=' "$ab_root/cache-$profile.run.log" | tail -1
    done
    printf 'qualification=SHORT_PREFIX_ONLY_NO_DEBIAN_LOGIN_NO_FMAX\n'
  } > "$ab_root/result.txt"
  echo "PRODUCT_CACHE_AB_SHORT_PREFIX_RECORDED evidence=$ab_root/result.txt no_debian_signoff=1"
}

case "$action" in
  preflight) check_inputs; echo 'PRODUCT_CACHE_AB_PREFLIGHT_PASS';;
  build) check_inputs; build_models;;
  run) check_inputs; run_models;;
esac
