#!/usr/bin/env bash
set -euo pipefail

release="2024.05-1"
archive_name="riscv64-lp64d--glibc--stable-${release}.tar.xz"
archive_sha256="78e16f3def8b2ff3da09c16155f993ac7e4dc1791d0904ada03fcb2e04910aab"
archive_url="https://toolchains.bootlin.com/downloads/releases/toolchains/riscv64-lp64d/tarballs/${archive_name}"
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
prefix="${cache_root}/bootlin-riscv64-lp64d-glibc-stable-${release}"
download_dir="${cache_root}/downloads"
archive="${download_dir}/${archive_name}"
marker="${prefix}/.aethercore-archive-sha256"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

find_cross_prefix() {
  local root="$1"
  local gcc_path
  gcc_path="$(find "${root}/bin" -maxdepth 1 -path '*/riscv64*-gcc' -print -quit 2>/dev/null || true)"
  [[ -n "${gcc_path}" ]] || return 1
  printf '%s\n' "${gcc_path%gcc}"
}

validate_payload() {
  local root="$1"
  local cross gcc ld readelf machine fullversion probe_dir

  cross="$(find_cross_prefix "${root}")" || {
    printf 'TOOLCHAIN VALIDATION ERROR: no RV64 GCC prefix under %s/bin\n' "${root}" >&2
    return 1
  }
  gcc="${cross}gcc"
  ld="${cross}ld"
  readelf="${cross}readelf"

  for tool in gcc ld ar objcopy objdump readelf nm; do
    [[ -x "${cross}${tool}" ]] || {
      printf 'TOOLCHAIN VALIDATION ERROR: missing %s\n' "${cross}${tool}" >&2
      return 1
    }
  done

  machine="$(${gcc} -dumpmachine 2>/dev/null)" || return 1
  fullversion="$(${gcc} -dumpfullversion 2>/dev/null)" || return 1
  case "${machine}" in
    riscv64*linux*) ;;
    *)
      printf 'TOOLCHAIN VALIDATION ERROR: unexpected target %s\n' "${machine}" >&2
      return 1
      ;;
  esac
  [[ "${fullversion}" == "13.3.0" ]] || {
    printf 'TOOLCHAIN VALIDATION ERROR: unexpected GCC version %s\n' "${fullversion}" >&2
    return 1
  }
  "${ld}" --version | head -n 1 | grep -q '2\.41' || {
    printf 'TOOLCHAIN VALIDATION ERROR: expected binutils ld 2.41\n' >&2
    return 1
  }

  probe_dir="$(mktemp -d "${cache_root}/.rv64-linux-pie-probe.XXXXXX")" || return 1
  cat > "${probe_dir}/probe.c" <<'EOF'
void _start(void)
{
    __asm__ volatile ("wfi");
    for (;;) { }
}
EOF
  if ! "${gcc}" -march=rv64ima_zicsr_zifencei -mabi=lp64 \
      -nostdlib -nostartfiles -ffreestanding -fPIE -pie -Wl,-e,_start \
      "${probe_dir}/probe.c" -o "${probe_dir}/probe.elf"; then
    printf 'TOOLCHAIN VALIDATION ERROR: RV64 PIE compile/link failed\n' >&2
    rm -rf "${probe_dir}"
    return 1
  fi
  "${readelf}" -h "${probe_dir}/probe.elf" > "${probe_dir}/elf-header.txt" || {
    rm -rf "${probe_dir}"
    return 1
  }
  grep -q 'Class:[[:space:]]*ELF64' "${probe_dir}/elf-header.txt" || {
    cat "${probe_dir}/elf-header.txt" >&2
    rm -rf "${probe_dir}"
    return 1
  }
  grep -q 'Machine:[[:space:]]*RISC-V' "${probe_dir}/elf-header.txt" || {
    cat "${probe_dir}/elf-header.txt" >&2
    rm -rf "${probe_dir}"
    return 1
  }
  grep -q 'Type:[[:space:]]*DYN' "${probe_dir}/elf-header.txt" || {
    printf 'TOOLCHAIN VALIDATION ERROR: PIE probe is not ET_DYN\n' >&2
    cat "${probe_dir}/elf-header.txt" >&2
    rm -rf "${probe_dir}"
    return 1
  }
  rm -rf "${probe_dir}"

  printf 'validated_target=%s\n' "${machine}"
  printf 'validated_gcc_version=%s\n' "${fullversion}"
  printf 'validated_cross_compile=%s\n' "${cross}"
  return 0
}

