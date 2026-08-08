#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/linux"
ARCHIVE="${CACHE_ROOT}/linux-${LINUX_VERSION}.tar.xz"
SOURCE_DIR="${CACHE_ROOT}/linux-${LINUX_VERSION}"
BUILD_DIR="${ROOT_DIR}/build/l32-linux"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"

mkdir -p "${CACHE_ROOT}" "${BUILD_DIR}" "${EVIDENCE_DIR}"

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

rm -rf "${BUILD_DIR}/obj"
mkdir -p "${BUILD_DIR}/obj"

make -C "${SOURCE_DIR}" O="${BUILD_DIR}/obj" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  "${LINUX_RV32_DEFCONFIG}" \
  2>&1 | tee "${BUILD_DIR}/config.log"

# Keep the first Linux workload inside the already-frozen AetherCore ISA. The
# upstream rv32_defconfig is the starting point; this bounded overlay removes
# instruction-set features that the current CPU intentionally does not expose.
"${SOURCE_DIR}/scripts/config" --file "${BUILD_DIR}/obj/.config" \
  -d RISCV_ISA_C \
  -d FPU

make -C "${SOURCE_DIR}" O="${BUILD_DIR}/obj" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" olddefconfig \
  2>&1 | tee -a "${BUILD_DIR}/config.log"

for required in \
  'CONFIG_32BIT=y' \
  'CONFIG_MMU=y' \
  'CONFIG_RISCV=y'; do
  grep -qx "${required}" "${BUILD_DIR}/obj/.config" || {
    echo "ERROR: resolved Linux config missing ${required}" >&2
    exit 22
  }
done
grep -qx '# CONFIG_RISCV_ISA_C is not set' "${BUILD_DIR}/obj/.config" || {
  echo "ERROR: Linux config retained compressed ISA" >&2; exit 22;
}
grep -qx '# CONFIG_FPU is not set' "${BUILD_DIR}/obj/.config" || {
  echo "ERROR: Linux config retained FPU" >&2; exit 22;
}

make -C "${SOURCE_DIR}" O="${BUILD_DIR}/obj" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  -j"${JOBS}" Image \
  2>&1 | tee "${BUILD_DIR}/linux-build.log"

VMLINUX="${BUILD_DIR}/obj/vmlinux"
IMAGE="${BUILD_DIR}/obj/arch/riscv/boot/Image"
[[ -s "${VMLINUX}" && -s "${IMAGE}" ]] || {
  echo "ERROR: Linux RV32 build did not produce vmlinux and Image" >&2
  exit 23
}

"${L32_CROSS_COMPILE_PREFIX}readelf" -h -A "${VMLINUX}" \
  | tee "${EVIDENCE_DIR}/vmlinux-readelf.txt"
file "${VMLINUX}" "${IMAGE}" | tee "${EVIDENCE_DIR}/file.txt"
cp "${BUILD_DIR}/obj/.config" "${EVIDENCE_DIR}/resolved.config"
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
  echo "linux_version=${LINUX_VERSION}"
  echo "source_sha256=${LINUX_SHA256}"
  echo "defconfig=${LINUX_RV32_DEFCONFIG}"
  echo "vmlinux=${VMLINUX}"
  echo "image=${IMAGE}"
  echo "arch=${arch:-not-emitted}"
} | tee "${BUILD_DIR}/result.txt"
