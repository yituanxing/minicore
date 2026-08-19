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

# This is validation only. The freeze script hashes and cross-checks the already
# produced BusyBox, real-program, Linux and OpenSBI outputs; it never builds them.
bash "${ROOT_DIR}/tools/ci/l32_busybox_runtime_freeze.sh" >/dev/null \
  || fail "runtime-freeze"

FREEZE="${L32_USERSPACE_PAYLOAD_BUILD_DIR}/runtime-freeze.txt"
FW_BIN="${L32_USERSPACE_PAYLOAD_BUILD_DIR}/opensbi/platform/generic/firmware/fw_payload.bin"
LINUX_IMAGE="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/obj/arch/riscv/boot/Image"

[[ -f "${FREEZE}" ]] || fail "freeze-record"
grep -qx 'L32_BUSYBOX_RUNTIME_FREEZE: status=PASS' "${FREEZE}" \
  || fail "freeze-marker"
grep -qx 'profile=rv32imac' "${FREEZE}" || fail "freeze-profile"
grep -qx 'isa=rv32imac_zicsr_zifencei' "${FREEZE}" || fail "freeze-isa"
grep -qx 'require_c=1' "${FREEZE}" || fail "freeze-compressed"
[[ -s "${FW_BIN}" ]] || fail "firmware-bin"
[[ -s "${LINUX_IMAGE}" ]] || fail "linux-image"

expected_fw="$(sed -n 's/^firmware_sha256=//p' "${FREEZE}" | head -n 1)"
actual_fw="$(sha256sum "${FW_BIN}" | awk '{print $1}')"
[[ -n "${expected_fw}" && "${expected_fw}" == "${actual_fw}" ]] \
  || fail "firmware-sha"

expected_linux="$(sed -n 's/^linux_image_sha256=//p' "${FREEZE}" | head -n 1)"
actual_linux="$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
[[ -n "${expected_linux}" && "${expected_linux}" == "${actual_linux}" ]] \
  || fail "linux-sha"

printf 'L32_LINUX_FRONTIER_INPUT: status=PASS\n'
printf 'profile=%s\n' "${L32_USERSPACE_PROFILE}"
printf 'isa=%s\n' "${L32_USERSPACE_EFFECTIVE_ISA}"
printf 'require_c=%s\n' "${L32_USERSPACE_REQUIRE_C}"
printf 'linux_image_sha256=%s\n' "${actual_linux}"
printf 'fw_payload_sha256=%s\n' "${actual_fw}"
printf 'firmware_bin=%s\n' "${FW_BIN#${ROOT_DIR}/}"
