#!/usr/bin/env bash
set -euo pipefail

# Explicit, bounded Product G3 fork-COW precheck: one real OpenSBI cold prefix
# followed by two independent short CPU-active process snapshots/replays.
# Reuses the exact already-qualified G3 binary RTL archive; no RTL generation,
# no PID1/Debian, and no implicit CI run.
#
#   bash tools/ci/product_forkcow_g3_smoke.sh
#   AETHERCORE_FORKCOW_G3_REPLAY_CYCLES=1000000 bash ...

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: real RAM-only G3 cannot carry SD/DTB image' >&2
  exit 2
}
g3_result="${G3_RESULT:-$root/build/rv64-opensbi-first-exec/result.txt}"
evidence="${G3_EVIDENCE:-$root/build/product-g3-short-ab/small-qualification.txt}"
[[ -s "$g3_result" && -s "$evidence" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: require previous qualified G3 build and short A/B evidence' >&2
  exit 3
}
grep -Fxq 'RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS' "$g3_result"
grep -Fxq 'qualification=PRODUCT_G3_SHORT_AB_IDENTICAL_NOT_LINUX_PASS' "$evidence"
[[ ! -e "$(dirname "$evidence")/inprogress" ]] || {
  echo 'PRODUCT_FORKCOW_G3_REJECTED: G3 comparison still in progress' >&2
  exit 3
}
firmware="$(sed -n 's/^firmware_bin=//p' "$g3_result" | head -1)"
[[ -s "$firmware" ]] || { echo 'PRODUCT_FORKCOW_G3_REJECTED: missing G3 firmware' >&2; exit 3; }
expected_fw="$(sed -n 's/^firmware_sha256=//p' "$evidence" | head -1)"
[[ -n "$expected_fw" && "$expected_fw" == "$(sha256sum "$firmware" | awk '{print $1}')" ]] || {
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
echo "PRODUCT_FORKCOW_G3_BEGIN selected=$selected replay-cycles=$replay_cycles wall-timeout=$wall_seconds"
timeout --signal=TERM --kill-after=10s "${wall_seconds}s" \
  "$runner" "$firmware" 30000000 'OpenSBI v1.6' "$replay_cycles" 2 '-' '-' 5000000
echo "PRODUCT_FORKCOW_G3_SHORT_PASS selected=$selected (NOT Linux PID1 or Debian)"
