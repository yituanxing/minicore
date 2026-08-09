#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32/linux-freeze.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32"
SOURCE_DIR="${CACHE_ROOT}/opensbi/${OPENSBI_COMMIT}"
LINUX_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
BUILD_DIR="${ROOT_DIR}/build/l32-linux-early-boot"
OPENSBI_OUT="${BUILD_DIR}/opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv32-linux.dtb"
FW_TEXT_START=0x80000000
FW_PAYLOAD_OFFSET=0x400000
BOOTARGS="${L32_LINUX_BOOTARGS:-earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200}"
JOBS="${L32_EARLY_BOOT_JOBS:-$(nproc)}"

mkdir -p "${CACHE_ROOT}/opensbi" "${BUILD_DIR}" "${EVIDENCE_DIR}"

command -v "${L32_CROSS_COMPILE_PREFIX}gcc" >/dev/null 2>&1 || {
  echo "ERROR: provision the pinned L32 Linux toolchain first" >&2
  exit 20
}

bash "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" check "${LINUX_BUILD_DIR}"
observed_cache_key="$(cat "${LINUX_BUILD_DIR}/evidence/input-key.txt")"
[[ "${observed_cache_key}" == "${L32_LINUX_BUILD_CACHE_KEY}" ]] || {
  echo "ERROR: Linux build cache key drifted: ${observed_cache_key}" >&2
  exit 21
}
observed_image_sha="$(sha256sum "${IMAGE}" | awk '{print $1}')"
[[ "${observed_image_sha}" == "${L32_LINUX_IMAGE_SHA256}" ]] || {
  echo "ERROR: frozen Linux Image SHA256 drifted: ${observed_image_sha}" >&2
  exit 21
}

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv32-linux-dtb.txt" \
  --bootargs "${BOOTARGS}"

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  rm -rf "${SOURCE_DIR}"
  mkdir -p "${SOURCE_DIR}"
  git -C "${SOURCE_DIR}" init -q
  git -C "${SOURCE_DIR}" remote add origin "${OPENSBI_REPOSITORY}"
  git -C "${SOURCE_DIR}" fetch --depth=1 origin "${OPENSBI_COMMIT}"
  git -C "${SOURCE_DIR}" checkout -q --detach FETCH_HEAD
fi
observed_commit="$(git -C "${SOURCE_DIR}" rev-parse HEAD)"
[[ "${observed_commit}" == "${OPENSBI_COMMIT}" ]] || {
  echo "ERROR: cached OpenSBI commit ${observed_commit} != ${OPENSBI_COMMIT}" >&2
  exit 22
}
git -C "${SOURCE_DIR}" diff --quiet --ignore-submodules -- || {
  echo "ERROR: cached OpenSBI source tree is dirty" >&2
  exit 22
}

rm -rf "${OPENSBI_OUT}"
mkdir -p "${OPENSBI_OUT}"

make -C "${SOURCE_DIR}" \
  O="${OPENSBI_OUT}" \
  PLATFORM="${L32_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${L32_XLEN}" \
  PLATFORM_RISCV_ISA="${OPENSBI_RV32_ISA}" \
  PLATFORM_RISCV_ABI="${OPENSBI_RV32_ABI}" \
  FW_TEXT_START="${FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${IMAGE}" \
  FW_PAYLOAD_OFFSET="${FW_PAYLOAD_OFFSET}" \
  CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" \
  2>&1 | tee "${BUILD_DIR}/opensbi-linux-build.log"

FW_ELF="${OPENSBI_OUT}/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_OUT}/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: Linux-bearing OpenSBI payload was not produced" >&2
  exit 23
}

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -l -A "${FW_ELF}" \
  | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${IMAGE}" "${DTB}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF32'
"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${FW_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
entry="$(${L32_CROSS_COMPILE_PREFIX}readelf -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((FW_TEXT_START))" ]] || {
  echo "ERROR: OpenSBI entry ${entry} != ${FW_TEXT_START}" >&2
  exit 24
}
[[ "$((FW_TEXT_START + FW_PAYLOAD_OFFSET))" -eq "$((L32_LINUX_PHYS_ENTRY))" ]] || {
  echo "ERROR: Linux payload entry contract is not 0x80400000" >&2
  exit 24
}

{
  echo "L32_LINUX_EARLY_BOOT_BUILD_RESULT: status=PASS"
  echo "opensbi_commit=${OPENSBI_COMMIT}"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "linux_phys_entry=${L32_LINUX_PHYS_ENTRY}"
  echo "linux_image_sha256=${observed_image_sha}"
  echo "linux_cache_key=${observed_cache_key}"
  echo "bootargs=${BOOTARGS}"
} | tee "${BUILD_DIR}/result.txt"
