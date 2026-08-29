#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32/linux-freeze.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/linux"
ARCHIVE="${CACHE_ROOT}/linux-${LINUX_VERSION}.tar.xz"
SOURCE_DIR="${CACHE_ROOT}/linux-${LINUX_VERSION}"
CANONICAL_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
STAGING_BUILD_DIR="${ROOT_DIR}/build/.l32-linux-staging"
BUILD_DIR="${STAGING_BUILD_DIR}"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
OBJ_DIR="${BUILD_DIR}/obj"
STAGING_KEY_FILE="${BUILD_DIR}/.aethercore-staging-input-key"
CACHE_KEY_SCRIPT="${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
JOBS="${L32_LINUX_JOBS:-$(nproc)}"
# CONFIG_PAHOLE_VERSION is serialized into .config. The original #78 canonical
# build had no usable pahole and therefore froze CONFIG_PAHOLE_VERSION=0.
# Linux 6.6 scripts/pahole-version.sh emits exactly 0 when the PAHOLE command
# cannot be resolved. Point PAHOLE at an intentionally absent sentinel rather
# than /bin/false: coreutils false --version prints text, which is not a valid
# Kconfig number and therefore does not model an absent pahole at all.
PAHOLE_BIN="${L32_LINUX_PAHOLE:-/__aethercore_no_pahole__}"

# The canonical base is a recipe-derived artifact, not an incremental Kbuild
# checkpoint. Fixed neutral metadata keeps rebuilds deterministic. Rebuild in a
# staging directory so cancellation, runner loss, or a failed Kbuild never
# destroys the previously published frozen base. A same-key interrupted
# staging tree may resume; the final frozen hashes remain the hard qualification.
export KBUILD_BUILD_USER="${L32_LINUX_BUILD_USER}"
export KBUILD_BUILD_HOST="${L32_LINUX_BUILD_HOST}"
export KBUILD_BUILD_VERSION="${L32_LINUX_BUILD_VERSION}"
export KBUILD_BUILD_TIMESTAMP="${L32_LINUX_BUILD_TIMESTAMP}"
export TZ="${L32_LINUX_BUILD_TZ}"

mkdir -p "${CACHE_ROOT}" "${ROOT_DIR}/build"
if command -v "${PAHOLE_BIN}" >/dev/null 2>&1; then
  echo "ERROR: canonical L32 recipe requires an unavailable pahole sentinel, but it resolved: ${PAHOLE_BIN}" >&2
  exit 19
fi

publish_staging() {
  # Staging has already passed the exact frozen-output checks. Removing the old
  # canonical tree only at this point keeps the previous canonical base untouched
  # throughout the long Kbuild. If interruption lands in this tiny publish window,
  # the qualified staging tree remains and the next invocation republishes it.
  rm -rf "${CANONICAL_BUILD_DIR}"
  mv "${STAGING_BUILD_DIR}" "${CANONICAL_BUILD_DIR}"
}

# Recover a fully qualified staging result left by an interruption during the
# tiny publish window. This path performs no Kbuild work.
if "${CACHE_KEY_SCRIPT}" check "${STAGING_BUILD_DIR}" >/dev/null 2>&1; then
  echo "L32 Linux staging cache already qualified; publishing without rebuild."
  publish_staging
  "${CACHE_KEY_SCRIPT}" check "${CANONICAL_BUILD_DIR}"
  sha256sum \
    "${CANONICAL_BUILD_DIR}/obj/vmlinux" \
    "${CANONICAL_BUILD_DIR}/obj/arch/riscv/boot/Image" \
    "${CANONICAL_BUILD_DIR}/evidence/resolved.config" \
    > "${CANONICAL_BUILD_DIR}/evidence/sha256.txt"
  exit 0
