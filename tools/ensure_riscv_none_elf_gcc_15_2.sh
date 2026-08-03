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

verify_payload() {
  local root="$1"
  local gcc="$root/bin/riscv-none-elf-gcc"
  local readelf="$root/bin/riscv-none-elf-readelf"
  local sysroot libc libgcc probe_dir

  test -x "$gcc"
  test -x "$root/bin/riscv-none-elf-objcopy"
  test -x "$root/bin/riscv-none-elf-objdump"
  test -x "$readelf"
  test "$($gcc -dumpmachine)" = "riscv-none-elf"
  test "$($gcc -dumpfullversion)" = "15.2.0"

  sysroot="$($gcc --print-sysroot)"
  test -n "$sysroot"
  test -d "$sysroot"
  case "$sysroot" in
    "$root"/*) ;;
    *) fail "compiler sysroot escapes the pinned toolchain: $sysroot" ;;
  esac

  for header in stddef.h stdint.h stdlib.h string.h; do
    test -f "$sysroot/include/$header" || fail "missing newlib header: $sysroot/include/$header"
  done

  libc="$($gcc -march=rv32im_zicsr -mabi=ilp32 -print-file-name=libc.a)"
  libgcc="$($gcc -march=rv32im_zicsr -mabi=ilp32 -print-libgcc-file-name)"
  test "$libc" != "libc.a"
  test -f "$libc" || fail "missing RV32 ILP32 newlib libc.a: $libc"
  test -f "$libgcc" || fail "missing RV32 ILP32 libgcc.a: $libgcc"

  probe_dir="$(mktemp -d "$cache_root/.riscv-none-elf-probe.XXXXXX")"
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

  "$gcc" --sysroot="$sysroot" -march=rv32im_zicsr -mabi=ilp32 \
    -mcmodel=medany -ffreestanding -fno-builtin -std=c11 \
    -c "$probe_dir/sysroot_probe.c" -o "$probe_dir/sysroot_probe.o"
  "$readelf" -h "$probe_dir/sysroot_probe.o" > "$probe_dir/elf-header.txt"
  grep -q 'Class:[[:space:]]*ELF32' "$probe_dir/elf-header.txt"
  grep -q 'Machine:[[:space:]]*RISC-V' "$probe_dir/elf-header.txt"

  "$gcc" --sysroot="$sysroot" -march=rv32im_zicsr -mabi=ilp32 \
    -E -Wp,-v "$probe_dir/sysroot_probe.c" \
    >/dev/null 2> "$probe_dir/include-search.txt"
  grep -Fq "$sysroot/include" "$probe_dir/include-search.txt"
  rm -rf "$probe_dir"
}

verify_install() {
  test -f "$marker"
  test "$(cat "$marker")" = "$archive_sha256"
  verify_payload "$prefix"
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
  riscv-none-elf-gcc -march=rv32im_zicsr -mabi=ilp32 -print-multi-directory
  sha256sum "$sysroot/include/stdlib.h" "$sysroot/include/string.h"
}

if verify_install; then
  activate
  exit 0
fi

mkdir -p "$download_dir" "$cache_root"

if [[ -f "$archive" ]] && ! printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum -c - >/dev/null 2>&1; then
  rm -f "$archive"
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
  test "$downloaded" -eq 1 || fail "unable to download $archive_name"
  printf '%s  %s\n' "$archive_sha256" "$tmp_archive" | sha256sum -c -
  mv "$tmp_archive" "$archive"
fi

printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum -c -

extract_dir="$(mktemp -d "$cache_root/.xpack-riscv-none-elf.XXXXXX")"
trap 'rm -rf "$extract_dir"' EXIT

tar -xzf "$archive" -C "$extract_dir"
candidate="$extract_dir/xpack-riscv-none-elf-gcc-$version"
test -d "$candidate" || fail "archive root mismatch: expected $candidate"
verify_payload "$candidate"
printf '%s\n' "$archive_sha256" > "$candidate/.aethercore-archive-sha256"

rm -rf "$prefix"
mv "$candidate" "$prefix"
verify_install
activate
