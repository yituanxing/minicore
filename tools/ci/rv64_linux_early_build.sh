#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
LINUX_CACHE="${CACHE_ROOT}/rv64/linux"
ARCHIVE="${LINUX_CACHE}/linux-${RV64_LINUX_VERSION}.tar.xz"
SOURCE_DIR="${LINUX_CACHE}/linux-${RV64_LINUX_VERSION}"
OPENSBI_SOURCE="${CACHE_ROOT}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
BUILD_DIR="${ROOT_DIR}/build/rv64-linux-early"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv64-linux.dtb"
OPENSBI_BUILD_DIR="${BUILD_DIR}/opensbi"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for tool in gcc ld ar objcopy objdump readelf nm; do
  [[ -x "${CROSS}${tool}" ]] || fail "missing pinned RV64 Linux tool ${CROSS}${tool}"
done
[[ "$(${CROSS}gcc -dumpmachine)" == riscv64*linux* ]] || fail "unexpected RV64 Linux target $(${CROSS}gcc -dumpmachine)"
[[ "$(${CROSS}gcc -dumpfullversion)" == "13.3.0" ]] || fail "unexpected GCC version $(${CROSS}gcc -dumpfullversion)"

mkdir -p "${LINUX_CACHE}" "${BUILD_DIR}" "${EVIDENCE_DIR}"

if [[ -f "${ARCHIVE}" ]] && ! printf '%s  %s\n' "${RV64_LINUX_SHA256}" "${ARCHIVE}" | sha256sum -c - >/dev/null 2>&1; then
  rm -f "${ARCHIVE}"
fi
if [[ ! -f "${ARCHIVE}" ]]; then
  tmp="${ARCHIVE}.part.$$"
  rm -f "${tmp}"
  curl --http1.1 -fL --retry 8 --retry-delay 2 --retry-all-errors \
    --connect-timeout 30 --max-time 3600 \
    "${RV64_LINUX_ARCHIVE}" -o "${tmp}"
  printf '%s  %s\n' "${RV64_LINUX_SHA256}" "${tmp}" | sha256sum -c -
  mv "${tmp}" "${ARCHIVE}"
fi
printf '%s  %s\n' "${RV64_LINUX_SHA256}" "${ARCHIVE}" | sha256sum -c -

source_marker="${SOURCE_DIR}/.aethercore-linux-source-sha256"
if [[ ! -f "${source_marker}" ]] || [[ "$(cat "${source_marker}" 2>/dev/null)" != "${RV64_LINUX_SHA256}" ]]; then
  rm -rf "${SOURCE_DIR}"
  extract_root="$(mktemp -d "${LINUX_CACHE}/.linux-extract.XXXXXX")"
  trap 'rm -rf "${extract_root}"' EXIT
  tar -xJf "${ARCHIVE}" -C "${extract_root}"
  extracted="${extract_root}/linux-${RV64_LINUX_VERSION}"
  [[ -d "${extracted}" ]] || fail "unexpected Linux archive layout"
  printf '%s\n' "${RV64_LINUX_SHA256}" > "${extracted}/.aethercore-linux-source-sha256"
  mv "${extracted}" "${SOURCE_DIR}"
  rm -rf "${extract_root}"
  trap - EXIT
fi

recipe_key="$({
  printf '%s\n' \
    "recipe=${RV64_LINUX_RECIPE_VERSION}" \
    "linux=${RV64_LINUX_SHA256}" \
    "defconfig=${RV64_LINUX_DEFCONFIG}" \
    "gcc=$(${CROSS}gcc -dumpfullversion)" \
    "target=$(${CROSS}gcc -dumpmachine)" \
    "build_user=${RV64_LINUX_BUILD_USER}" \
    "build_host=${RV64_LINUX_BUILD_HOST}" \
    "build_version=${RV64_LINUX_BUILD_VERSION}" \
    "build_timestamp=${RV64_LINUX_BUILD_TIMESTAMP}" \
    "build_tz=${RV64_LINUX_BUILD_TZ}"
  sha256sum "${ROOT_DIR}/software/rv64/linux_early.env" "${ROOT_DIR}/tools/ci/rv64_linux_early_build.sh"
} | sha256sum | awk '{print $1}')"
KERNEL_CACHE="${CACHE_ROOT}/rv64/linux-build/${recipe_key}"
OBJ_DIR="${KERNEL_CACHE}/obj"
QUALIFIED="${KERNEL_CACHE}/qualified.env"

export KBUILD_BUILD_USER="${RV64_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${RV64_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${RV64_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${RV64_LINUX_BUILD_TIMESTAMP}"
export TZ="${RV64_LINUX_BUILD_TZ}"

config_is_y() {
  local symbol="$1"
  local config="$2"
  grep -qx "CONFIG_${symbol}=y" "${config}"
}

