#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

BUSYBOX_BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"
RUNTIME_PROBE_BUILD_DIR="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}"
REAL_BUILD_DIR="${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}"
LINUX_BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"
PAYLOAD_BUILD_DIR="${L32_USERSPACE_PAYLOAD_BUILD_DIR}"
BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox-src/busybox"
RUNTIME_PROBE_ELF="${RUNTIME_PROBE_BUILD_DIR}/l32-runtime-probe"
LUA_ELF="${REAL_BUILD_DIR}/lua"
SQLITE_ELF="${REAL_BUILD_DIR}/sqlite-smoke"
BASH_ELF="${REAL_BUILD_DIR}/bash"
BUSYBOX_REAL_ELF="${REAL_BUILD_DIR}/busybox-real"
ZLIB_ELF="${REAL_BUILD_DIR}/zlib-smoke"
LIBPNG_ELF="${REAL_BUILD_DIR}/libpng-smoke"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
FW_BIN="${PAYLOAD_BUILD_DIR}/opensbi/platform/generic/firmware/fw_payload.bin"
PAYLOAD_SHA256="${PAYLOAD_BUILD_DIR}/evidence/sha256.txt"

require_profile_pass() {
  local file="$1" marker="$2" label="$3"
  [[ -f "${file}" ]] || {
    echo "ERROR: missing qualified ${label} result: ${file}" >&2
    exit 20
  }
  grep -qx "${marker}" "${file}" || {
    echo "ERROR: ${label} result is not qualified: ${file}" >&2
    exit 21
  }
  grep -qx "profile=${L32_USERSPACE_PROFILE}" "${file}" || {
    echo "ERROR: ${label} result profile does not match ${L32_USERSPACE_PROFILE}: ${file}" >&2
    exit 21
  }
}

verify_recorded_sha() {
  local file="$1" result="$2" key="$3" label="$4"
  [[ -s "${file}" ]] || {
    echo "ERROR: missing frozen ${label}: ${file}" >&2
    exit 22
  }
  local expected actual
  expected="$(sed -n "s/^${key}=//p" "${result}" | head -n 1)"
  actual="$(sha256sum "${file}" | awk '{print $1}')"
  [[ -n "${expected}" && "${actual}" == "${expected}" ]] || {
    echo "ERROR: frozen ${label} hash drifted: expected=${expected:-missing} actual=${actual}" >&2
    exit 23
  }
}

require_profile_pass \
  "${BUSYBOX_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_BUILD_RESULT: status=PASS" \
  "BusyBox"
require_profile_pass \
  "${RUNTIME_PROBE_BUILD_DIR}/result.txt" \
  "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS" \
  "runtime probe"
require_profile_pass \
  "${REAL_BUILD_DIR}/result.txt" \
  "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS" \
  "real programs"
require_profile_pass \
  "${LINUX_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS" \
  "BusyBox initramfs kernel"
require_profile_pass \
  "${PAYLOAD_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS" \
  "OpenSBI payload"

verify_recorded_sha \
  "${BUSYBOX_ELF}" \
  "${BUSYBOX_BUILD_DIR}/result.txt" \
  "busybox_sha256" \
  "BusyBox ELF"
verify_recorded_sha \
  "${RUNTIME_PROBE_ELF}" \
  "${RUNTIME_PROBE_BUILD_DIR}/result.txt" \
  "probe_sha256" \
  "runtime probe ELF"
verify_recorded_sha \
  "${LUA_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "lua_sha256" \
  "Lua userspace ELF"
verify_recorded_sha \
  "${SQLITE_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "sqlite_sha256" \
  "SQLite userspace ELF"
verify_recorded_sha \
  "${BASH_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "bash_sha256" \
  "Bash userspace ELF"
verify_recorded_sha \
  "${BUSYBOX_REAL_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "busybox_real_sha256" \
  "BusyBox real userspace ELF"
verify_recorded_sha \
  "${ZLIB_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "zlib_sha256" \
  "zlib userspace ELF"
verify_recorded_sha \
  "${LIBPNG_ELF}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "libpng_sha256" \
  "libpng userspace ELF"
verify_recorded_sha \
  "${LINUX_IMAGE}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "image_sha256" \
  "Linux BusyBox Image"

[[ -s "${FW_BIN}" ]] || {
  echo "ERROR: frozen OpenSBI+Linux+BusyBox firmware is missing: ${FW_BIN}" >&2
  exit 24
}
verify_recorded_sha \
  "${FW_BIN}" \
  "${PAYLOAD_BUILD_DIR}/result.txt" \
  "firmware_bin_sha256" \
  "OpenSBI payload binary"
[[ -f "${PAYLOAD_SHA256}" ]] || {
  echo "ERROR: frozen payload SHA256 evidence is missing: ${PAYLOAD_SHA256}" >&2
  exit 25
}

(
  cd "${ROOT_DIR}"
  sha256sum -c "${PAYLOAD_SHA256}"
)

{
  echo "L32_BUSYBOX_RUNTIME_FREEZE: status=PASS"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "require_c=${L32_USERSPACE_REQUIRE_C}"
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
  echo "runtime_probe_sha256=$(sha256sum "${RUNTIME_PROBE_ELF}" | awk '{print $1}')"
  echo "lua_sha256=$(sha256sum "${LUA_ELF}" | awk '{print $1}')"
  echo "sqlite_sha256=$(sha256sum "${SQLITE_ELF}" | awk '{print $1}')"
  echo "bash_sha256=$(sha256sum "${BASH_ELF}" | awk '{print $1}')"
  echo "busybox_real_sha256=$(sha256sum "${BUSYBOX_REAL_ELF}" | awk '{print $1}')"
  echo "zlib_sha256=$(sha256sum "${ZLIB_ELF}" | awk '{print $1}')"
  echo "libpng_sha256=$(sha256sum "${LIBPNG_ELF}" | awk '{print $1}')"
  echo "linux_image_sha256=$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
  echo "firmware_bin=${FW_BIN}"
  echo "firmware_sha256=$(sha256sum "${FW_BIN}" | awk '{print $1}')"
} | tee "${PAYLOAD_BUILD_DIR}/runtime-freeze.txt"
