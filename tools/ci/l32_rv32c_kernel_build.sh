#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32/linux-freeze.env"

# RV32C Linux qualification is a peer of the frozen RV32IMA lane. Never write
# its objects or evidence into build/l32-linux: that directory is an immutable
# regression oracle for the historical non-C system.
PROFILE=rv32imac
LINUX_ISA=rv32imac_zicsr_zifencei
DTB_ISA=rv32imac_zicsr_zifencei_sstc
BUILD_DIR="${ROOT_DIR}/build/l32-linux-${PROFILE}"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
OPENSBI_OUT="${BUILD_DIR}/opensbi"
DTB="${BUILD_DIR}/aethercore-rv32imac.dtb"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32"
LINUX_CACHE="${CACHE_ROOT}/linux"
LINUX_ARCHIVE_PATH="${LINUX_CACHE}/linux-${LINUX_VERSION}.tar.xz"
LINUX_SOURCE="${LINUX_CACHE}/linux-${LINUX_VERSION}"
OPENSBI_SOURCE="${CACHE_ROOT}/opensbi/${OPENSBI_COMMIT}"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"
FW_TEXT_START=0x80000000
FW_PAYLOAD_OFFSET=0x400000
FW_PAYLOAD_FDT_ADDR=0x87f00000
BOOTARGS="earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200"
INPUT_KEY_FILE="${BUILD_DIR}/.aethercore-input-key"

mkdir -p "${BUILD_DIR}" "${EVIDENCE_DIR}" "${LINUX_CACHE}" "${CACHE_ROOT}/opensbi"

for tool in curl git tar sha256sum file make python3 \
  "${L32_CROSS_COMPILE_PREFIX}gcc" \
  "${L32_CROSS_COMPILE_PREFIX}readelf" \
  "${L32_CROSS_COMPILE_PREFIX}objdump"; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "ERROR: missing required RV32C Linux qualification tool: ${tool}" >&2
    exit 20
  }
done

fetch_linux_source() {
  if [[ -f "${LINUX_ARCHIVE_PATH}" ]]; then
    if ! printf '%s  %s\n' "${LINUX_SHA256}" "${LINUX_ARCHIVE_PATH}" | sha256sum -c - >/dev/null 2>&1; then
      rm -f "${LINUX_ARCHIVE_PATH}"
    fi
  fi
  if [[ ! -f "${LINUX_ARCHIVE_PATH}" ]]; then
    local tmp="${LINUX_ARCHIVE_PATH}.part.$$"
    rm -f "${tmp}"
    curl --http1.1 -fL --retry 8 --retry-delay 2 --retry-all-errors \
      --connect-timeout 30 --max-time 3600 "${LINUX_ARCHIVE}" -o "${tmp}"
    printf '%s  %s\n' "${LINUX_SHA256}" "${tmp}" | sha256sum -c -
    mv "${tmp}" "${LINUX_ARCHIVE_PATH}"
  fi

  local marker="${LINUX_SOURCE}/.aethercore-linux-source-sha256"
  if [[ ! -f "${marker}" ]] || [[ "$(cat "${marker}" 2>/dev/null)" != "${LINUX_SHA256}" ]]; then
    rm -rf "${LINUX_SOURCE}"
    local extract_root
    extract_root="$(mktemp -d "${LINUX_CACHE}/.linux-rv32c-extract.XXXXXX")"
    tar -xJf "${LINUX_ARCHIVE_PATH}" -C "${extract_root}"
    [[ -d "${extract_root}/linux-${LINUX_VERSION}" ]] || {
      rm -rf "${extract_root}"
      echo "ERROR: unexpected Linux archive layout" >&2
      exit 21
    }
    printf '%s\n' "${LINUX_SHA256}" > "${extract_root}/linux-${LINUX_VERSION}/.aethercore-linux-source-sha256"
    mv "${extract_root}/linux-${LINUX_VERSION}" "${LINUX_SOURCE}"
    rm -rf "${extract_root}"
  fi
}

fetch_opensbi_source() {
  if [[ ! -d "${OPENSBI_SOURCE}/.git" ]]; then
    rm -rf "${OPENSBI_SOURCE}"
    mkdir -p "${OPENSBI_SOURCE}"
    git -C "${OPENSBI_SOURCE}" init -q
    git -C "${OPENSBI_SOURCE}" remote add origin "${OPENSBI_REPOSITORY}"
    git -C "${OPENSBI_SOURCE}" fetch --depth=1 origin "${OPENSBI_COMMIT}"
    git -C "${OPENSBI_SOURCE}" checkout -q --detach FETCH_HEAD
  fi
  local observed
  observed="$(git -C "${OPENSBI_SOURCE}" rev-parse HEAD)"
  [[ "${observed}" == "${OPENSBI_COMMIT}" ]] || {
    echo "ERROR: cached OpenSBI commit ${observed} != ${OPENSBI_COMMIT}" >&2
    exit 22
  }
  git -C "${OPENSBI_SOURCE}" diff --quiet --ignore-submodules -- || {
    echo "ERROR: cached OpenSBI source tree is dirty" >&2
    exit 22
  }
}