qualify_kernel() {
  local image="${OBJ_DIR}/arch/riscv/boot/Image"
  local vmlinux="${OBJ_DIR}/vmlinux"
  local config="${OBJ_DIR}/.config"
  [[ -s "${image}" && -s "${vmlinux}" && -s "${config}" && -s "${QUALIFIED}" ]] || return 1
  grep -qx "recipe_key=${recipe_key}" "${QUALIFIED}" || return 1
  grep -qx 'CONFIG_64BIT=y' "${config}" || return 1
  grep -qx 'CONFIG_MMU=y' "${config}" || return 1
  grep -qx 'CONFIG_NONPORTABLE=y' "${config}" || return 1
  ! config_is_y PORTABLE "${config}" || return 1
  grep -qx '# CONFIG_EFI is not set' "${config}" || return 1
  grep -qx '# CONFIG_RISCV_ISA_C is not set' "${config}" || return 1
  grep -qx '# CONFIG_FPU is not set' "${config}" || return 1
  grep -qx '# CONFIG_VGA_CONSOLE is not set' "${config}" || return 1
  "${CROSS}readelf" -h "${vmlinux}" | grep -q 'Class:[[:space:]]*ELF64' || return 1
  "${CROSS}readelf" -h "${vmlinux}" | grep -q 'Machine:[[:space:]]*RISC-V' || return 1
}

if ! qualify_kernel; then
  rm -rf "${KERNEL_CACHE}"
  mkdir -p "${OBJ_DIR}"

  make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
    ARCH=riscv CROSS_COMPILE="${CROSS}" \
    "${RV64_LINUX_DEFCONFIG}"

  # CONFIG_PORTABLE resolves on in the upstream RV64 defconfig path and
  # selects EFI; EFI in turn selects RISCV_ISA_C. AetherCore's frozen
  # RV64IMA/no-C profile is intentionally non-portable, so express both sides
  # of that profile explicitly before olddefconfig instead of fighting the
  # selected EFI/C leaves afterwards. PORTABLE is a hidden bool and Kconfig
  # may omit it entirely from .config when it resolves to n, so qualification
  # checks semantic "not y" rather than requiring a serialized n comment.
  # VGA_CONSOLE also defaults y on RISC-V, but AetherCore has no VGA device or
  # architecture screen_info owner; the real console for this slice is the
  # ns16550 UART. Linux source remains byte-for-byte identical to the pinned
  # upstream 6.6.143 archive.
  "${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
    -e NONPORTABLE \
    -d PORTABLE \
    -d EFI \
    -d RISCV_ISA_C \
    -d FPU \
    -d RISCV_ISA_V \
    -d VGA_CONSOLE \
    -e SERIAL_8250 \
    -e SERIAL_8250_CONSOLE \
    -e SERIAL_OF_PLATFORM

  make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
    ARCH=riscv CROSS_COMPILE="${CROSS}" olddefconfig

  for required in \
    'CONFIG_64BIT=y' \
    'CONFIG_MMU=y' \
    'CONFIG_RISCV=y' \
    'CONFIG_NONPORTABLE=y' \
    'CONFIG_SERIAL_8250=y' \
    'CONFIG_SERIAL_8250_CONSOLE=y' \
    'CONFIG_SERIAL_OF_PLATFORM=y'; do
    grep -qx "${required}" "${OBJ_DIR}/.config" || fail "resolved Linux config missing ${required}"
  done
  ! config_is_y PORTABLE "${OBJ_DIR}/.config" || fail "Linux config resolved CONFIG_PORTABLE=y and would re-select EFI"
  grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config" || fail "Linux config retained EFI"
  grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config" || fail "Linux config retained compressed ISA"
  grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config" || fail "Linux config retained FPU"
  grep -qx '# CONFIG_VGA_CONSOLE is not set' "${OBJ_DIR}/.config" || fail "Linux config retained VGA console without a platform VGA/screen_info owner"

  make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
    ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image

  VMLINUX="${OBJ_DIR}/vmlinux"
  IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
  [[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || fail "RV64 Linux build did not produce vmlinux and Image"
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || fail "vmlinux is not ELF64"
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || fail "vmlinux is not RISC-V"
  arch="$(${CROSS}readelf -A "${VMLINUX}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
  if [[ -n "${arch}" && ( "${arch}" =~ (^|_)c[0-9] || "${arch}" =~ (^|_)f[0-9] || "${arch}" =~ (^|_)d[0-9] || "${arch}" =~ (^|_)v[0-9] ) ]]; then
    fail "Linux vmlinux retained unsupported C/F/D/V extension: ${arch}"
  fi

  {
    echo "recipe_key=${recipe_key}"
    echo "linux_version=${RV64_LINUX_VERSION}"
    echo "linux_sha256=${RV64_LINUX_SHA256}"
    echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
    echo "vmlinux_sha256=$(sha256sum "${VMLINUX}" | awk '{print $1}')"
    echo "config_sha256=$(sha256sum "${OBJ_DIR}/.config" | awk '{print $1}')"
    echo "arch=${arch:-not-emitted}"
  } > "${QUALIFIED}"
  qualify_kernel || fail "fresh RV64 Linux cache failed post-build qualification"
else
  echo "RV64 Linux qualified cache hit: ${recipe_key}"
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${EVIDENCE_DIR}"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
VMLINUX="${OBJ_DIR}/vmlinux"
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
cp "${QUALIFIED}" "${EVIDENCE_DIR}/qualified.env"
"${CROSS}readelf" -h -A "${VMLINUX}" > "${EVIDENCE_DIR}/vmlinux-readelf.txt"
file "${VMLINUX}" "${IMAGE}" > "${EVIDENCE_DIR}/files.txt"
sha256sum "${VMLINUX}" "${IMAGE}" "${OBJ_DIR}/.config" > "${EVIDENCE_DIR}/kernel-sha256.txt"

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv64-linux-dtb.txt" \
  --isa "${RV64_OPENSBI_ISA}" \
  --mmu "${RV64_OPENSBI_MMU}" \
  --bootargs "${RV64_LINUX_BOOTARGS}"

if [[ ! -d "${OPENSBI_SOURCE}/.git" ]]; then
  rm -rf "${OPENSBI_SOURCE}"
  mkdir -p "${OPENSBI_SOURCE}"
  git -C "${OPENSBI_SOURCE}" init -q
  git -C "${OPENSBI_SOURCE}" remote add origin "${RV64_OPENSBI_REPOSITORY}"
  git -C "${OPENSBI_SOURCE}" fetch --depth=1 origin "${RV64_OPENSBI_COMMIT}"
  git -C "${OPENSBI_SOURCE}" checkout -q --detach FETCH_HEAD
fi
[[ "$(git -C "${OPENSBI_SOURCE}" rev-parse HEAD)" == "${RV64_OPENSBI_COMMIT}" ]] || fail "cached OpenSBI commit drifted"
git -C "${OPENSBI_SOURCE}" diff --quiet --ignore-submodules -- || fail "cached OpenSBI source tree is dirty"

mkdir -p "${OPENSBI_BUILD_DIR}"
make -C "${OPENSBI_SOURCE}" \
  O="${OPENSBI_BUILD_DIR}" \
  PLATFORM="${RV64_OPENSBI_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${RV64_OPENSBI_XLEN}" \
  PLATFORM_RISCV_ISA="${RV64_OPENSBI_ISA}" \
  PLATFORM_RISCV_ABI="${RV64_OPENSBI_ABI}" \
  FW_TEXT_START="${RV64_OPENSBI_FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${IMAGE}" \
  FW_PAYLOAD_OFFSET="${RV64_OPENSBI_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${RV64_OPENSBI_FDT_ADDR}" \
  CROSS_COMPILE="${CROSS}" \
  -j"${JOBS}" > "${BUILD_DIR}/opensbi-linux-build.log" 2>&1

FW_ELF="${OPENSBI_BUILD_DIR}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_BUILD_DIR}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || fail "missing RV64 OpenSBI+Linux payload outputs"
"${CROSS}readelf" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF64' || fail "OpenSBI payload is not ELF64"
entry="$(${CROSS}readelf -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((RV64_OPENSBI_FW_TEXT_START))" ]] || fail "OpenSBI entry ${entry} drifted"
[[ "$((RV64_OPENSBI_FW_TEXT_START + RV64_OPENSBI_PAYLOAD_OFFSET))" -eq "$((RV64_LINUX_PHYS_ENTRY))" ]] || fail "RV64 Linux payload address drifted"

