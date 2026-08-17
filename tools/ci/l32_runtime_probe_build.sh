#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

BUSYBOX_BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"
BUILD_DIR="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}"
SOURCE="${ROOT_DIR}/software/l32_busybox/runtime_probe.c"
REAL_GCC="${L32_USERSPACE_MUSL_WRAPPER}"
OUTPUT="${BUILD_DIR}/l32-runtime-probe"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"
OBJDUMP="${L32_USERSPACE_CROSS_COMPILE_PREFIX}objdump"
PROFILE_AUDIT="${ROOT_DIR}/tools/ci/riscv_elf_profile.py"
WRAPPER_TOOL="${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"

mkdir -p "${BUILD_DIR}"

[[ -s "${SOURCE}" ]] || { echo "ERROR: missing L32 runtime probe source" >&2; exit 20; }
grep -qx 'L32_BUSYBOX_BUILD_RESULT: status=PASS' "${BUSYBOX_BUILD_DIR}/result.txt" || {
  echo "ERROR: qualified L32 musl/BusyBox build is required first" >&2
  exit 21
}
grep -qx "profile=${L32_USERSPACE_PROFILE}" "${BUSYBOX_BUILD_DIR}/result.txt" || {
  echo "ERROR: musl/BusyBox profile does not match runtime probe profile" >&2
  exit 21
}
[[ -x "${WRAPPER_TOOL}" ]] || { echo "ERROR: missing musl link-wrapper generator" >&2; exit 21; }
"${WRAPPER_TOOL}" >/dev/null
[[ -x "${REAL_GCC}" ]] || { echo "ERROR: qualified musl link wrapper is missing: ${REAL_GCC}" >&2; exit 21; }
for tool in "${READELF}" "${OBJDUMP}"; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "ERROR: missing ${tool}" >&2; exit 22; }
done

GITHUB_WORKSPACE="${ROOT_DIR}" "${REAL_GCC}" \
  -Os -pipe -fno-pie -no-pie \
  -Wall -Wextra -Werror \
  "${SOURCE}" -o "${OUTPUT}"

[[ -s "${OUTPUT}" ]] || { echo "ERROR: runtime probe was not produced" >&2; exit 23; }
"${READELF}" -h -l -A "${OUTPUT}" | tee "${BUILD_DIR}/readelf.txt"
file "${OUTPUT}" | tee "${BUILD_DIR}/file.txt"
file "${OUTPUT}" | grep -q 'statically linked'

c_policy=(--forbid-c)
if [[ "${L32_USERSPACE_REQUIRE_C}" -eq 1 ]]; then
  c_policy=(--require-c)
fi
python3 "${PROFILE_AUDIT}" \
  --elf "${OUTPUT}" \
  --name runtime-probe \
  --readelf "${READELF}" \
  --objdump "${OBJDUMP}" \
  "${c_policy[@]}" \
  --output "${BUILD_DIR}/profile.txt"
probe_compressed="$(awk -F= '$1 == "compressed_instructions" {print $2; exit}' "${BUILD_DIR}/profile.txt")"

{
  echo "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "abi=${L32_USERSPACE_ABI}"
  echo "require_c=${L32_USERSPACE_REQUIRE_C}"
  echo "compressed_instructions=${probe_compressed}"
  echo "probe=${OUTPUT}"
  echo "probe_sha256=$(sha256sum "${OUTPUT}" | awk '{print $1}')"
  echo "source_sha256=$(sha256sum "${SOURCE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