fi

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
  archive_urls=("${LINUX_ARCHIVE}")
  if [[ "${LINUX_ARCHIVE}" == https://cdn.kernel.org/* ]]; then
    kernel_path="${LINUX_ARCHIVE#https://cdn.kernel.org}"
    archive_urls+=(
      "https://mirrors.edge.kernel.org${kernel_path}"
      "https://www.kernel.org${kernel_path}"
    )
  fi

  downloaded=0
  for archive_url in "${archive_urls[@]}"; do
    rm -f "${tmp}"
    echo "L32 Linux source fetch: ${archive_url}"
    if curl --http1.1 -fL --retry 4 --retry-delay 2 --retry-all-errors \
        --connect-timeout 30 --max-time 1200 \
        "${archive_url}" -o "${tmp}"; then
      if printf '%s  %s\n' "${LINUX_SHA256}" "${tmp}" | sha256sum -c -; then
        mv "${tmp}" "${ARCHIVE}"
        downloaded=1
        break
      fi
      echo "L32 Linux source checksum mismatch from ${archive_url}; trying next mirror." >&2
    else
      echo "L32 Linux source fetch failed from ${archive_url}; trying next mirror." >&2
    fi
  done
  rm -f "${tmp}"
  [[ "${downloaded}" == "1" ]] || {
    echo "ERROR: unable to fetch the exact Linux ${LINUX_VERSION} archive from all pinned mirrors" >&2
    exit 21
  }
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

input_key="$("${CACHE_KEY_SCRIPT}" key)"
resume_staging=0
if [[ -f "${STAGING_KEY_FILE}" ]] && [[ "$(cat "${STAGING_KEY_FILE}")" == "${input_key}" ]]; then
  resume_staging=1
  echo "L32 Linux staging input key matches; resuming interrupted Kbuild."
else
  # A different recipe/toolchain must never inherit Kbuild objects.
  rm -rf "${STAGING_BUILD_DIR}"
  mkdir -p "${STAGING_BUILD_DIR}"
  printf '%s\n' "${input_key}" > "${STAGING_KEY_FILE}"
fi

mkdir -p "${OBJ_DIR}"
rm -rf "${EVIDENCE_DIR}"
mkdir -p "${EVIDENCE_DIR}"
rm -f "${BUILD_DIR}/result.txt" "${BUILD_DIR}/config.log" "${BUILD_DIR}/linux-build.log"

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
  echo "pahole=${PAHOLE_BIN}"
  echo "pahole_version=0"
  echo "staging_input_key=${input_key}"
  echo "resumed_staging=${resume_staging}"
} > "${EVIDENCE_DIR}/build-inputs.txt"

make -C "${SOURCE_DIR}" O="${OBJ_DIR}" \
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  PAHOLE="${PAHOLE_BIN}" \
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
  ARCH=riscv CROSS_COMPILE="${L32_CROSS_COMPILE_PREFIX}" \
  PAHOLE="${PAHOLE_BIN}" olddefconfig \
  2>&1 | tee -a "${BUILD_DIR}/config.log"

for required in \
  'CONFIG_32BIT=y' \
  'CONFIG_MMU=y' \
  'CONFIG_RISCV=y' \
  'CONFIG_PAHOLE_VERSION=0'; do
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
  PAHOLE="${PAHOLE_BIN}" \
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
  echo "vmlinux=${CANONICAL_BUILD_DIR}/obj/vmlinux"
  echo "image=${CANONICAL_BUILD_DIR}/obj/arch/riscv/boot/Image"
  echo "arch=${arch:-not-emitted}"
  echo "kbuild_user=${KBUILD_BUILD_USER}"
  echo "kbuild_host=${KBUILD_BUILD_HOST}"
  echo "kbuild_version=${KBUILD_BUILD_VERSION}"
  echo "kbuild_timestamp=${KBUILD_BUILD_TIMESTAMP}"
  echo "kbuild_tz=${TZ}"
  echo "pahole_version=0"
} | tee "${BUILD_DIR}/result.txt"

# Qualify the complete staging tree before publishing it. Until these exact
# hashes pass, the previous canonical base remains untouched.
"${CACHE_KEY_SCRIPT}" mark "${STAGING_BUILD_DIR}"
"${CACHE_KEY_SCRIPT}" check "${STAGING_BUILD_DIR}"
publish_staging
"${CACHE_KEY_SCRIPT}" check "${CANONICAL_BUILD_DIR}"
# sha256sum records its operand paths. Rewrite the evidence after publication so
# archived paths identify the durable canonical artifact rather than the moved
# staging directory, without changing which bytes were qualified above.
sha256sum \
  "${CANONICAL_BUILD_DIR}/obj/vmlinux" \
  "${CANONICAL_BUILD_DIR}/obj/arch/riscv/boot/Image" \
  "${CANONICAL_BUILD_DIR}/evidence/resolved.config" \
  > "${CANONICAL_BUILD_DIR}/evidence/sha256.txt"
echo "L32 Linux transactional publish complete: ${CANONICAL_BUILD_DIR}"
