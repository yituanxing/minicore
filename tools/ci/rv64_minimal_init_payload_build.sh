#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
OPENSBI_SOURCE="${CACHE_ROOT}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
LINUX_BUILD_DIR="${ROOT_DIR}/build/rv64-linux-initramfs"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
BUILD_DIR="${ROOT_DIR}/build/rv64-minimal-init-boot"
OPENSBI_BUILD_DIR="${BUILD_DIR}/opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv64-initramfs.dtb"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"
# Freeze-path bootargs: prove the same userspace/interrupt workload through the
# kernel's default legacy PTY registration path, with no exploration shortcut.
BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

grep -qx 'RV64_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS' "${LINUX_BUILD_DIR}/result.txt" || \
  fail "RV64 minimal initramfs Image is not qualified"
[[ -s "${LINUX_IMAGE}" ]] || fail "RV64 minimal initramfs Image is missing"
actual_image_sha="$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
recorded_image_sha="$(sed -n 's/^image_sha256=//p' "${LINUX_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_image_sha}" && "${actual_image_sha}" == "${recorded_image_sha}" ]] || \
  fail "RV64 minimal initramfs Image hash drifted"

[[ -d "${OPENSBI_SOURCE}/.git" ]] || fail "qualified OpenSBI source cache is missing"
[[ "$(git -C "${OPENSBI_SOURCE}" rev-parse HEAD)" == "${RV64_OPENSBI_COMMIT}" ]] || \
  fail "cached OpenSBI commit drifted"
git -C "${OPENSBI_SOURCE}" diff --quiet --ignore-submodules -- || fail "cached OpenSBI source tree is dirty"

rm -rf "${BUILD_DIR}"
mkdir -p "${OPENSBI_BUILD_DIR}" "${EVIDENCE_DIR}"

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv64-initramfs-dtb.txt" \
  --isa "${RV64_OPENSBI_ISA}" \
  --mmu "${RV64_OPENSBI_MMU}" \
  --bootargs "${BOOTARGS}"

{
  echo "RV64_MINIMAL_INIT_PAYLOAD_INPUT: status=READY"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "opensbi_commit=${RV64_OPENSBI_COMMIT}"
  echo "fw_text_start=${RV64_OPENSBI_FW_TEXT_START}"
  echo "fw_payload_offset=${RV64_OPENSBI_PAYLOAD_OFFSET}"
  echo "fw_payload_address=${RV64_OPENSBI_PAYLOAD_ADDR}"
  echo "fw_payload_fdt_addr=${RV64_OPENSBI_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
} | tee "${EVIDENCE_DIR}/inputs.txt"

make -C "${OPENSBI_SOURCE}" \
  O="${OPENSBI_BUILD_DIR}" \
  PLATFORM="${RV64_OPENSBI_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${RV64_OPENSBI_XLEN}" \
  PLATFORM_RISCV_ISA="${RV64_OPENSBI_ISA}" \
  PLATFORM_RISCV_ABI="${RV64_OPENSBI_ABI}" \
  FW_TEXT_START="${RV64_OPENSBI_FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${LINUX_IMAGE}" \
  FW_PAYLOAD_OFFSET="${RV64_OPENSBI_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${RV64_OPENSBI_FDT_ADDR}" \
  CROSS_COMPILE="${CROSS}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-initramfs-build.log"

FW_ELF="${OPENSBI_BUILD_DIR}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_BUILD_DIR}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || fail "missing RV64 OpenSBI+initramfs outputs"
"${CROSS}readelf" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF64' || fail "OpenSBI payload is not ELF64"
entry="$(${CROSS}readelf -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((RV64_OPENSBI_FW_TEXT_START))" ]] || fail "OpenSBI entry ${entry} drifted"
[[ "$((RV64_OPENSBI_FW_TEXT_START + RV64_OPENSBI_PAYLOAD_OFFSET))" -eq "$((RV64_LINUX_PHYS_ENTRY))" ]] || \
  fail "RV64 Linux payload address drifted"

"${CROSS}readelf" -h -l -A "${FW_ELF}" > "${EVIDENCE_DIR}/fw_payload-readelf.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "fdt=${DTB}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "entry=${entry}"
  echo "next_addr=${RV64_LINUX_PHYS_ENTRY}"
  echo "next_mode=S-mode"
  echo "fdt_addr=${RV64_OPENSBI_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
} | tee "${BUILD_DIR}/result.txt"
