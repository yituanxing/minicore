#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-busybox"
LINUX_BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox"
PAYLOAD_BUILD_DIR="${ROOT_DIR}/build/l32-busybox-shell-boot"
BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox-src/busybox"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
FW_BIN="${PAYLOAD_BUILD_DIR}/opensbi/platform/generic/firmware/fw_payload.bin"
PAYLOAD_SHA256="${PAYLOAD_BUILD_DIR}/evidence/sha256.txt"

require_pass() {
  local file="$1" marker="$2"
  [[ -f "${file}" ]] || {
    echo "ERROR: missing frozen runtime result: ${file}" >&2
    exit 20
  }
  grep -qx "${marker}" "${file}" || {
    echo "ERROR: frozen runtime result is not qualified: ${file}" >&2
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

require_pass \
  "${BUSYBOX_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_BUILD_RESULT: status=PASS"
require_pass \
  "${LINUX_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS"
require_pass \
  "${PAYLOAD_BUILD_DIR}/result.txt" \
  "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS"

verify_recorded_sha \
  "${BUSYBOX_ELF}" \
  "${BUSYBOX_BUILD_DIR}/result.txt" \
  "busybox_sha256" \
  "BusyBox ELF"
verify_recorded_sha \
  "${LINUX_IMAGE}" \
  "${LINUX_BUILD_DIR}/result.txt" \
  "image_sha256" \
  "Linux BusyBox Image"

[[ -s "${FW_BIN}" ]] || {
  echo "ERROR: frozen OpenSBI+Linux+BusyBox firmware is missing: ${FW_BIN}" >&2
  exit 24
}
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
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
  echo "linux_image_sha256=$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
  echo "firmware_bin=${FW_BIN}"
  echo "firmware_sha256=$(sha256sum "${FW_BIN}" | awk '{print $1}')"
} | tee "${PAYLOAD_BUILD_DIR}/runtime-freeze.txt"
