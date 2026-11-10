#!/usr/bin/env bash
set -euo pipefail

# Explicit, bounded Product G3 fork-COW precheck: one real OpenSBI cold prefix
# followed by two independent short CPU-active process snapshots/replays.
# Reuses the exact already-qualified G3 binary RTL archive; no RTL generation,
# no PID1/Debian, and no implicit CI run.
#
#   bash tools/ci/product_forkcow_g3_smoke.sh
#   AETHERCORE_FORKCOW_G3_REPLAY_CYCLES=1000000 bash ...
# Or reuse the single pinned Product Verilator model prebuilt by the GSIM
# comparison (its build-ticket is NOT a GSIM runtime qualification):
#   G3_MODEL_TICKET=build/product-gsim-g3-ab/model-ready.txt bash ...

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: real RAM-only G3 cannot carry SD/DTB image' >&2
  exit 2
}
g3_result="${G3_RESULT:-$root/build/rv64-opensbi-first-exec/result.txt}"
evidence="${G3_EVIDENCE:-$root/build/product-g3-short-ab/small-qualification.txt}"
[[ -s "$g3_result" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: require previously qualified G3 OpenSBI firmware' >&2
  exit 3
}
grep -Fxq 'RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS' "$g3_result"
firmware="$(sed -n 's/^firmware_bin=//p' "$g3_result" | head -1)"
[[ -s "$firmware" ]] || { echo 'PRODUCT_FORKCOW_G3_REJECTED: missing G3 firmware' >&2; exit 3; }
firmware_sha="$(sha256sum "$firmware" | awk '{print $1}')"
if [[ -n "${G3_MODEL_TICKET:-}" ]]; then
  # A *build*-verified, single-model ticket is enough to exercise actual G3
  # OpenSBI CPU retirement and fork-COW determinism. It is explicitly NOT
  # proof that the separate 30M-cycle GSIM differential has passed. The
  # relinker independently re-verifies the live pinned toolchain/model cache.
  ticket="$(realpath "$G3_MODEL_TICKET")"
  [[ -s "$ticket" ]] &&
    grep -Fxq 'status=PRODUCT_GSIM_G3_MODELS_VERIFIED' "$ticket" &&
    grep -Fxq 'cycles=30000000' "$ticket" &&
    grep -Fxq "firmware_sha256=$firmware_sha" "$ticket" || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: single-model ticket is missing or bound to different firmware' >&2
    exit 3
  }
  model="$(sed -n 's/^verilator_model_path=//p' "$ticket" | head -1)"
  expected_model_id="$(sed -n 's/^verilator_model_id=//p' "$ticket" | head -1)"
  expected_model_sha="$(sed -n 's/^verilator_sha256=//p' "$ticket" | head -1)"
  [[ -n "$model" && -d "$model" && -n "$expected_model_id" &&
     -n "$expected_model_sha" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: incomplete single-model identity' >&2
    exit 3
  }
  model="$(realpath "$model")"
  [[ "$(basename "$model")" == model-verilator-5.054-reference ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: single-model ticket must identify pinned Product 5.054/reference' >&2
    exit 3
  }
  selected=5.054:reference
  version=5.054
  mode=reference
  obj="$model/obj"
  top=AetherCoreProductOpenSbiRV64SimTop
  [[ -x "$obj/V$top" &&
     "$(sha256sum "$obj/V$top" | awk '{print $1}')" == "$expected_model_sha" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: single-model executable does not match its build-ticket hash' >&2
    exit 3
  }
  flags="-std=c++20 -O3 -march=native -I$root/sim/product_rv64_opensbi_shim -I$root/sim"
  actual_model_id="$(AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
    bash tools/ci/product_sim_cache.sh fingerprint "$model" "$top" \
      aethercore.sim.ElaborateProductOpenSbiRV64SimTop "$flags")"
  [[ "$actual_model_id" == "$expected_model_id" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: single-model source/pinned tool identity changed since build' >&2
    exit 3
  }
  evidence_kind=gsim-single-model-build-ticket-not-gsim-runtime-pass
  ticket_sha_before="$(sha256sum "$ticket" | awk '{print $1}')"
else
  # Preserve the original independent three-profile G3 qualification path.
  [[ -s "$evidence" ]] &&
    grep -Fxq 'qualification=PRODUCT_G3_SHORT_AB_IDENTICAL_NOT_LINUX_PASS' "$evidence" || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: require previous G3 short A/B evidence' >&2
    exit 3
  }
  [[ ! -e "$(dirname "$evidence")/inprogress" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: G3 comparison still in progress' >&2
    exit 3
  }
  expected_fw="$(sed -n 's/^firmware_sha256=//p' "$evidence" | head -1)"
  [[ -n "$expected_fw" && "$expected_fw" == "$firmware_sha" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: G3 firmware differs from the qualified image' >&2
    exit 3
  }
  selected="$(sed -n 's/^selected_profile=//p' "$evidence" | head -1)"
  case "$selected" in
    5.024:reference|5.054:reference|5.054:adaptive) ;;
    *) echo "PRODUCT_FORKCOW_G3_REJECTED: invalid selected G3 profile $selected" >&2; exit 3 ;;
  esac
  version="${selected%%:*}"
  mode="${selected#*:}"
  obj="$(dirname "$evidence")/model-verilator-$version-$mode/obj"
  evidence_kind=independently-qualified-g3-three-profile
fi
top=AetherCoreProductOpenSbiRV64SimTop
replay_cycles="${AETHERCORE_FORKCOW_G3_REPLAY_CYCLES:-500000}"
wall_seconds="${AETHERCORE_FORKCOW_G3_WALL_SECONDS:-300}"
[[ "$replay_cycles" =~ ^[0-9]+$ && "$wall_seconds" =~ ^[0-9]+$ ]] &&
  (( replay_cycles >= 1000 && replay_cycles <= 3000000 &&
     wall_seconds >= 30 && wall_seconds <= 600 )) || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: replay_cycles=1000..3000000, wall_seconds=30..600' >&2
  exit 3
}

relink_log="$(TOP="$top" AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
  bash tools/ci/product_forkcow_relink.sh "$obj")"
echo "$relink_log"
runner="$(printf '%s\n' "$relink_log" | sed -n 's/^PRODUCT_FORKCOW_BINARY=//p' | tail -1)"
[[ -x "$runner" ]] || { echo 'PRODUCT_FORKCOW_G3_REJECTED: relink did not return a runner' >&2; exit 4; }
echo "PRODUCT_FORKCOW_G3_BEGIN selected=$selected replay-cycles=$replay_cycles wall-timeout=$wall_seconds evidence=$evidence_kind"
timeout --signal=TERM --kill-after=10s "${wall_seconds}s" \
  "$runner" "$firmware" 30000000 'OpenSBI v1.6' "$replay_cycles" 2 '-' '-' 5000000
[[ "$(sha256sum "$firmware" | awk '{print $1}')" == "$firmware_sha" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: firmware changed during replay' >&2; exit 4;
}
if [[ -n "${G3_MODEL_TICKET:-}" ]]; then
  [[ "$(sha256sum "$ticket" | awk '{print $1}')" == "$ticket_sha_before" &&
     "$(sha256sum "$obj/V$top" | awk '{print $1}')" == "$expected_model_sha" ]] || {
    echo 'PRODUCT_FORKCOW_G3_REJECTED: single-model ticket or simulator changed during replay' >&2; exit 4;
  }
fi
echo "PRODUCT_FORKCOW_G3_SHORT_PASS selected=$selected evidence=$evidence_kind (NOT GSIM A/B, Linux PID1 or Debian)"
