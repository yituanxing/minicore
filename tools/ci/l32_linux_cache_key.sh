#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${2:-${ROOT_DIR}/build/l32-linux}"
MODE="${1:-key}"

key="$({
  sha256sum \
    "${ROOT_DIR}/software/l32/manifest.env" \
    "${ROOT_DIR}/tools/ci/l32_linux_build.sh" \
    "${ROOT_DIR}/tools/ensure_l32_riscv32_linux_gcc.sh"
} | sha256sum | awk '{print $1}')"
marker="${BUILD_DIR}/evidence/input-key.txt"

case "${MODE}" in
  key)
    printf '%s\n' "${key}"
    ;;
  check)
    [[ -s "${BUILD_DIR}/obj/vmlinux" ]]
    [[ -s "${BUILD_DIR}/obj/arch/riscv/boot/Image" ]]
    [[ -f "${BUILD_DIR}/result.txt" ]]
    grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${BUILD_DIR}/result.txt"
    [[ -f "${marker}" ]]
    [[ "$(cat "${marker}")" == "${key}" ]]
    printf 'L32 Linux build cache hit: %s\n' "${key}"
    ;;
  mark)
    [[ -s "${BUILD_DIR}/obj/vmlinux" ]]
    [[ -s "${BUILD_DIR}/obj/arch/riscv/boot/Image" ]]
    [[ -f "${BUILD_DIR}/result.txt" ]]
    grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${BUILD_DIR}/result.txt"
    mkdir -p "$(dirname "${marker}")"
    printf '%s\n' "${key}" > "${marker}"
    printf 'L32 Linux build cache marked: %s\n' "${key}"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [build-dir]" >&2
    exit 2
    ;;
esac
