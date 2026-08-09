#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"

cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
prefix="${cache_root}/${L32_TOOLCHAIN_VERSION}"
download_dir="${cache_root}/downloads"
archive="${download_dir}/${L32_TOOLCHAIN_ARCHIVE}"
marker="${prefix}/.aethercore-archive-sha256"
compiler="${prefix}/bin/${L32_CROSS_COMPILE_PREFIX}gcc"
readelf="${prefix}/bin/${L32_CROSS_COMPILE_PREFIX}readelf"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

validate_payload() {
  local root="$1"
  local gcc="${root}/bin/${L32_CROSS_COMPILE_PREFIX}gcc"
  local ld="${root}/bin/${L32_CROSS_COMPILE_PREFIX}ld"
  local elfread="${root}/bin/${L32_CROSS_COMPILE_PREFIX}readelf"
  local probe_dir machine version

  [[ -x "${gcc}" ]] || return 1
  [[ -x "${ld}" ]] || return 1
  [[ -x "${elfread}" ]] || return 1

  machine="$(${gcc} -dumpmachine 2>/dev/null || true)"
  [[ "${machine}" == riscv32*linux* ]] || {
    echo "L32_BOOTLIN_BAD_TARGET: ${machine}" >&2
    return 1
  }
  version="$(${gcc} -dumpfullversion 2>/dev/null || true)"
  [[ "${version}" == "13.3.0" ]] || {
    echo "L32_BOOTLIN_BAD_GCC_VERSION: ${version}" >&2
    return 1
  }

  probe_dir="$(mktemp -d "${cache_root}/.l32-linux-gcc-probe.XXXXXX")"
  trap 'rm -rf "${probe_dir}"' RETURN

  cat > "${probe_dir}/probe.c" <<'EOF'
int l32_pie_probe(void) { return 0; }
EOF

  if ! "${gcc}" \
      -march=rv32ima_zicsr_zifencei -mabi=ilp32 \
      -fPIE -nostdlib -Wl,-pie \
      "${probe_dir}/probe.c" -o "${probe_dir}/probe.elf"; then
    echo "L32_BOOTLIN_PIE_LINK_FAILED" >&2
    return 1
  fi

  "${elfread}" -h "${probe_dir}/probe.elf" > "${probe_dir}/header.txt"
  grep -q 'Class:[[:space:]]*ELF32' "${probe_dir}/header.txt" || return 1
  grep -q 'Machine:[[:space:]]*RISC-V' "${probe_dir}/header.txt" || return 1
  "${elfread}" -h "${probe_dir}/probe.elf" | grep -q 'Type:[[:space:]]*DYN' || {
    echo "L32_BOOTLIN_PIE_NOT_ET_DYN" >&2
    return 1
  }

  printf 'validated_target=%s\n' "${machine}"
  printf 'validated_gcc_version=%s\n' "${version}"
  printf 'validated_prefix=%s\n' "${root}"
  printf 'validated_pie=ELF32-RISCV-ET_DYN\n'
  rm -rf "${probe_dir}"
  trap - RETURN
  return 0
}

verify_install() {
  [[ -f "${marker}" ]] || return 1
  [[ "$(cat "${marker}" 2>/dev/null)" == "${L32_TOOLCHAIN_SHA256}" ]] || return 1
  validate_payload "${prefix}"
}

activate() {
  export PATH="${prefix}/bin:${PATH}"
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "${prefix}/bin" >> "${GITHUB_PATH}"
  fi
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'AETHERCORE_L32_LINUX_GCC_ROOT=%s\n' "${prefix}" >> "${GITHUB_ENV}"
    printf 'L32_CROSS_COMPILE=%s\n' "${L32_CROSS_COMPILE_PREFIX}" >> "${GITHUB_ENV}"
  fi

  printf 'aethercore_l32_toolchain_version=%s\n' "${L32_TOOLCHAIN_VERSION}"
  printf 'aethercore_l32_toolchain_archive_sha256=%s\n' "${L32_TOOLCHAIN_SHA256}"
  printf 'aethercore_l32_toolchain_root=%s\n' "${prefix}"
  "${compiler}" --version | head -n 1
  "${prefix}/bin/${L32_CROSS_COMPILE_PREFIX}ld" --version | head -n 1
}

if verify_install; then
  activate
  exit 0
fi

mkdir -p "${download_dir}" "${cache_root}"
if [[ -f "${archive}" ]]; then
  if ! printf '%s  %s\n' "${L32_TOOLCHAIN_SHA256}" "${archive}" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "${archive}"
  fi
fi

if [[ ! -f "${archive}" ]]; then
  tmp_archive="${archive}.part.$$"
  rm -f "${tmp_archive}"
  curl --http1.1 -fL --retry 5 --retry-delay 2 \
    --connect-timeout 30 --max-time 1800 \
    "${L32_TOOLCHAIN_URL}" -o "${tmp_archive}"
  printf '%s  %s\n' "${L32_TOOLCHAIN_SHA256}" "${tmp_archive}" | sha256sum -c -
  mv "${tmp_archive}" "${archive}"
fi

printf '%s  %s\n' "${L32_TOOLCHAIN_SHA256}" "${archive}" | sha256sum -c -
extract_dir="$(mktemp -d "${cache_root}/.l32-bootlin.XXXXXX")"
trap 'rm -rf "${extract_dir}"' EXIT

tar -xJf "${archive}" -C "${extract_dir}"
# Bootlin exposes the cross compiler through symlinks/wrappers in bin/, so do
# not require the path itself to be a regular file.
found_gcc="$(find "${extract_dir}" -path "*/bin/${L32_CROSS_COMPILE_PREFIX}gcc" -print -quit)"
[[ -n "${found_gcc}" ]] || fail "Bootlin archive does not contain ${L32_CROSS_COMPILE_PREFIX}gcc"
candidate="$(dirname "$(dirname "${found_gcc}")")"
validate_payload "${candidate}" || fail "downloaded Bootlin toolchain failed RV32 PIE validation"
printf '%s\n' "${L32_TOOLCHAIN_SHA256}" > "${candidate}/.aethercore-archive-sha256"

rm -rf "${prefix}"
mv "${candidate}" "${prefix}"
verify_install || fail "installed Bootlin toolchain failed post-install validation"
activate
