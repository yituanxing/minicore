#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FREEZE_MANIFEST="${ROOT_DIR}/software/l32/linux-frontier-freeze.env"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"

fail() {
  echo "L32_LINUX_FRONTIER_ARTIFACT: status=MISS reason=$1" >&2
  exit 41
}

field() {
  local file="$1" key="$2"
  sed -n "s/^${key}=//p" "${file}" 2>/dev/null | head -n 1
}

[[ -f "${FREEZE_MANIFEST}" ]] || fail "repo-freeze-manifest"
version="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_FREEZE_VERSION)"
profile="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_PROFILE)"
isa="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_ISA)"
require_c="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_REQUIRE_C)"
linux_sha="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_LINUX_IMAGE_SHA256)"
firmware_sha="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_FIRMWARE_SHA256)"
qualification="$(field "${FREEZE_MANIFEST}" L32_LINUX_FRONTIER_QUALIFICATION)"

[[ "${version}" == "1" ]] || fail "freeze-version"
[[ "${profile}" == "rv32imac" ]] || fail "freeze-profile"
[[ "${isa}" == "rv32imac_zicsr_zifencei" ]] || fail "freeze-isa"
[[ "${require_c}" == "1" ]] || fail "freeze-compressed"
[[ "${linux_sha}" =~ ^[0-9a-f]{64}$ ]] || fail "freeze-linux-sha"
[[ "${firmware_sha}" =~ ^[0-9a-f]{64}$ ]] || fail "freeze-firmware-sha"
[[ -n "${qualification}" ]] || fail "freeze-qualification"

CACHE_DIR="${CACHE_ROOT}/l32/linux-frontier/${profile}/${firmware_sha}"
CACHE_FIRMWARE="${CACHE_DIR}/fw_payload.bin"
CACHE_MANIFEST="${CACHE_DIR}/manifest.env"

verify_cache() {
  [[ -s "${CACHE_FIRMWARE}" && -f "${CACHE_MANIFEST}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" freeze_version)" == "${version}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" profile)" == "${profile}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" isa)" == "${isa}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" require_c)" == "${require_c}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" linux_image_sha256)" == "${linux_sha}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" firmware_sha256)" == "${firmware_sha}" ]] || return 1
  [[ "$(field "${CACHE_MANIFEST}" qualification)" == "${qualification}" ]] || return 1
  [[ "$(sha256sum "${CACHE_FIRMWARE}" | awk '{print $1}')" == "${firmware_sha}" ]] || return 1
}

print_resolved() {
  printf 'L32_LINUX_FRONTIER_ARTIFACT: status=PASS\n'
  printf 'profile=%s\n' "${profile}"
  printf 'isa=%s\n' "${isa}"
  printf 'require_c=%s\n' "${require_c}"
  printf 'linux_image_sha256=%s\n' "${linux_sha}"
  printf 'fw_payload_sha256=%s\n' "${firmware_sha}"
  printf 'qualification=%s\n' "${qualification}"
  printf 'firmware_bin=%s\n' "${CACHE_FIRMWARE}"
}

publish() {
  local runtime_freeze="${ROOT_DIR}/build/l32-busybox-shell-boot-rv32imac/runtime-freeze.txt"
  local forkserver_log="${ROOT_DIR}/build/l32-busybox-forkserver-rv32imac/logs/forkserver.log"
  [[ -f "${runtime_freeze}" ]] || fail "producer-runtime-freeze"
  [[ -f "${forkserver_log}" ]] || fail "producer-25-case-log"
  grep -qx 'L32_BUSYBOX_RUNTIME_FREEZE: status=PASS' "${runtime_freeze}" || fail "producer-freeze-status"
  grep -qx 'profile=rv32imac' "${runtime_freeze}" || fail "producer-profile"
  grep -qx 'isa=rv32imac_zicsr_zifencei' "${runtime_freeze}" || fail "producer-isa"
  grep -qx 'require_c=1' "${runtime_freeze}" || fail "producer-compressed"
  grep -Eq '^L32_FORKSERVER_RESULT cases=25 passed=25 failed=0 boot-cycles=[0-9]+$' "${forkserver_log}" \
    || fail "producer-25-case-result"
  grep -Eq '^L32_FORKSERVER_PASS cases=25 boot-cycles=[0-9]+$' "${forkserver_log}" \
    || fail "producer-25-case-pass"

  local producer_linux producer_firmware source_firmware
  producer_linux="$(field "${runtime_freeze}" linux_image_sha256)"
  producer_firmware="$(field "${runtime_freeze}" firmware_sha256)"
  source_firmware="$(field "${runtime_freeze}" firmware_bin)"
  [[ "${producer_linux}" == "${linux_sha}" ]] || fail "producer-linux-sha"
  [[ "${producer_firmware}" == "${firmware_sha}" ]] || fail "producer-firmware-sha"
  [[ -s "${source_firmware}" ]] || fail "producer-firmware-bin"
  [[ "$(sha256sum "${source_firmware}" | awk '{print $1}')" == "${firmware_sha}" ]] \
    || fail "producer-firmware-bytes"

  if verify_cache; then
    echo "L32_LINUX_FRONTIER_ARTIFACT_PUBLISH: status=PASS cache=${CACHE_DIR} reused=1"
    print_resolved
    return 0
  fi

  local parent tmpdir
  parent="$(dirname "${CACHE_DIR}")"
  mkdir -p "${parent}"
  tmpdir="$(mktemp -d "${parent}/.${firmware_sha}.tmp.XXXXXX")"
  trap 'rm -rf "${tmpdir}"' EXIT
  install -m 0644 "${source_firmware}" "${tmpdir}/fw_payload.bin"
  [[ "$(sha256sum "${tmpdir}/fw_payload.bin" | awk '{print $1}')" == "${firmware_sha}" ]] \
    || fail "publish-copy-sha"
  {
    echo "freeze_version=${version}"
    echo "profile=${profile}"
    echo "isa=${isa}"
    echo "require_c=${require_c}"
    echo "linux_image_sha256=${linux_sha}"
    echo "firmware_sha256=${firmware_sha}"
    echo "qualification=${qualification}"
  } > "${tmpdir}/manifest.env"

  rm -rf "${CACHE_DIR}"
  mv "${tmpdir}" "${CACHE_DIR}"
  trap - EXIT
  verify_cache || fail "published-cache-verification"
  echo "L32_LINUX_FRONTIER_ARTIFACT_PUBLISH: status=PASS cache=${CACHE_DIR} reused=0"
  print_resolved
}

resolve() {
  verify_cache || fail "qualified-cache"
  print_resolved
}

case "${1:-resolve}" in
  publish) publish ;;
  resolve) resolve ;;
  *) echo "usage: $0 [publish|resolve]" >&2; exit 2 ;;
esac
