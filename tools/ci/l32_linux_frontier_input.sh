#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export AETHERCORE_L32_USERSPACE_PROFILE="${AETHERCORE_L32_USERSPACE_PROFILE:-rv32imac}"

fail() {
  echo "L32_LINUX_FRONTIER_INPUT: status=MISS reason=$1" >&2
  exit 40
}

[[ "${AETHERCORE_L32_USERSPACE_PROFILE}" == "rv32imac" ]] \
  || fail "frontier-profile-must-be-rv32imac"

# shellcheck disable=SC1091
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
[[ "${L32_USERSPACE_PROFILE}" == "rv32imac" ]] || fail "profile"
[[ "${L32_USERSPACE_REQUIRE_C}" == "1" ]] || fail "compressed-contract"

# Frontier is a restore-only consumer. The repository freeze manifest owns the
# accepted software identity; the persistent runner cache owns bytes only.
# Missing or mismatched bytes fail closed here and never trigger a software build.
resolved="$(bash "${ROOT_DIR}/tools/ci/l32_linux_frontier_artifact.sh" resolve)" \
  || fail "qualified-artifact"

grep -qx 'L32_LINUX_FRONTIER_ARTIFACT: status=PASS' <<<"${resolved}" \
  || fail "artifact-status"
grep -qx 'profile=rv32imac' <<<"${resolved}" || fail "artifact-profile"
grep -qx 'isa=rv32imac_zicsr_zifencei' <<<"${resolved}" || fail "artifact-isa"
grep -qx 'require_c=1' <<<"${resolved}" || fail "artifact-compressed"

firmware_bin="$(sed -n 's/^firmware_bin=//p' <<<"${resolved}" | head -n 1)"
[[ -n "${firmware_bin}" && -s "${firmware_bin}" ]] || fail "artifact-firmware-bin"

printf 'L32_LINUX_FRONTIER_INPUT: status=PASS\n'
printf '%s\n' "${resolved}" | grep -E '^(profile|isa|require_c|linux_image_sha256|fw_payload_sha256|qualification|firmware_bin)='