count_compressed() {
  local elf="$1"
  local upper_bound="${2:-}"
  "${L32_CROSS_COMPILE_PREFIX}objdump" -d "${elf}" | python3 -c '
import re, sys
limit = int(sys.argv[1], 0) if sys.argv[1] else None
count = 0
for line in sys.stdin:
    match = re.match(r"^\s*([0-9a-f]+):\s+([0-9a-f]{4})\s", line, re.I)
    if match and (limit is None or int(match.group(1), 16) < limit):
        count += 1
print(count)
' "${upper_bound}"
}

count_mnemonic_family() {
  local elf="$1"
  local pattern="$2"
  "${L32_CROSS_COMPILE_PREFIX}objdump" -d "${elf}" | python3 -c '
import re, sys
pattern = re.compile(sys.argv[1], re.I)
count = 0
for line in sys.stdin:
    match = re.match(r"^\s*[0-9a-f]+:\s+[0-9a-f]+\s+([A-Za-z0-9_.]+)", line)
    if match and pattern.fullmatch(match.group(1)):
        count += 1
print(count)
' "${pattern}"
}

extract_arch() {
  "${L32_CROSS_COMPILE_PREFIX}readelf" -A "$1" \
    | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1
}

extract_flags() {
  "${L32_CROSS_COMPILE_PREFIX}readelf" -h "$1" \
    | sed -n 's/^[[:space:]]*Flags:[[:space:]]*//p' | head -n 1
}

require_c_arch() {
  local label="$1" arch="$2"
  [[ "${arch}" == rv32i* && "${arch}" == *"_m"* && "${arch}" == *"_a"* ]] || {
    echo "ERROR: ${label} lost required RV32IMA profile: ${arch}" >&2
    exit 23
  }
  [[ "${arch}" =~ _c[0-9] ]] || {
    echo "ERROR: ${label} does not advertise C: ${arch}" >&2
    exit 23
  }
  if [[ "${arch}" =~ _f[0-9] || "${arch}" =~ _d[0-9] || "${arch}" =~ _v[0-9] ]]; then
    echo "ERROR: ${label} retained forbidden F/D/V extension: ${arch}" >&2
    exit 23
  fi
}

fetch_linux_source
fetch_opensbi_source

input_key="$({
  sha256sum \
    "${ROOT_DIR}/software/l32/manifest.env" \
    "${ROOT_DIR}/software/l32/linux-freeze.env" \
    "${ROOT_DIR}/tools/ci/l32_rv32c_kernel_build.sh" \
    "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
    "${ROOT_DIR}/tools/ensure_l32_riscv32_linux_gcc.sh"
  printf 'profile=%s\nlinux_isa=%s\ndtb_isa=%s\nfw_payload_fdt_addr=%s\nbootargs=%s\n' \
    "${PROFILE}" "${LINUX_ISA}" "${DTB_ISA}" "${FW_PAYLOAD_FDT_ADDR}" "${BOOTARGS}"
} | sha256sum | awk '{print $1}')"

if [[ ! -f "${INPUT_KEY_FILE}" ]] || [[ "$(cat "${INPUT_KEY_FILE}" 2>/dev/null)" != "${input_key}" ]]; then
  rm -rf "${OBJ_DIR}" "${OPENSBI_OUT}" "${EVIDENCE_DIR}"
  mkdir -p "${OBJ_DIR}" "${EVIDENCE_DIR}"
  printf '%s\n' "${input_key}" > "${INPUT_KEY_FILE}"
else
  mkdir -p "${OBJ_DIR}" "${EVIDENCE_DIR}"
fi

export KBUILD_BUILD_USER="${L32_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${L32_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${L32_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${L32_LINUX_BUILD_TIMESTAMP}"
export TZ="${L32_LINUX_BUILD_TZ}"

make -C "${LINUX_SOURCE}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  "${LINUX_RV32_DEFCONFIG}" 2>&1 | tee "${BUILD_DIR}/linux-config.log"

"${LINUX_SOURCE}/scripts/config" --file "${OBJ_DIR}/.config" \
  -d EFI \
  -e RISCV_ISA_C \
  -d FPU \
  -d VGA_CONSOLE

make -C "${LINUX_SOURCE}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" olddefconfig \
  2>&1 | tee -a "${BUILD_DIR}/linux-config.log"

grep -qx 'CONFIG_32BIT=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_MMU=y' "${OBJ_DIR}/.config"
grep -qx 'CONFIG_RISCV_ISA_C=y' "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux Kconfig did not retain RISCV_ISA_C" >&2
  exit 24
}
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config"
grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config"

make -C "${LINUX_SOURCE}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" Image 2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || {
  echo "ERROR: RV32IMAC Linux build did not produce vmlinux/Image" >&2
  exit 25
}

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -A "${VMLINUX}" \
  | tee "${EVIDENCE_DIR}/vmlinux-readelf.txt"
