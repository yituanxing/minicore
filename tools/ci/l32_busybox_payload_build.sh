#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32"
SOURCE_DIR="${CACHE_ROOT}/opensbi/${OPENSBI_COMMIT}"
LINUX_BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"
LINUX_IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
BUILD_DIR="${L32_USERSPACE_PAYLOAD_BUILD_DIR}"
OPENSBI_BUILD_DIR="${BUILD_DIR}/opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv32-busybox.dtb"
DTB_SUMMARY="${EVIDENCE_DIR}/aethercore-rv32-busybox-dtb.txt"
READELF="${L32_CROSS_COMPILE_PREFIX}readelf"
OBJDUMP="${L32_CROSS_COMPILE_PREFIX}objdump"
JOBS="${L32_JOBS:-$(nproc)}"
FW_TEXT_START="0x80000000"
FW_PAYLOAD_OFFSET="0x00400000"
FW_PAYLOAD_ADDRESS="0x80400000"
FW_PAYLOAD_FDT_ADDR="0x87f00000"
BOOTARGS="earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200 rdinit=/init"

mkdir -p "${CACHE_ROOT}/opensbi" "${BUILD_DIR}" "${EVIDENCE_DIR}"

grep -qx 'L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS' "${LINUX_BUILD_DIR}/result.txt" || {
  echo "ERROR: BusyBox initramfs Linux image is not qualified" >&2
  exit 20
}
grep -qx "profile=${L32_USERSPACE_PROFILE}" "${LINUX_BUILD_DIR}/result.txt" || {
  echo "ERROR: BusyBox initramfs profile does not match payload profile ${L32_USERSPACE_PROFILE}" >&2
  exit 20
}
[[ -s "${LINUX_IMAGE}" ]] || { echo "ERROR: BusyBox initramfs Linux Image is missing" >&2; exit 20; }
actual_image_sha="$(sha256sum "${LINUX_IMAGE}" | awk '{print $1}')"
recorded_image_sha="$(sed -n 's/^image_sha256=//p' "${LINUX_BUILD_DIR}/result.txt" | head -n 1)"
[[ -n "${recorded_image_sha}" && "${actual_image_sha}" == "${recorded_image_sha}" ]] || {
  echo "ERROR: BusyBox initramfs Image hash drifted" >&2
  exit 21
}

for tool in "${L32_CROSS_COMPILE_PREFIX}gcc" "${READELF}" "${OBJDUMP}"; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "ERROR: provision the pinned L32 Linux toolchain first" >&2
    exit 22
  }
done

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --isa "${L32_USERSPACE_DTB_ISA}" \
  --output "${DTB}" \
  --summary "${DTB_SUMMARY}" \
  --bootargs "${BOOTARGS}"
grep -qx "isa=${L32_USERSPACE_DTB_ISA}" "${DTB_SUMMARY}"
grep -Fxq "bootargs=${BOOTARGS}" "${DTB_SUMMARY}"

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
  echo "L32_BUSYBOX_PAYLOAD_INPUT: status=READY"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "linux_version=${LINUX_VERSION}"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "opensbi_commit=${OPENSBI_COMMIT}"
  echo "opensbi_isa=${L32_USERSPACE_OPENSBI_ISA}"
  echo "dtb_isa=${L32_USERSPACE_DTB_ISA}"
  echo "fw_text_start=${FW_TEXT_START}"
  echo "fw_payload_offset=${FW_PAYLOAD_OFFSET}"
  echo "fw_payload_address=${FW_PAYLOAD_ADDRESS}"
  echo "fw_payload_fdt_addr=${FW_PAYLOAD_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
  "${L32_CROSS_COMPILE_PREFIX}gcc" --version | head -n 1
} | tee "${EVIDENCE_DIR}/inputs.txt"

make -C "${SOURCE_DIR}" \
  O="${OPENSBI_BUILD_DIR}" \
  PLATFORM="${L32_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${L32_XLEN}" \
  PLATFORM_RISCV_ISA="${L32_USERSPACE_OPENSBI_ISA}" \
  PLATFORM_RISCV_ABI="${OPENSBI_RV32_ABI}" \
  FW_TEXT_START="${FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${LINUX_IMAGE}" \
  FW_PAYLOAD_OFFSET="${FW_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${FW_PAYLOAD_FDT_ADDR}" \
  CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-busybox-build.log"

