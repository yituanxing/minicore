#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
OPENSBI_SOURCE="${CACHE_ROOT}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
LINUX_BUILD_DIR="${RV64_PAYLOAD_LINUX_BUILD_DIR:-${ROOT_DIR}/build/rv64-linux-initramfs}"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
BUILD_DIR="${RV64_PAYLOAD_BUILD_DIR:-${ROOT_DIR}/build/rv64-minimal-init-boot}"
OPENSBI_BUILD_DIR="${BUILD_DIR}/opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTS="${BUILD_DIR}/aethersoc-v0-initramfs.dts"
DTB="${BUILD_DIR}/aethercore-rv64-initramfs.dtb"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"
# Freeze-path bootargs: prove the same userspace/interrupt workload through the
# kernel's default legacy PTY registration path, with no exploration shortcut.
BOOTARGS="${RV64_PAYLOAD_BOOTARGS:-${RV64_LINUX_BOOTARGS} rdinit=/init}"
PAYLOAD_ISA="${RV64_PAYLOAD_ISA:-${RV64_OPENSBI_ISA}}"
PAYLOAD_ABI="${RV64_PAYLOAD_ABI:-${RV64_OPENSBI_ABI}}"
LINUX_QUALIFIER="${RV64_PAYLOAD_LINUX_QUALIFIER:-RV64_MINIMAL_INITRAMFS_BUILD_RESULT}"
PAYLOAD_RESULT_TAG="${RV64_PAYLOAD_RESULT_TAG:-RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT}"
PAYLOAD_INPUT_TAG="${RV64_PAYLOAD_INPUT_TAG:-RV64_MINIMAL_INIT_PAYLOAD_INPUT}"
PAYLOAD_ENABLE_SPI_SD="${RV64_PAYLOAD_ENABLE_SPI_SD:-0}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

grep -qx "${LINUX_QUALIFIER}: status=PASS" "${LINUX_BUILD_DIR}/result.txt" || \
  fail "RV64 payload Linux Image is not qualified (${LINUX_QUALIFIER})"
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

command -v dtc >/dev/null 2>&1 || fail "device-tree-compiler (dtc) is required for RV64 AetherSoC DTS"
chmod +x "${ROOT_DIR}/mill"
spi_dts_args=()
case "${PAYLOAD_ENABLE_SPI_SD}" in
  0) ;;
  1)
    spi_dts_args+=(--spi-sd)
    if [[ -n "${RV64_PAYLOAD_SPI_CLOCK_HZ:-}" ]]; then
      [[ "${RV64_PAYLOAD_SPI_CLOCK_HZ}" =~ ^[0-9]+$ ]] || fail 'non-integer SPI clock Hz'
      spi_dts_args+=(--spi-clock-hz "${RV64_PAYLOAD_SPI_CLOCK_HZ}")
    fi
    if [[ -n "${RV64_PAYLOAD_SPI_MAX_HZ:-}" ]]; then
      [[ "${RV64_PAYLOAD_SPI_MAX_HZ}" =~ ^[0-9]+$ ]] || fail 'non-integer SPI max Hz'
      spi_dts_args+=(--spi-max-hz "${RV64_PAYLOAD_SPI_MAX_HZ}")
    fi
    ;;
  *) fail "invalid RV64_PAYLOAD_ENABLE_SPI_SD=${PAYLOAD_ENABLE_SPI_SD}" ;;
esac
"${ROOT_DIR}/mill" aethercore.runMain aethercore.EmitAetherSoCDts \
  --output "${DTS}" \
  --isa "${PAYLOAD_ISA}" \
  --mmu "${RV64_OPENSBI_MMU}" \
  --bootargs "${BOOTARGS}" \
  "${spi_dts_args[@]}" \
  | tee "${EVIDENCE_DIR}/aethersoc-rv64-initramfs-dts.txt"
dtc -I dts -O dtb -o "${DTB}" "${DTS}"

{
  echo "${PAYLOAD_INPUT_TAG}: status=READY"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "opensbi_commit=${RV64_OPENSBI_COMMIT}"
  echo "fw_text_start=${RV64_OPENSBI_FW_TEXT_START}"
  echo "fw_payload_offset=${RV64_OPENSBI_PAYLOAD_OFFSET}"
  echo "fw_payload_address=${RV64_OPENSBI_PAYLOAD_ADDR}"
  echo "fw_payload_fdt_addr=${RV64_OPENSBI_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
  echo "spi_sd=${PAYLOAD_ENABLE_SPI_SD}"
  echo "isa=${PAYLOAD_ISA}"
  echo "abi=${PAYLOAD_ABI}"
} | tee "${EVIDENCE_DIR}/inputs.txt"

make -C "${OPENSBI_SOURCE}" \
  O="${OPENSBI_BUILD_DIR}" \
  PLATFORM="${RV64_OPENSBI_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${RV64_OPENSBI_XLEN}" \
  PLATFORM_RISCV_ISA="${PAYLOAD_ISA}" \
  PLATFORM_RISCV_ABI="${PAYLOAD_ABI}" \
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
# Firmware is loaded at 0x80000000, but the DTB is placed at 0x87f00000.
# The embedded Debian rootfs may be tens of MiB: never silently overlap DTB.
firmware_bytes="$(stat -c %s "${FW_BIN}")"
[[ "${firmware_bytes}" -lt "$((RV64_OPENSBI_FDT_ADDR - RV64_OPENSBI_FW_TEXT_START))" ]] || \
  fail "firmware payload (${firmware_bytes} B) overlaps the pinned DTB address"
"${CROSS}readelf" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF64' || fail "OpenSBI payload is not ELF64"
entry="$(${CROSS}readelf -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((RV64_OPENSBI_FW_TEXT_START))" ]] || fail "OpenSBI entry ${entry} drifted"
[[ "$((RV64_OPENSBI_FW_TEXT_START + RV64_OPENSBI_PAYLOAD_OFFSET))" -eq "$((RV64_LINUX_PHYS_ENTRY))" ]] || \
  fail "RV64 Linux payload address drifted"

"${CROSS}readelf" -h -l -A "${FW_ELF}" > "${EVIDENCE_DIR}/fw_payload-readelf.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "${PAYLOAD_RESULT_TAG}: status=PASS"
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
  echo "spi_sd=${PAYLOAD_ENABLE_SPI_SD}"
  echo "isa=${PAYLOAD_ISA}"
  echo "abi=${PAYLOAD_ABI}"
  echo "firmware_bytes=${firmware_bytes}"
} | tee "${BUILD_DIR}/result.txt"