verify_install() {
  [[ -f "${marker}" ]] || return 1
  [[ "$(cat "${marker}" 2>/dev/null)" == "${archive_sha256}" ]] || return 1
  validate_payload "${prefix}"
}

activate() {
  local target_prefix
  target_prefix="$(find_cross_prefix "${prefix}")" || fail "installed toolchain has no RV64 compiler prefix"
  export PATH="${prefix}/bin:${PATH}"
  export AETHERCORE_RV64_LINUX_GCC_ROOT="${prefix}"
  export AETHERCORE_RV64_LINUX_CROSS_COMPILE="${target_prefix}"

  if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "${prefix}/bin" >> "${GITHUB_PATH}"
  fi
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'AETHERCORE_RV64_LINUX_GCC_ROOT=%s\n' "${prefix}" >> "${GITHUB_ENV}"
    printf 'AETHERCORE_RV64_LINUX_CROSS_COMPILE=%s\n' "${target_prefix}" >> "${GITHUB_ENV}"
  fi

  printf 'aethercore_rv64_linux_toolchain_release=%s\n' "${release}"
  printf 'aethercore_rv64_linux_toolchain_archive_sha256=%s\n' "${archive_sha256}"
  printf 'aethercore_rv64_linux_toolchain_root=%s\n' "${prefix}"
  printf 'aethercore_rv64_linux_cross_compile=%s\n' "${target_prefix}"
  "${target_prefix}gcc" --version | head -n 1
  "${target_prefix}gcc" -dumpmachine
  "${target_prefix}ld" --version | head -n 1
}

if verify_install; then
  activate
  exit 0
fi

mkdir -p "${download_dir}" "${cache_root}"

if [[ -f "${archive}" ]] && \
   ! printf '%s  %s\n' "${archive_sha256}" "${archive}" | sha256sum -c - >/dev/null 2>&1; then
  rm -f "${archive}"
fi

if [[ ! -f "${archive}" ]]; then
  tmp_archive="${archive}.part.$$"
  rm -f "${tmp_archive}"
  curl --http1.1 -fL --retry 5 --retry-delay 2 \
    --connect-timeout 30 --max-time 1800 \
    "${archive_url}" -o "${tmp_archive}" || fail "unable to download ${archive_name}"
  printf '%s  %s\n' "${archive_sha256}" "${tmp_archive}" | sha256sum -c -
  mv "${tmp_archive}" "${archive}"
fi

printf '%s  %s\n' "${archive_sha256}" "${archive}" | sha256sum -c -

extract_dir="$(mktemp -d "${cache_root}/.bootlin-rv64-linux.XXXXXX")"
trap 'rm -rf "${extract_dir}"' EXIT
tar -xJf "${archive}" -C "${extract_dir}"

gcc_path="$(find "${extract_dir}" -path '*/bin/riscv64*-gcc' -print -quit)"
[[ -n "${gcc_path}" ]] || fail "archive did not contain an RV64 Linux-target GCC"
candidate="$(dirname "$(dirname "${gcc_path}")")"
validate_payload "${candidate}" || fail "downloaded Bootlin RV64 Linux toolchain failed validation"
printf '%s\n' "${archive_sha256}" > "${candidate}/.aethercore-archive-sha256"

rm -rf "${prefix}"
mv "${candidate}" "${prefix}"
verify_install || fail "installed Bootlin RV64 Linux toolchain failed post-install verification"
activate