"${CROSS}readelf" -h -l -A "${FW_ELF}" > "${EVIDENCE_DIR}/fw_payload-readelf.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" "${IMAGE}" > "${EVIDENCE_DIR}/payload-sha256.txt"

{
  echo "RV64_LINUX_EARLY_BUILD_RESULT: status=PASS"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_source_sha256=${RV64_LINUX_SHA256}"
  echo "linux_recipe=${RV64_LINUX_RECIPE_VERSION}"
  echo "kernel_recipe_key=${recipe_key}"
  echo "kernel_image=${IMAGE}"
  echo "kernel_image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
  echo "opensbi_version=${RV64_OPENSBI_VERSION}"
  echo "opensbi_commit=${RV64_OPENSBI_COMMIT}"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "firmware_bin_sha256=$(sha256sum "${FW_BIN}" | awk '{print $1}')"
  echo "entry=${entry}"
  echo "next_addr=${RV64_LINUX_PHYS_ENTRY}"
  echo "next_mode=S-mode"
  echo "fdt=${DTB}"
  echo "fdt_addr=${RV64_OPENSBI_FDT_ADDR}"
  echo "bootargs=${RV64_LINUX_BOOTARGS}"
  echo "milestone=${RV64_LINUX_MILESTONE}"
} | tee "${BUILD_DIR}/result.txt"
