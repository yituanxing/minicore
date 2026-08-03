#!/usr/bin/env bash
set -euo pipefail

version="15.2.0-1"
archive_name="xpack-riscv-none-elf-gcc-${version}-linux-x64.tar.gz"
archive_sha256="aaaa8060c914851a3e5ee1ba82cc3d6f80972f90638a05c6e823a37557a33758"
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
prefix="$cache_root/xpack-riscv-none-elf-gcc-$version"
download_dir="$cache_root/downloads"
archive="$download_dir/$archive_name"
marker="$prefix/.aethercore-archive-sha256"

urls=(
  "https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v${version}/${archive_name}"
  "https://sourceforge.net/projects/riscv-none-elf-gcc-xpack/files/v${version}/${archive_name}/download"
)

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

validation_error() {
  printf 'TOOLCHAIN VALIDATION ERROR: %s\n' "$*" >&2
  return 1
}

validate_payload() {
  local root="$1"
  local gcc="$root/bin/riscv-none-elf-gcc"
  local readelf="$root/bin/riscv-none-elf-readelf"
  local sysroot libc libgcc multi_dir probe_dir machine fullversion

  [[ -x "$gcc" ]] || validation_error "missing compiler: $gcc" || return 1
  [[ -x "$root/bin/riscv-none-elf-objcopy" ]] || validation_error "missing objcopy" || return 1
  [[ -x "$root/bin/riscv-none-elf-objdump" ]] || validation_error "missing objdump" || return 1
  [[ -x "$readelf" ]] || validation_error "missing readelf" || return 1

  machine="$($gcc -dumpmachine 2>/dev/null)" || validation_error "cannot query target machine" || return 1
  fullversion="$($gcc -dumpfullversion 2>/dev/null)" || validation_error "cannot query GCC version" || return 1
  [[ "$machine" = "riscv-none-elf" ]] || validation_error "unexpected target: $machine" || return 1
  [[ "$fullversion" = "15.2.0" ]] || validation_error "unexpected GCC version: $fullversion" || return 1

  sysroot="$($gcc --print-sysroot 2>/dev/null)" || validation_error "cannot query sysroot" || return 1
  [[ -n "$sysroot" ]] || validation_error "compiler returned an empty sysroot" || return 1
  [[ -d "$sysroot" ]] || validation_error "sysroot directory does not exist: $sysroot" || return 1
  case "$sysroot" in
    "$root"/*) ;;
    *) validation_error "sysroot escapes pinned toolchain: $sysroot" || return 1 ;;
  esac

  # stddef.h and GCC's stdint.h wrapper legitimately live in GCC's internal
  # include directory.  The authoritative boundary is that a real RV32 compile
  # can resolve them, while newlib's hosted-surface headers come from sysroot.
  [[ -f "$sysroot/include/stdlib.h" ]] || validation_error "missing newlib stdlib.h: $sysroot/include/stdlib.h" || return 1
  [[ -f "$sysroot/include/string.h" ]] || validation_error "missing newlib string.h: $sysroot/include/string.h" || return 1

  # The published soft-float multilib is rv32im/ilp32.  Zicsr is an ISA
  # extension used by our objects, not a separate newlib directory.
  multi_dir="$($gcc -march=rv32im -mabi=ilp32 -print-multi-directory 2>/dev/null)" || \
    validation_error "cannot select rv32im/ilp32 multilib" || return 1
  [[ "$multi_dir" = "rv32im/ilp32" ]] || validation_error "unexpected rv32im multilib directory: $multi_dir" || return 1

  libc="$($gcc -march=rv32im -mabi=ilp32 -print-file-name=libc.a 2>/dev/null)" || \
    validation_error "cannot locate RV32 libc.a" || return 1
  libgcc="$($gcc -march=rv32im -mabi=ilp32 -print-libgcc-file-name 2>/dev/null)" || \
    validation_error "cannot locate RV32 libgcc.a" || return 1
  [[ "$libc" != "libc.a" && -f "$libc" ]] || validation_error "missing RV32 ILP32 newlib libc.a: $libc" || return 1
  [[ -f "$libgcc" ]] || validation_error "missing RV32 ILP32 libgcc.a: $libgcc" || return 1
  case "$libc" in "$root"/*) ;; *) validation_error "libc.a escapes pinned toolchain: $libc" || return 1 ;; esac
  case "$libgcc" in "$root"/*) ;; *) validation_error "libgcc.a escapes pinned toolchain: $libgcc" || return 1 ;; esac

  probe_dir="$(mktemp -d "$cache_root/.riscv-none-elf-probe.XXXXXX")" || \
    validation_error "cannot create probe directory" || return 1
  cat > "$probe_dir/sysroot_probe.c" <<'EOF'
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

_Static_assert( sizeof( void * ) == 4, "RV32 pointer width" );
_Static_assert( sizeof( uintptr_t ) == 4, "RV32 uintptr_t width" );

int sysroot_probe( void * destination, const void * source, size_t bytes )
{
    return memcmp( memcpy( destination, source, bytes ), source, bytes ) + abs( -1 ) - 1;
}
EOF

  if ! "$gcc" --sysroot="$sysroot" -march=rv32im_zicsr -mabi=ilp32 \
    -mcmodel=medany -ffreestanding -fno-builtin -std=c11 \
    -c "$probe_dir/sysroot_probe.c" -o "$probe_dir/sysroot_probe.o"; then
    validation_error "RV32 compile probe could not resolve the standard headers"
    rm -rf "$probe_dir"
    return 1
  fi
  if ! "$readelf" -h "$probe_dir/sysroot_probe.o" > "$probe_dir/elf-header.txt"; then
    validation_error "readelf rejected the RV32 probe object"
    rm -rf "$probe_dir"
    return 1
  fi
  if ! grep -q 'Class:[[:space:]]*ELF32' "$probe_dir/elf-header.txt" || \
     ! grep -q 'Machine:[[:space:]]*RISC-V' "$probe_dir/elf-header.txt"; then
    validation_error "probe object is not ELF32 RISC-V"
    cat "$probe_dir/elf-header.txt" >&2
    rm -rf "$probe_dir"
    return 1
  fi

  if ! "$gcc" --sysroot="$sysroot" -march=rv32im_zicsr -mabi=ilp32 \
    -E -H -Wp,-v "$probe_dir/sysroot_probe.c" \
    >/dev/null 2> "$probe_dir/include-search.txt"; then
    validation_error "preprocessor include-trace probe failed"
    cat "$probe_dir/include-search.txt" >&2
    rm -rf "$probe_dir"
    return 1
  fi
  if ! grep -Fq "$sysroot/include" "$probe_dir/include-search.txt"; then
    validation_error "newlib sysroot is absent from compiler include search"
    cat "$probe_dir/include-search.txt" >&2
    rm -rf "$probe_dir"
    return 1
  fi
  if ! grep -Fq "$sysroot/include/stdlib.h" "$probe_dir/include-search.txt" || \
     ! grep -Fq "$sysroot/include/string.h" "$probe_dir/include-search.txt"; then
    validation_error "stdlib.h/string.h were not resolved from the newlib sysroot"
    cat "$probe_dir/include-search.txt" >&2
    rm -rf "$probe_dir"
    return 1
  fi
  if grep -Eq '(^|[[:space:].])(/usr/include|/usr/local/include)(/|$)' "$probe_dir/include-search.txt"; then
    validation_error "host C headers leaked into the RISC-V compile"
    cat "$probe_dir/include-search.txt" >&2
    rm -rf "$probe_dir"
    return 1
  fi

  printf 'validated_target=%s\n' "$machine"
  printf 'validated_gcc_version=%s\n' "$fullversion"
  printf 'validated_sysroot=%s\n' "$sysroot"
  printf 'validated_multilib=%s\n' "$multi_dir"
  printf 'validated_libc=%s\n' "$libc"
  printf 'validated_libgcc=%s\n' "$libgcc"
  rm -rf "$probe_dir"
  return 0
}

verify_install() {
  [[ -f "$marker" ]] || return 1
  [[ "$(cat "$marker" 2>/dev/null)" = "$archive_sha256" ]] || return 1
  validate_payload "$prefix"
}

activate() {
  local sysroot
  sysroot="$($prefix/bin/riscv-none-elf-gcc --print-sysroot)"
  export PATH="$prefix/bin:$PATH"

  if [[ -n "${GITHUB_PATH:-}" ]]; then
    printf '%s\n' "$prefix/bin" >> "$GITHUB_PATH"
  fi
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'AETHERCORE_RISCV_NONE_ELF_ROOT=%s\n' "$prefix" >> "$GITHUB_ENV"
  fi

  printf 'aethercore_riscv_toolchain_version=%s\n' "$version"
  printf 'aethercore_riscv_toolchain_archive_sha256=%s\n' "$archive_sha256"
  printf 'aethercore_riscv_toolchain_root=%s\n' "$prefix"
  printf 'aethercore_riscv_toolchain_sysroot=%s\n' "$sysroot"
  riscv-none-elf-gcc --version | head -n 1
  riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-multi-directory
  sha256sum "$sysroot/include/stdlib.h" "$sysroot/include/string.h"
}

if verify_install; then
  activate
  exit 0
fi

mkdir -p "$download_dir" "$cache_root"

if [[ -f "$archive" ]]; then
  if ! printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "$archive"
  fi
fi

if [[ ! -f "$archive" ]]; then
  tmp_archive="$archive.part.$$"
  rm -f "$tmp_archive"
  downloaded=0
  for url in "${urls[@]}"; do
    if curl --http1.1 -fL --retry 5 --retry-delay 2 \
      --connect-timeout 30 --max-time 1800 \
      "$url" -o "$tmp_archive"; then
      downloaded=1
      break
    fi
    rm -f "$tmp_archive"
  done
  [[ "$downloaded" -eq 1 ]] || fail "unable to download $archive_name"
  printf '%s  %s\n' "$archive_sha256" "$tmp_archive" | sha256sum -c -
  mv "$tmp_archive" "$archive"
fi

printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum -c -

extract_dir="$(mktemp -d "$cache_root/.xpack-riscv-none-elf.XXXXXX")"
trap 'rm -rf "$extract_dir"' EXIT

tar -xzf "$archive" -C "$extract_dir"
candidate="$extract_dir/xpack-riscv-none-elf-gcc-$version"
[[ -d "$candidate" ]] || fail "archive root mismatch: expected $candidate"
validate_payload "$candidate" || fail "downloaded toolchain failed the named RV32/newlib check above"
printf '%s\n' "$archive_sha256" > "$candidate/.aethercore-archive-sha256"

rm -rf "$prefix"
mv "$candidate" "$prefix"
verify_install || fail "installed toolchain failed post-install verification"
activate
