#!/usr/bin/env bash
set -euo pipefail

# No guest cycles; real model/firmware/CPU checks execute only on an opt-in CI
# runner that has restored a complete qualified Linux object cache.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="${ROOT}/tools/ci/product_gsim_g4_board_lean_early.sh"
BUILDER="${ROOT}/tools/ci/rv64_board_lean_linux_build.sh"
WARM_GUARD="${ROOT}/tools/ci/rv64_board_lean_linux_warm_guard.sh"
WORKFLOW="${ROOT}/.github/workflows/product-gsim-g4-board-lean-short.yml"
bash -n "${RUNNER}" "${BUILDER}" "${WARM_GUARD}"
bash "${ROOT}/tools/ci/rv64_board_lean_linux_selftest.sh"

reject() {
  local expected="$1" output
  shift
  if output="$("$@" 2>&1)"; then
    echo "PRODUCT_GSIM_BOARD_LEAN_SELFTEST_FAIL accepted_unsafe=${expected}" >&2
    exit 1
  fi
  grep -Fq "${expected}" <<< "${output}" || {
    echo "PRODUCT_GSIM_BOARD_LEAN_SELFTEST_FAIL missing_rejection=${expected}" >&2
    printf '%s\n' "${output}" >&2
    exit 1
  }
}

reject 'usage:' bash "${RUNNER}" invalid
reject 'SD1/Debian disk' env AETHERCORE_PRODUCT_SD_IMAGE=/dev/null bash "${RUNNER}" preflight
reject 'BOARD_LEAN_CYCLES out of' env AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_CYCLES=29999999 bash "${RUNNER}" preflight
reject 'BOARD_LEAN_CYCLES out of' env AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_CYCLES=100000001 bash "${RUNNER}" preflight
reject 'BOARD_LEAN_WALL_SECONDS out of' env AETHERCORE_PRODUCT_GSIM_BOARD_LEAN_WALL_SECONDS=181 bash "${RUNNER}" preflight
reject 'RV64_BOARD_LEAN_WARM_CACHE_REQUIRED:' env AETHERCORE_RV64_LINUX_CROSS_COMPILE=/missing-toolchain-rv64- bash "${WARM_GUARD}"

grep -Fq 'CYCLES:-40000000' "${RUNNER}" || exit 1
grep -Fq 'fast-g4-unqualified' "${RUNNER}" || exit 1
grep -Fq 'same_cycle_evidence=NONE_FOR_BOARD_LEAN' "${RUNNER}" || exit 1
grep -Fq 'qualification=PRODUCT_GSIM_BOARD_LEAN_%s_CYCLES_' "${RUNNER}" || exit 1
# Kernel cache keys must remain valid across Chisel/RTL-only changes, while
# their firmware/DTB companion still follows the exact Scala design identity.
kernel_keys="$(grep -F 'key: aethercore-' "${WORKFLOW}" | grep -F 'board-lean-kernel-v2')"
[[ "$(wc -l <<< "${kernel_keys}")" == 2 ]] || exit 1
! grep -Eq 'src/main/scala|build.mill' <<< "${kernel_keys}" || exit 1
payload_keys="$(grep -F 'key: aethercore-' "${WORKFLOW}" | grep -F 'board-lean-payload-v2')"
[[ "$(wc -l <<< "${payload_keys}")" == 2 ]] || exit 1
grep -Fq 'src/main/scala/**/*.scala' <<< "${payload_keys}" || exit 1
grep -Fq 'bash tools/ci/rv64_board_lean_linux_warm_guard.sh' "${WORKFLOW}" || exit 1
grep -Fq 'OPENSBI_UART_ONLY|LINUX_BANNER' "${WORKFLOW}" || exit 1

echo 'PRODUCT_GSIM_BOARD_LEAN_SELFTEST_PASS rejected=6 default_cycles=40000000 bound_wall_s=180 kernel_cache_rtl_independent=1 firmware_cache_rtl_sensitive=1 cold_build_forbidden=1 linux_banner_observational=1 guest_cycles=0'
