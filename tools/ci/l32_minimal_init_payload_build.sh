#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32"
SOURCE_DIR="${CACHE_ROOT}/opensbi/${OPENSBI_COMMIT}"
LINUX_BUILD_DIR="${ROOT_DIR}/build/l32-linux-initramfs"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
BUILD_DIR="${ROOT_DIR}/build/l32-minimal-init-boot"
OPENSBI_BUILD_DIR="${BUILD_DIR}/opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv32-initramfs.dtb"
JOBS="${L32_JOBS:-$(nproc)}"
FW_TEXT_START="0x80000000"
FW_PAYLOAD_OFFSET="0x00400000"
FW_PAYLOAD_FDT_ADDR="0x87f00000"
BOOTARGS="earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200 rdinit=/init"

mkdir -p "${CACHE_ROOT}/opensbi" "${BUILD_DIR}" "${EVIDENCE_DIR}"

grep -qx 'L32_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS' "${LINUX_BUILD_DIR}/result.txt" || {
  echo "ERROR: minimal initramfs Linux image is not qualified" >&2
  exit 20
}
[[ -s "${LINUX_IMAGE}" ]] || { echo "ERROR: minimal initramfs Linux Image is missing" >&2; exit 20; }
actual_image_sha="$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
recorded_image_sha="$(sed -n 's/^image_sha256=//p' "${LINUX_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_image_sha}" && "${actual_image_sha}" == "${recorded_image_sha}" ]] || {
  echo "ERROR: minimal initramfs Image hash drifted" >&2
  exit 21
}

command -v "${L32_CROSS_COMPILE_PREFIX}gcc" >/dev/null 2>&1 || {
  echo "ERROR: provision the pinned L32 Linux toolchain first" >&2
  exit 22
}

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv32-initramfs-dtb.txt" \
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
  exit 23
}
git -C "${SOURCE_DIR}" diff --quiet --ignore-submodules -- || {
  echo "ERROR: cached OpenSBI source tree is dirty" >&2
  exit 24
}

rm -rf "${OPENSBI_BUILD_DIR}"
mkdir -p "${OPENSBI_BUILD_DIR}"

{
  echo "L32_MINIMAL_INIT_PAYLOAD_INPUT: status=READY"
  echo "linux_version=${LINUX_VERSION}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "opensbi_commit=${OPENSBI_COMMIT}"
  echo "fw_text_start=${FW_TEXT_START}"
  echo "fw_payload_offset=${FW_PAYLOAD_OFFSET}"
  echo "fw_payload_address=0x80400000"
  echo "fw_payload_fdt_addr=${FW_PAYLOAD_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
  "${L32_CROSS_COMPILE_PREFIX}gcc" --version | head -n 1
} | tee "${EVIDENCE_DIR}/inputs.txt"

make -C "${SOURCE_DIR}" \
  O="${OPENSBI_BUILD_DIR}" \
  PLATFORM="${L32_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${L32_XLEN}" \
  PLATFORM_RISCV_ISA="${OPENSBI_RV32_ISA}" \
  PLATFORM_RISCV_ABI="${OPENSBI_RV32_ABI}" \
  FW_TEXT_START="${FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${LINUX_IMAGE}" \
  FW_PAYLOAD_OFFSET="${FW_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${FW_PAYLOAD_FDT_ADDR}" \
  CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-initramfs-build.log"

FW_ELF="${OPENSBI_BUILD_DIR}/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_BUILD_DIR}/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: missing OpenSBI+initramfs Linux payload outputs" >&2
  exit 25
}

READELF="${L32_CROSS_COMPILE_PREFIX}readelf"
"${READELF}" -h -l -A "${FW_ELF}" | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
file "${FW_ELF}" "${FW_BIN}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/files.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/sha256.txt"
entry="$(${READELF} -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((FW_TEXT_START))" ]] || exit 26
[[ "$((FW_TEXT_START + FW_PAYLOAD_OFFSET))" -eq "$((0x80400000))" ]] || exit 27

{
  echo "L32_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "fdt=${DTB}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "entry=${entry}"
  echo "next_addr=0x80400000"
  echo "next_mode=S-mode"
  echo "fdt_addr=${FW_PAYLOAD_FDT_ADDR}"
} | tee "${BUILD_DIR}/result.txt"
