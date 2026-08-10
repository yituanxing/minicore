#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32/linux-freeze.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/linux"
ARCHIVE="${CACHE_ROOT}/linux-${LINUX_VERSION}.tar.xz"
SOURCE_DIR="${CACHE_ROOT}/linux-${LINUX_VERSION}"
BUILD_DIR="${ROOT_DIR}/build/l32-linux"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
OBJ_DIR="${BUILD_DIR}/obj"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"

# The canonical base is a recipe-derived artifact, not an incremental Kbuild
# checkpoint. Fixed neutral metadata plus a clean object tree keep a cache
# repair independent of whatever a self-hosted runner built previously.
export KBUILD_BUILD_USER="${L32_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${L32_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${L32_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${L32_LINUX_BUILD_TIMESTAMP}"
export TZ="${L32_LINUX_BUILD_TZ}"

mkdir -p "${CACHE_ROOT}" "${BUILD_DIR}"

command -v "${L32_CROSS_COMPILE_PREFIX}gcc" >/dev/null 2>&1 || {
  echo "ERROR: provision the pinned L32 Linux toolchain first" >&2
  exit 20
}

if [[ -f "${ARCHIVE}" ]]; then
  if ! printf '%s  %s\n' "${LINUX_SHA256}" "${ARCHIVE}" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "${ARCHIVE}"
  fi
fi

if [[ ! -f "${ARCHIVE}" ]]; then
  tmp="${ARCHIVE}.part.$$"
  rm -f "${tmp}"
  curl --http1.1 -fL --retry 8 --retry-delay 2 --retry-all-errors \
    --connect-timeout 30 --max-time 3600 \
    "${LINUX_ARCHIVE}" -o "${tmp}"
  printf '%s  %s\n' "${LINUX_SHA256}" "${tmp}" | sha256sum -c -
  mv "${tmp}" "${ARCHIVE}"
fi
printf '%s  %s\n' "${LINUX_SHA256}" "${ARCHIVE}" | sha256sum -c -

marker="${SOURCE_DIR}/.aethercore-linux-source-sha256"
if [[ ! -f "${marker}" ]] || [[ "$(cat "${marker}" 2>/dev/null)" != "${LINUX_SHA256}" ]]; then
  rm -rf "${SOURCE_DIR}"
  extract_root="$(mktemp -d "${CACHE_ROOT}/.linux-extract.XXXXXX")"
  trap 'rm -rf "${extract_root}"' EXIT
  tar -xJf "${ARCHIVE}" -C "${extract_root}"
  extracted="${extract_root}/linux-${LINUX_VERSION}"
  [[ -d "${extracted}" ]] || { echo "ERROR: unexpected Linux archive layout" >&2; exit 21; }
  printf '%s\n' "${LINUX_SHA256}" > "${extracted}/.aethercore-linux-source-sha256"
  mv "${extracted}" "${SOURCE_DIR}"
  rm -rf "${extract_root}"
  trap - EXIT
fi

# A validated cache avoids this build entirely. Once a rebuild is required,
# start from a clean Kbuild tree; never try to repair the canonical root by
# relinking stale objects from a previous workflow or build identity.
rm -rf "${OBJ_DIR}" "${EVIDENCE_DIR}"
mkdir -p "${OBJ_DIR}" "${EVIDENCE_DIR}"
{
  echo "recipe_version=${L32_LINUX_RECIPE_VERSION}"
  echo "linux_sha256=${LINUX_SHA256}"
  echo "toolchain_version=${L32_TOOLCHAIN_VERSION}"
  echo "cross_compile=${L32_CROSS_COMPILE_PREFIX}"
  echo "kbuild_user=${KBUILD_BUILD_USER}"
  echo "kbuild_host=${KBUILD_BUILD_HOST}"
  echo "kbuild_version=${KBUILD_BUILD_VERSION}"
  echo "kbuild_timestamp=${KBUILD_BUILD_TIMESTAMP}"
  echo "kbuild_tz=${TZ}"
} > "${EVIDENCE_DIR}/build-inputs.txt"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  "${LINUX_RV32_DEFCONFIG}" \
  2>&1 | tee "${BUILD_DIR}/config.log"

# Keep the canonical Linux base inside the AetherCore ISA/platform contract.
# RISC-V EFI selects RISCV_ISA_C in Linux 6.6, so disable the unused UEFI
# runtime path first. AetherCore exposes only the NS16550 serial console in
# this checkpoint, so the generic VGA text console is intentionally off.
"${SOURCE_DIR}/scripts/config" --file "${OBJ_DIR}/.config" \
  -d EFI \
  -d RISCV_ISA_C \
  -d FPU \
  -d VGA_CONSOLE

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" olddefconfig \
  2>&1 | tee -a "${BUILD_DIR}/config.log"

for required in \
  'CONFIG_32BIT=y' \
  'CONFIG_MMU=y' \
  'CONFIG_RISCV=y'; do
  grep -qx "${required}" "${OBJ_DIR}/.config" || {
    echo "ERROR: resolved Linux config missing ${required}" >&2
    exit 22
  }
done
grep -qx '# CONFIG_EFI is not set' "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux config retained EFI, which re-selects compressed ISA" >&2; exit 22;
}
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux config retained compressed ISA" >&2; exit 22;
}
grep -qx '# CONFIG_FPU is not set' "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux config retained FPU" >&2; exit 22;
}
grep -qx '# CONFIG_VGA_CONSOLE is not set' "${OBJ_DIR}/.config" || {
  echo "ERROR: Linux config retained unsupported VGA text console" >&2; exit 22;
}

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${OBJ_DIR}/vmlinux"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || {
  echo "ERROR: Linux RV32 build did not produce vmlinux and Image" >&2
  exit 23
}

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -A "${VMLINUX}" \
  | tee "${EVIDENCE_DIR}/vmlinux-readelf.txt"
file "${VMLINUX}" "${IMAGE}" | tee "${EVIDENCE_DIR}/file.txt"
cp "${OBJ_DIR}/.config" "${EVIDENCE_DIR}/resolved.config"
sha256sum "${VMLINUX}" "${IMAGE}" "${EVIDENCE_DIR}/resolved.config" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF32'
"${L32_CROSS_COMPILE_PREFIX}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V'

arch="$(${L32_CROSS_COMPILE_PREFIX}readelf -A "${VMLINUX}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
if [[ -n "${arch}" && ( "${arch}" =~ _f[0-9] || "${arch}" =~ _d[0-9] || "${arch}" =~ _c[0-9] ) ]]; then
  echo "ERROR: Linux vmlinux retained unsupported F/D/C extension: ${arch}" >&2
  exit 24
fi

{
  echo "L32_LINUX_BUILD_RESULT: status=PASS"
  echo "recipe_version=${L32_LINUX_RECIPE_VERSION}"
  echo "linux_version=${LINUX_VERSION}"
  echo "source_sha256=${LINUX_SHA256}"
  echo "defconfig=${LINUX_RV32_DEFCONFIG}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "arch=${arch:-not-emitted}"
  echo "kbuild_user=${KBUILD_BUILD_USER}"
  echo "kbuild_host=${KBUILD_BUILD_HOST}"
  echo "kbuild_version=${KBUILD_BUILD_VERSION}"
  echo "kbuild_timestamp=${KBUILD_BUILD_TIMESTAMP}"
  echo "kbuild_tz=${TZ}"
} | tee "${BUILD_DIR}/result.txt"
