#!/usr/bin/env bash
set -euo pipefail

# Real G5 Product GSIM Tiny PID1: exact same-firmware short RTL A/B before
# any long run. Never upgrade the old Debian 30M A/B to PID1 qualification.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in ab|pid1) ;; *) echo 'usage: product_gsim_g5_tiny_pid1.sh ab|pid1' >&2; exit 2 ;; esac
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_G5_TINY_PID1:-0}" == 1 ]] || {
  echo 'G5_TINY_GSIM_REJECTED: explicit one-shot opt-in required' >&2; exit 2;
}
[[ "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${VERILATOR_THREADS:-1}" == 1 ]] || {
  echo 'G5_TINY_GSIM_REJECTED: pinned 5.054/reference single-thread only' >&2; exit 2;
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'G5_TINY_GSIM_REJECTED: SD1 and external DTB excluded' >&2; exit 2;
}

OUT="$ROOT/build/product-g5-tiny-gsim"
TINY="$ROOT/build/product-g5-tiny-control"
FW="$TINY/boot/opensbi/platform/generic/firmware/fw_payload.bin"
RESULT="$TINY/boot/result.txt"
LINUX_RESULT="$TINY/linux/result.txt"
NORMALIZED="$TINY/evidence/tiny-config-normalized.txt"
GSIM_ROOT="$ROOT/build/product-gsim-debian-bridge"
GSIM="$GSIM_ROOT/product-gsim-debian-prefix"
MODEL="$ROOT/build/product-gsim-g5-ab/model-verilator-5.054-reference"
TOP=AetherCoreProductDebianRV64SimTop
VERILATOR="$MODEL/obj/V$TOP"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_GSIM_DEBIAN_AB_TRACE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
AB_TICKET="$OUT/ab-qualified.txt"
fail() { echo "G5_TINY_GSIM_REJECTED: $*" >&2; exit 3; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$2=//p" "$1" | head -1; }
model_cache() {
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference     bash tools/ci/product_sim_cache.sh "$1" "$MODEL" "$TOP"       aethercore.sim.ElaborateProductDebianRV64SimTop "$FLAGS"
}
require_fixed_file() {
  [[ -s "$1" ]] || fail "missing $1"
}
require_fixed_file "$RESULT"
require_fixed_file "$LINUX_RESULT"
require_fixed_file "$FW"
require_fixed_file "$NORMALIZED"
require_fixed_file "$GSIM"
require_fixed_file "$VERILATOR"
require_fixed_file "$GSIM_ROOT/build-identity.txt"
grep -Fxq 'PRODUCT_G5_TINY_PAYLOAD_BUILD_RESULT: status=PASS' "$RESULT" ||
  fail 'Tiny G5 payload unqualified'
grep -Fxq 'PRODUCT_G5_TINY_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" ||
  fail 'Tiny kernel unqualified'
grep -Fxq 'qualification=CONTROL_ONLY_NOT_DEBIAN' "$LINUX_RESULT" ||
  fail 'expected G5 D2 Tiny control only'
grep -Fxq "firmware_bin=$FW" "$RESULT" || fail 'Tiny firmware path changed'
grep -Fxq 'spi_sd=0' "$RESULT" || fail 'Tiny test is RAM-only'
[[ "$(field "$LINUX_RESULT" normalized_config_sha256)" == "$(sha "$NORMALIZED")" ]] ||
  fail 'normalized G5 D2 Kconfig changed'
[[ "$(field "$RESULT" linux_image_sha256)" == "$(field "$LINUX_RESULT" image_sha256)" ]] ||
  fail 'firmware and Tiny Linux Image differ'
sha256sum --check --status "$TINY/boot/evidence/sha256.txt" ||
  fail 'Tiny payload, DTB or Image changed'
grep -Fxq "top=$TOP" "$GSIM_ROOT/build-identity.txt" ||
  fail 'GSIM is not the real G5 Product top'
grep -Fxq 'debian_mode=1' "$GSIM_ROOT/build-identity.txt" ||
  fail 'GSIM is not the RV64GC Debian-mode model'
model_cache verify >/dev/null || fail 'G5 trace reference model cache invalid'
mkdir -p "$OUT/logs"
FW_SHA="$(sha "$FW")"
GSIM_SHA="$(sha "$GSIM")"
VL_SHA="$(sha "$VERILATOR")"
GSIM_ID_SHA="$(sha "$GSIM_ROOT/build-identity.txt")"
verify_unchanged() {
  [[ "$(sha "$FW")" == "$FW_SHA" && "$(sha "$GSIM")" == "$GSIM_SHA" &&
     "$(sha "$VERILATOR")" == "$VL_SHA" &&
     "$(sha "$GSIM_ROOT/build-identity.txt")" == "$GSIM_ID_SHA" ]] ||
    fail 'firmware or executable changed during validation'
  model_cache verify >/dev/null || fail 'G5 reference model changed'
}

if [[ "$ACTION" == ab ]]; then
  rm -f "$AB_TICKET"
  # Exact fixed-cycle arguments from the qualified G5 30M strict comparison.
  rc=0
  timeout --signal=TERM --kill-after=10s 900s "$VERILATOR" "$FW" 30000000     '__PRODUCT_G5_GSIM_AB_NO_PID1__' 0 0 '' '' 0 0 0 0 0     > "$OUT/logs/tiny-verilator-30m.log" 2>&1 || rc=$?
  [[ "$rc" == 2 ]] || {
    tail -60 "$OUT/logs/tiny-verilator-30m.log" >&2
    fail "Verilator short reference returned $rc (expected bounded exit 2)"
  }
  rc=0
  timeout --signal=TERM --kill-after=10s 900s "$GSIM" "$FW" 30000000     '__PRODUCT_G5_GSIM_AB_NO_PID1__'     > "$OUT/logs/tiny-gsim-30m.log" 2>&1 || rc=$?
  [[ "$rc" == 0 ]] || {
    tail -60 "$OUT/logs/tiny-gsim-30m.log" >&2
    fail "GSIM short reference returned $rc"
  }
  python3 tools/ci/product_gsim_g5_compare.py     --verilator "$OUT/logs/tiny-verilator-30m.log"     --gsim "$OUT/logs/tiny-gsim-30m.log"     --cycles 30000000 > "$OUT/compare.txt" || {
      tail -80 "$OUT/compare.txt" >&2
      fail 'same Tiny firmware 30M CPU/FPU/RAM/UART/IRQ diverged'
    }
  grep -Fq 'PRODUCT_GSIM_G5_SAME_CYCLE_PASS' "$OUT/compare.txt" ||
    fail 'strict same-cycle comparator did not pass'
  verify_unchanged
  {
    echo 'PRODUCT_G5_TINY_GSIM_30M_AB_PASS: exact same Tiny firmware and Product G5 top'
    echo "tiny_fw_sha256=$FW_SHA"
    echo "gsim_sha256=$GSIM_SHA"
    echo "verilator_sha256=$VL_SHA"
    echo "gsim_identity_sha256=$GSIM_ID_SHA"
    echo "normalized_config_sha256=$(sha "$NORMALIZED")"
    echo 'cycles=30000000'
    echo 'qualification=SHORT_SAME_CYCLE_ONLY_NOT_PID1'
  } > "$AB_TICKET"
  cat "$OUT/compare.txt"
  cat "$AB_TICKET"
  exit 0
fi

require_fixed_file "$AB_TICKET"
grep -Fxq 'PRODUCT_G5_TINY_GSIM_30M_AB_PASS: exact same Tiny firmware and Product G5 top' "$AB_TICKET" ||
  fail 'Tiny same-firmware strict A/B not qualified'
for pair in "tiny_fw_sha256=$FW_SHA" "gsim_sha256=$GSIM_SHA"     "verilator_sha256=$VL_SHA" "gsim_identity_sha256=$GSIM_ID_SHA"     "normalized_config_sha256=$(sha "$NORMALIZED")" 'cycles=30000000'; do
  grep -Fxq "$pair" "$AB_TICKET" || fail "stale Tiny A/B ticket: $pair"
done

rc=0
timeout --signal=TERM --kill-after=10s 9000s "$GSIM" "$FW" 1200000000   'RV64 USER UART IRQ OK' --linux > "$OUT/logs/tiny-gsim-pid1.log" 2>&1 || rc=$?
[[ "$rc" == 0 ]] || {
  tail -100 "$OUT/logs/tiny-gsim-pid1.log" >&2
  fail "full G5 Tiny GSIM returned $rc"
}
LOG="$OUT/logs/tiny-gsim-pid1.log"
grep -Fxq 'AETHERCORE_PRODUCT_GSIM_LINUX_MILESTONE_PASS' "$LOG" ||
  fail 'GSIM did not qualify the real Linux PID1 milestone'
grep -Fq 'Linux version 6.6.143' "$LOG" || fail 'Linux banner missing'
grep -Fq 'RV64 USER UART IRQ OK' "$LOG" || fail 'Tiny PID1 UART marker missing'
PREFIX="$(grep '^AETHERCORE_PRODUCT_GSIM_PREFIX ' "$LOG" | tail -1)"
[[ "$PREFIX" == *'opensbi_banner=1'* && "$PREFIX" == *'linux_banner=1'* ]] ||
  fail 'OpenSBI/Linux identity missing'
[[ "$PREFIX" =~ (^|[[:space:]])stip=[1-9][0-9]*([[:space:]]|$) &&
   "$PREFIX" =~ (^|[[:space:]])seip=[1-9][0-9]*([[:space:]]|$) &&
   "$PREFIX" =~ (^|[[:space:]])milestone_cycle=[1-9][0-9]*([[:space:]]|$) ]] ||
  fail 'no qualified STIP/SEIP/PID1 event'
verify_unchanged
{
  echo 'PRODUCT_G5_TINY_GSIM_REAL_PID1_PASS: actual G5 RTL GSIM, same Kconfig Tiny control, not Debian userland'
  echo "tiny_fw_sha256=$FW_SHA"
  echo "gsim_sha256=$GSIM_SHA"
  echo "verilator_sha256=$VL_SHA"
  echo "gsim_identity_sha256=$GSIM_ID_SHA"
  echo "$PREFIX"
} | tee "$OUT/pid1-result.txt"
