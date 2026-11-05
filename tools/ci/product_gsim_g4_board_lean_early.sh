#!/usr/bin/env bash
set -euo pipefail

# Operator-only Product GSIM *board-lean* 40M-cycle early boot regression.
# This exercises the actual Product RAM RTL + OpenSBI + Linux Image, but not
# the frozen defconfig, Verilator same-cycle A/B, PID1, SD1 or Debian. The
# lean firmware must be separately built and byte-qualified first.
#
#   bash tools/ci/product_gsim_g4_board_lean_early.sh preflight
#   AETHERCORE_ALLOW_PRODUCT_GSIM_BOARD_LEAN=1 \
#     bash tools/ci/product_gsim_g4_board_lean_early.sh run

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"
ACTION="${1:-}"
case "${ACTION}" in preflight|run) ;; *)
  echo 'usage: product_gsim_g4_board_lean_early.sh preflight|run' >&2; exit 2 ;;
esac

fail() { echo "PRODUCT_GSIM_BOARD_LEAN_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
must_have() { grep -Fxq "$1" "$2" || fail "missing or stale $1 in $2"; }
field() {
  local value
  [[ -s "$2" ]] || fail "missing evidence $2"
  value="$(sed -n "s/^${1}=//p" "$2" | head -n 1)"
  [[ -n "${value}" ]] || fail "missing field $1 in $2"
  printf '%s\n' "${value}"
}
metric() {
  local value
  value="$(tr ' ' '\n' <<< "$1" | sed -n "s/^${2}=//p")"
  [[ -n "${value}" ]] || fail "missing GSIM metric ${2}"
  printf '%s\n' "${value}"
}
integer_range() {
  [[ "$1" =~ ^[0-9]+$ ]] || fail "$4 must be decimal"
  (( $1 >= $2 && $1 <= $3 )) || fail "$4 out of $2..$3"
}

[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'SD1/Debian disk or external DTB is forbidden in RAM-only board-lean smoke'
CYCLES="${AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_CYCLES:-40000000}"
WALL="${AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_WALL_SECONDS:-180}"
integer_range "${CYCLES}" 30000000 100000000 AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_CYCLES
integer_range "${WALL}" 30 180 AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_WALL_SECONDS

MODEL_ROOT="${PRODUCT_GSIM_OUT:-${ROOT}/build/product-gsim-bridge}"
GSIM="${MODEL_ROOT}/product-gsim-prefix"
MODEL_ID="${MODEL_ROOT}/build-identity.txt"
LINUX_ROOT="${ROOT}/build/rv64-linux-board-lean"
LINUX_RESULT="${LINUX_ROOT}/result.txt"
FW_ROOT="${ROOT}/build/rv64-board-lean-payload"
FW_RESULT="${FW_ROOT}/result.txt"
FW_SHA_LIST="${FW_ROOT}/evidence/sha256.txt"
RUN_ROOT="${ROOT}/build/product-gsim-g4-board-lean"

[[ -x "${GSIM}" && -s "${MODEL_ID}" ]] || fail 'exact Product GSIM model is missing'
MODEL_SHA="$(sha_file "${GSIM}")"
must_have "model_sha256=${MODEL_SHA}" "${MODEL_ID}"
must_have 'gsim_revision=45a73d6afb9b1893781cfb57ccd54096987bb23d' "${MODEL_ID}"
must_have 'RV64_BOARD_LEAN_LINUX_BUILD_RESULT: status=PASS' "${LINUX_RESULT}"
must_have 'qualification=LEAN_G4_RAM_INITRAMFS_BUILD_ONLY_NOT_RTL_PID1_OR_DEBIAN_PASS' "${LINUX_RESULT}"
must_have 'RV64_BOARD_LEAN_PAYLOAD_BUILD_RESULT: status=PASS' "${FW_RESULT}"
must_have 'spi_sd=0' "${FW_RESULT}"
must_have 'product_board=0' "${FW_RESULT}"
FW_BIN="$(field firmware_bin "${FW_RESULT}")"
[[ -s "${FW_BIN}" ]] || fail 'qualified board-lean OpenSBI firmware bytes missing'
sha256sum --check --status "${FW_SHA_LIST}" || fail 'board-lean Image/DTB/OpenSBI payload hashes no longer match'
LINUX_IMAGE="$(field image "${LINUX_RESULT}")"
[[ -s "${LINUX_IMAGE}" ]] || fail 'board-lean Linux Image bytes missing'
must_have "linux_image_sha256=$(sha_file "${LINUX_IMAGE}")" "${FW_RESULT}"
FW_SHA="$(sha_file "${FW_BIN}")"
LINUX_SHA="$(sha_file "${LINUX_IMAGE}")"

if [[ "${ACTION}" == preflight ]]; then
  echo "PRODUCT_GSIM_BOARD_LEAN_PREFLIGHT_PASS cycles=${CYCLES} wall_cap_seconds=${WALL} model_sha256=${MODEL_SHA} firmware_sha256=${FW_SHA} image_sha256=${LINUX_SHA} no_g3_ab_claim=1 no_pid1=1 no_debian=1"
  exit 0
fi
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_BOARD_LEAN:-0}" == 1 ]] ||
  fail 'AETHERCORE_ALLOW_PRODUCT_GSIM_BOARD_LEAN=1 required for real guest execution'

mkdir -p "${RUN_ROOT}"
touch "${RUN_ROOT}/inprogress"
rm -f "${RUN_ROOT}/qualification.txt"
LOG="${RUN_ROOT}/linux-lean-early.run.log"
start="$(date +%s%N)"
set +e
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "${GSIM}" "${FW_BIN}" "${CYCLES}" '__BOARD_LEAN_FIXED_CYCLE_NO_PID1_CLAIM__' --fast-g4-unqualified \
  > "${LOG}" 2>&1
rc=$?
set -e
end="$(date +%s%N)"
[[ "${rc}" == 0 ]] || { tail -50 "${LOG}" >&2; fail "real Product GSIM stopped rc=${rc}"; }

[[ "$(grep -ac '^L32_OPENSBI_TIMEOUT ' "${LOG}" || true)" == 1 ]] || fail 'expected one real GSIM terminal record'
[[ "$(grep -ac '^AETHERCORE_PRODUCT_GSIM_PREFIX ' "${LOG}" || true)" == 1 ]] || fail 'expected one real GSIM CPU record'
TERMINAL="$(grep -a '^L32_OPENSBI_TIMEOUT ' "${LOG}")"
DETAIL="$(grep -a '^AETHERCORE_PRODUCT_GSIM_PREFIX ' "${LOG}")"
[[ "$(metric "${TERMINAL}" cycles)" == "${CYCLES}" ]] || fail 'did not execute exact bounded cycles'
[[ "$(metric "${DETAIL}" guest_cycles)" == "$((CYCLES - 4))" ]] || fail 'reset accounting diverged'
[[ "$(metric "${DETAIL}" trace_mode)" == fast-g4-unqualified &&
   "$(metric "${DETAIL}" commit_digest)" == OMITTED &&
   "$(metric "${DETAIL}" wire_digest)" == OMITTED ]] || fail 'fast unqualified trace contract was not honored'
[[ "$(metric "${DETAIL}" opensbi_banner)" == 1 ]] || fail 'real OpenSBI banner not observed'
[[ "$(metric "${DETAIL}" host_bridge_calls)" =~ ^[1-9][0-9]*$ &&
   "$(metric "${DETAIL}" host_imem_calls)" =~ ^[1-9][0-9]*$ &&
   "$(metric "${TERMINAL}" commits)" =~ ^[1-9][0-9]*$ ]] || fail 'no real Product CPU/RAM activity'
grep -aFq 'OpenSBI v1.6' "${LOG}" || fail 'real OpenSBI UART missing'
# Historical #41988771291 shows Linux at <=100M, but its fixed-cycle
# milestone_cycle=0 does not prove Linux's first UART byte was before 40M.
# Report observed state truthfully: an OpenSBI-only 40M run is not a Linux
# banner PASS. Once earliest Linux cycle is measured, operators may enable
# the strict Linux milestone gate in a separately selected cycle window.
LINUX_OBSERVED="$(metric "${DETAIL}" linux_banner)"
[[ "${LINUX_OBSERVED}" == 0 || "${LINUX_OBSERVED}" == 1 ]] || fail 'invalid Linux banner flag'
if [[ "${LINUX_OBSERVED}" == 1 ]]; then
  grep -aFq 'Linux version 6.6.143' "${LOG}" || fail 'Linux flag set but real UART banner absent'
  STAGE='LINUX_BANNER'
else
  if grep -aFq 'Linux version 6.6.143' "${LOG}"; then
    fail 'real Linux UART present but GSIM banner flag unset'
  fi
  STAGE='OPENSBI_UART_ONLY'
fi
if [[ "${AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_REQUIRE_LINUX_BANNER:-0}" == 1 &&
      "${LINUX_OBSERVED}" != 1 ]]; then
  fail 'strict Linux banner requested but bounded 40M run reached only OpenSBI'
fi
grep -aFq 'AETHERCORE_PRODUCT_GSIM_FIXED_CYCLE_UNQUALIFIED_REQUIRES_VERILATOR_AB' "${LOG}" ||
  fail 'GSIM binary unexpectedly claimed G4 strict same-cycle or PID1 qualification'
[[ "$(sha_file "${GSIM}")" == "${MODEL_SHA}" &&
   "$(sha_file "${FW_BIN}")" == "${FW_SHA}" &&
   "$(sha_file "${LINUX_IMAGE}")" == "${LINUX_SHA}" ]] ||
  fail 'Product model or board-lean firmware changed during bounded run'

elapsed="$(awk -v a="${start}" -v b="${end}" 'BEGIN{printf "%.6f", (b-a)/1000000000}')"
{
  printf 'qualification=PRODUCT_GSIM_BOARD_LEAN_%s_CYCLES_%s_NOT_PID1_OR_DEBIAN_PASS\n' "${CYCLES}" "${STAGE}"
  echo "cycles=${CYCLES}"
  echo "opensbi_banner_observed=1"
  echo "linux_banner_observed=${LINUX_OBSERVED}"
  echo "wall_seconds=${elapsed}"
  echo 'top=AetherCoreProductOpenSbiRV64SimTop'
  echo 'engine=GSIM'
  echo 'trace_mode=fast-g4-unqualified'
  echo 'same_cycle_evidence=NONE_FOR_BOARD_LEAN'
  echo "host_cps=$(metric "${DETAIL}" host_cps)"
  echo "commits=$(metric "${TERMINAL}" commits)"
  echo "interrupts=$(metric "${DETAIL}" interrupts)"
  echo "model_sha256=${MODEL_SHA}"
  echo "firmware_sha256=${FW_SHA}"
  echo "linux_image_sha256=${LINUX_SHA}"
  echo "log_sha256=$(sha_file "${LOG}")"
} > "${RUN_ROOT}/qualification.txt.$$.tmp"
mv "${RUN_ROOT}/qualification.txt.$$.tmp" "${RUN_ROOT}/qualification.txt"
rm -f "${RUN_ROOT}/inprogress"
echo "PRODUCT_GSIM_BOARD_LEAN_EARLY_OBSERVED stage=${STAGE} cycles=${CYCLES} wall_seconds=${elapsed} no_g4_ab=1 no_pid1=1 no_debian=1"
