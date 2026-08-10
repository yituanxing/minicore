#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${2:-${ROOT_DIR}/build/l32-linux}"
MODE="${1:-key}"
FREEZE_ENV="${ROOT_DIR}/software/l32/linux-freeze.env"

# The L32-C base kernel is a frozen executable input for every later Linux
# milestone.  A self-hosted runner keeps build/ between jobs, so an input-key
# marker alone is not sufficient: another workflow can leave files in the same
# object tree even while the marker still describes the original build.
# Validate the frozen output identity as well as the build inputs so cache hits
# are independent of workflow execution order.
# shellcheck disable=SC1090
source "${FREEZE_ENV}"

VMLINUX="${BUILD_DIR}/obj/vmlinux"
IMAGE="${BUILD_DIR}/obj/arch/riscv/boot/Image"
CONFIG="${BUILD_DIR}/evidence/resolved.config"
RESULT="${BUILD_DIR}/result.txt"

key="$({
  sha256sum \
    "${ROOT_DIR}/software/l32/manifest.env" \
    "${ROOT_DIR}/tools/ci/l32_linux_build.sh" \
    "${ROOT_DIR}/tools/ensure_l32_riscv32_linux_gcc.sh"
} | sha256sum | awk '{print $1}')"
marker="${BUILD_DIR}/evidence/input-key.txt"

verify_sha256() {
  local label="$1"
  local path="$2"
  local expected="$3"
  local actual

  if [[ ! -s "${path}" ]]; then
    printf 'L32 Linux cache miss: missing frozen %s: %s\n' "${label}" "${path}" >&2
    return 1
  fi

  actual="$(sha256sum "${path}" | awk '{print $1}')"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'L32 Linux cache miss: frozen %s SHA drift expected=%s actual=%s\n' \
      "${label}" "${expected}" "${actual}" >&2
    return 1
  fi
}

verify_frozen_outputs() {
  verify_sha256 vmlinux "${VMLINUX}" "${L32_LINUX_VMLINUX_SHA256}" || return 1
  verify_sha256 Image "${IMAGE}" "${L32_LINUX_IMAGE_SHA256}" || return 1
  verify_sha256 config "${CONFIG}" "${L32_LINUX_CONFIG_SHA256}" || return 1
}

validate_common() {
  [[ -f "${RESULT}" ]] || return 1
  grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${RESULT}" || return 1
  [[ -f "${marker}" ]] || return 1
  [[ "$(cat "${marker}")" == "${key}" ]] || return 1
  verify_frozen_outputs || return 1
}

case "${MODE}" in
  key)
    printf '%s\n' "${key}"
    ;;
  check)
    validate_common
    printf 'L32 Linux build cache hit: %s\n' "${key}"
    ;;
  mark)
    [[ -f "${RESULT}" ]]
    grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${RESULT}"
    verify_frozen_outputs
    mkdir -p "$(dirname "${marker}")"
    printf '%s\n' "${key}" > "${marker}"
    printf 'L32 Linux build cache marked: %s\n' "${key}"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [build-dir]" >&2
    exit 2
    ;;
esac