FW_ELF="${OPENSBI_BUILD_DIR}/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_BUILD_DIR}/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: missing OpenSBI+BusyBox Linux payload outputs" >&2
  exit 25
}

"${READELF}" -h -l -A "${FW_ELF}" | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
opensbi_arch="$(${READELF} -A "${FW_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
[[ -n "${opensbi_arch}" ]] || { echo "ERROR: OpenSBI payload lost Tag_RISCV_arch" >&2; exit 26; }
opensbi_flags="$(${READELF} -h "${FW_ELF}" | sed -n 's/^[[:space:]]*Flags:[[:space:]]*//p' | head -n 1)"
[[ -n "${opensbi_flags}" ]] || { echo "ERROR: OpenSBI payload lost ELF flags" >&2; exit 26; }
firmware_compressed="$(${OBJDUMP} -d "${FW_ELF}" | python3 -c '
import re, sys
limit = int(sys.argv[1], 0)
count = 0
for line in sys.stdin:
    match = re.match(r"^\s*([0-9a-f]+):\s+([0-9a-f]{4})\s", line, re.I)
    if match and int(match.group(1), 16) < limit:
        count += 1
print(count)
' "${FW_PAYLOAD_ADDRESS}")"
if [[ "${L32_USERSPACE_REQUIRE_C}" -eq 1 ]]; then
  [[ "${opensbi_arch}" =~ _c[0-9] && "${opensbi_flags}" == *RVC* && "${firmware_compressed}" -gt 0 ]] || {
    echo "ERROR: RV32IMAC OpenSBI payload lacks firmware-owned compressed code: arch=${opensbi_arch} flags=${opensbi_flags} count=${firmware_compressed}" >&2
    exit 26
  }
else
  # As with linked vmlinux, executable firmware sections can contain data or
  # alignment bytes that objdump renders as four-hex-digit lines even when C
  # is disabled. The sound negative ISA oracle is the declared architecture
  # plus EF_RISCV_RVC; the frozen RV32IMA runtime independently rejects real C
  # execution. Keep the halfword count only as diagnostic evidence.
  [[ ! "${opensbi_arch}" =~ _c[0-9] && "${opensbi_flags}" != *RVC* ]] || {
    echo "ERROR: historical RV32IMA OpenSBI payload unexpectedly advertises RVC: arch=${opensbi_arch} flags=${opensbi_flags} count=${firmware_compressed}" >&2
    exit 26
  }
fi

file "${FW_ELF}" "${FW_BIN}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/files.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" "${LINUX_IMAGE}" | tee "${EVIDENCE_DIR}/sha256.txt"
entry="$(${READELF} -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((FW_TEXT_START))" ]] || exit 27
[[ "$((FW_TEXT_START + FW_PAYLOAD_OFFSET))" -eq "$((FW_PAYLOAD_ADDRESS))" ]] || exit 28

{
  echo "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "require_c=${L32_USERSPACE_REQUIRE_C}"
  echo "opensbi_isa=${L32_USERSPACE_OPENSBI_ISA}"
  echo "opensbi_arch=${opensbi_arch}"
  echo "opensbi_elf_flags=${opensbi_flags}"
  echo "opensbi_firmware_compressed_instructions=${firmware_compressed}"
  echo "dtb_isa=${L32_USERSPACE_DTB_ISA}"
  echo "firmware=${FW_ELF}"
  echo "firmware_sha256=$(sha256sum "${FW_ELF}" | awk '{print $1}')"
  echo "firmware_bin=${FW_BIN}"
  echo "firmware_bin_sha256=$(sha256sum "${FW_BIN}" | awk '{print $1}')"
  echo "fdt=${DTB}"
  echo "fdt_sha256=$(sha256sum "${DTB}" | awk '{print $1}')"
  echo "linux_image=${LINUX_IMAGE}"
  echo "linux_image_sha256=${actual_image_sha}"
  echo "entry=${entry}"
  echo "next_addr=${FW_PAYLOAD_ADDRESS}"
  echo "next_mode=S-mode"
  echo "fdt_addr=${FW_PAYLOAD_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
} | tee "${BUILD_DIR}/result.txt"
