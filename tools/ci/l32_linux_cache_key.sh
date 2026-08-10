#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/linux-freeze.env"
BUILD_DIR="${2:-${ROOT_DIR}/build/l32-linux}"
MODE="${1:-key}"

key="$({
  sha256sum \
    "${ROOT_DIR}/software/l32/manifest.env" \
    "${ROOT_DIR}/software/l32/linux-freeze.env" \
    "${ROOT_DIR}/tools/ci/l32_linux_build.sh" \
    "${ROOT_DIR}/tools/ensure_l32_riscv32_linux_gcc.sh"
} | sha256sum | awk '{print $1}')"
marker="${BUILD_DIR}/evidence/input-key.txt"

outputs=(
  "${BUILD_DIR}/obj/vmlinux"
  "${BUILD_DIR}/obj/arch/riscv/boot/Image"
  "${BUILD_DIR}/evidence/resolved.config"
)
frozen_sha=(
  "${L32_LINUX_VMLINUX_SHA256}"
  "${L32_LINUX_IMAGE_SHA256}"
  "${L32_LINUX_CONFIG_SHA256}"
)

verify_outputs() {
  local require_marker="$1"
  local i output rel expected actual
  for i in "${!outputs[@]}"; do
    output="${outputs[$i]}"
    [[ -s "${output}" ]] || return 1
    actual="$(sha256sum "${output}" | awk '{print $1}')"
    [[ "${actual}" == "${frozen_sha[$i]}" ]] || return 1
    if [[ "${require_marker}" == "yes" ]]; then
      rel="${output#${ROOT_DIR}/}"
      expected="$(awk -v p="${rel}" '$1 == "sha256" && $3 == p { print $2; exit }' "${marker}" 2>/dev/null || true)"
      [[ -n "${expected}" && "${actual}" == "${expected}" ]] || return 1
    fi
  done
}

case "${MODE}" in
  key)
    printf '%s\n' "${key}"
    ;;
  check)
    [[ -f "${BUILD_DIR}/result.txt" ]]
    grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${BUILD_DIR}/result.txt"
    [[ -f "${marker}" ]]
    [[ "$(awk '$1 == "input_key" { print $2; exit }' "${marker}" 2>/dev/null)" == "${key}" ]]
    verify_outputs yes
    printf 'L32 Linux build cache hit: %s\n' "${key}"
    ;;
  mark)
    [[ -f "${BUILD_DIR}/result.txt" ]]
    grep -qx 'L32_LINUX_BUILD_RESULT: status=PASS' "${BUILD_DIR}/result.txt"
    verify_outputs no
    mkdir -p "$(dirname "${marker}")"
    tmp="${marker}.tmp.$$"
    {
      printf 'input_key %s\n' "${key}"
      for output in "${outputs[@]}"; do
        rel="${output#${ROOT_DIR}/}"
        printf 'sha256 %s %s\n' "$(sha256sum "${output}" | awk '{print $1}')" "${rel}"
      done
    } > "${tmp}"
    mv "${tmp}" "${marker}"
    printf 'L32 Linux build cache marked: %s\n' "${key}"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [build-dir]" >&2
    exit 2
    ;;
esac