# Linux's final vmlinux intentionally does not preserve Tag_RISCV_arch. Its
# stable linked-ELF contract is e_flags plus the instructions actually present.
linux_flags="$(extract_flags "${VMLINUX}")"
[[ "${linux_flags}" == *RVC* && "${linux_flags}" == *"soft-float ABI"* ]] || {
  echo "ERROR: Linux vmlinux did not retain RVC soft-float ELF flags: ${linux_flags}" >&2
  exit 26
}
linux_compressed="$(count_compressed "${VMLINUX}")"
linux_m_instructions="$(count_mnemonic_family "${VMLINUX}" '^(mul|mulh|mulhsu|mulhu|div|divu|rem|remu)$')"
linux_atomic_instructions="$(count_mnemonic_family "${VMLINUX}" '^(lr|sc|amo[a-z]+)\.w(\.[a-z]+)?$')"
[[ "${linux_compressed}" -gt 0 ]] || {
  echo "ERROR: Linux RVC flag is set but no 16-bit instruction encoding was found" >&2
  exit 26
}
[[ "${linux_m_instructions}" -gt 0 ]] || {
  echo "ERROR: Linux contains no real RV32M instruction" >&2
  exit 26
}
[[ "${linux_atomic_instructions}" -gt 0 ]] || {
  echo "ERROR: Linux contains no real RV32A instruction" >&2
  exit 26
}
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/linux.config"

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --isa "${DTB_ISA}" \
  --bootargs "${BOOTARGS}" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv32imac-dtb.txt"
grep -qx "isa=${DTB_ISA}" "${EVIDENCE_DIR}/aethercore-rv32imac-dtb.txt"
grep -Fxq "bootargs=${BOOTARGS}" "${EVIDENCE_DIR}/aethercore-rv32imac-dtb.txt"

rm -rf "${OPENSBI_OUT}"
mkdir -p "${OPENSBI_OUT}"
make -C "${OPENSBI_SOURCE}" \
  O="${OPENSBI_OUT}" \
  PLATFORM="${L32_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${L32_XLEN}" \
  PLATFORM_RISCV_ISA="${LINUX_ISA}" \
  PLATFORM_RISCV_ABI="${OPENSBI_RV32_ABI}" \
  FW_TEXT_START="${FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${IMAGE}" \
  FW_PAYLOAD_OFFSET="${FW_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${FW_PAYLOAD_FDT_ADDR}" \
  CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-build.log"

FW_ELF="${OPENSBI_OUT}/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_OUT}/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: RV32IMAC OpenSBI+Linux payload was not produced" >&2
  exit 27
}

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -l -A "${FW_ELF}" \
  | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
opensbi_arch="$(extract_arch "${FW_ELF}")"
require_c_arch OpenSBI "${opensbi_arch}"
# fw_payload.elf contains the raw Linux payload. Count only the firmware-owned
# address range so Linux code cannot satisfy the OpenSBI C qualification.
opensbi_compressed="$(count_compressed "${FW_ELF}" "${L32_LINUX_PHYS_ENTRY}")"
[[ "${opensbi_compressed}" -gt 0 ]] || {
  echo "ERROR: OpenSBI advertises C but no firmware-owned 16-bit instruction was found" >&2
  exit 28
}

entry="$(${L32_CROSS_COMPILE_PREFIX}readelf -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((FW_TEXT_START))" ]] || {
  echo "ERROR: OpenSBI entry ${entry} != ${FW_TEXT_START}" >&2
  exit 29
}
[[ "$((FW_TEXT_START + FW_PAYLOAD_OFFSET))" -eq "$((L32_LINUX_PHYS_ENTRY))" ]] || {
  echo "ERROR: Linux payload physical-entry contract drifted" >&2
  exit 29
}

sha256sum "${VMLINUX}" "${IMAGE}" "${FW_ELF}" "${FW_BIN}" "${DTB}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV32C_LINUX_KERNEL_RESULT: status=PASS"
  echo "profile=${PROFILE}"
  echo "linux_version=${LINUX_VERSION}"
  echo "linux_elf_flags=${linux_flags}"
  echo "linux_compressed_instructions=${linux_compressed}"
  echo "linux_m_instructions=${linux_m_instructions}"
  echo "linux_atomic_instructions=${linux_atomic_instructions}"
  echo "linux_vmlinux_sha256=$(sha256sum "${VMLINUX}" | awk '{print $1}')"
  echo "linux_image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
  echo "opensbi_version=${OPENSBI_VERSION}"
  echo "opensbi_commit=${OPENSBI_COMMIT}"
  echo "opensbi_arch=${opensbi_arch}"
  echo "opensbi_compressed_instructions=${opensbi_compressed}"
  echo "fw_payload_sha256=$(sha256sum "${FW_ELF}" | awk '{print $1}')"
  echo "fw_payload_bin_sha256=$(sha256sum "${FW_BIN}" | awk '{print $1}')"
  echo "dtb_isa=${DTB_ISA}"
  echo "dtb_sha256=$(sha256sum "${DTB}" | awk '{print $1}')"
  echo "fw_payload_fdt_addr=${FW_PAYLOAD_FDT_ADDR}"
  echo "bootargs=${BOOTARGS}"
  echo "input_key=${input_key}"
} | tee "${BUILD_DIR}/result.txt"
