#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LINUX_DIR="${ROOT_DIR}/build/l32-linux"
PAYLOAD_DIR="${ROOT_DIR}/build/l32-linux-boot"
LINUX_IMAGE="${LINUX_DIR}/obj/arch/riscv/boot/Image"
PAYLOAD_RESULT="${PAYLOAD_DIR}/result.txt"
PAYLOAD_MARKER="${PAYLOAD_DIR}/evidence/software-cache.txt"
FW_ELF="${PAYLOAD_DIR}/opensbi/platform/generic/firmware/fw_payload.elf"
FW_BIN="${PAYLOAD_DIR}/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="${PAYLOAD_DIR}/aethercore-rv32-linux.dtb"

fail() {
  echo "L32_LINUX_FRONTIER_INPUT: status=MISS reason=$1" >&2
  exit 40
}

"${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" check "${LINUX_DIR}" >/dev/null \
  || fail "linux-cache"

[[ -s "${LINUX_IMAGE}" ]] || fail "linux-image"
[[ -f "${PAYLOAD_RESULT}" ]] || fail "payload-result"
grep -qx 'L32_LINUX_PAYLOAD_BUILD_RESULT: status=PASS' "${PAYLOAD_RESULT}" \
  || fail "payload-result-marker"
[[ -f "${PAYLOAD_MARKER}" ]] || fail "payload-cache-marker"

for output in "${FW_ELF}" "${FW_BIN}" "${DTB}"; do
  [[ -s "${output}" ]] || fail "missing-output:${output#${ROOT_DIR}/}"
  rel="${output#${ROOT_DIR}/}"
  expected="$(awk -v p="${rel}" '$1 == "sha256" && $3 == p { print $2; exit }' "${PAYLOAD_MARKER}")"
  [[ -n "${expected}" ]] || fail "missing-sha:${rel}"
  actual="$(sha256sum "${output}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || fail "sha-mismatch:${rel}"
done

linux_image_sha256="$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
result_linux_sha256="$(sed -n 's/^linux_image_sha256=//p' "${PAYLOAD_RESULT}" | head -n 1)"
[[ -n "${result_linux_sha256}" ]] || fail "payload-linux-sha-missing"
[[ "${linux_image_sha256}" == "${result_linux_sha256}" ]] \
  || fail "payload-linux-sha-mismatch"

grep -qx 'entry=0x80000000' "${PAYLOAD_RESULT}" || fail "payload-entry"
grep -qx 'next_addr=0x80400000' "${PAYLOAD_RESULT}" || fail "payload-next-address"
grep -qx 'next_mode=S-mode' "${PAYLOAD_RESULT}" || fail "payload-next-mode"
grep -qx 'fdt_addr=0x87f00000' "${PAYLOAD_RESULT}" || fail "payload-fdt-address"

printf 'L32_LINUX_FRONTIER_INPUT: status=PASS\n'
printf 'linux_image_sha256=%s\n' "${linux_image_sha256}"
printf 'fw_payload_sha256=%s\n' "$(sha256sum "${FW_BIN}" | awk '{print $1}')"
printf 'dtb_sha256=%s\n' "$(sha256sum "${DTB}" | awk '{print $1}')"
printf 'firmware_bin=%s\n' "${FW_BIN#${ROOT_DIR}/}"
