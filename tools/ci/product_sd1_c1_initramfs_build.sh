#!/usr/bin/env bash
# Builds ONLY a rootfs-independent static /init for a separately selected C1
# Linux kernel profile. It never builds/embeds a Debian or Alpine rootfs,
# starts QEMU, touches RTL, or changes normal SD1 firmware/manifests.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT/software/rv64_userspace/product_sd1_c1_init.c"
OUT="$ROOT/build/product-sd1-c1-initramfs"
ACTION="${1:-}"
fail() { printf 'PRODUCT_SD1_C1_INITRAMFS_REJECTED: %s\n' "$*" >&2; exit 2; }
case "$ACTION" in
  check-source)
    "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsyntax-only "$SOURCE"
    echo 'PRODUCT_SD1_C1_GUEST_INIT_SOURCE_PASS no_guest_cycles=1'
    exit 0;;
  prepare) ;;
  *) fail 'usage: product_sd1_c1_initramfs_build.sh check-source|prepare';;
esac

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision the fixed RV64 GCC first}"
CROSS="$AETHERCORE_RV64_LINUX_CROSS_COMPILE"
[[ "$CROSS" == /* && -x "${CROSS}gcc" && -x "${CROSS}readelf" ]] ||
  fail 'C1 requires the exact absolute RV64 Linux cross GCC and readelf'
[[ ! -e "$OUT" ]] || fail 'refusing to overwrite an existing C1 initramfs'
mkdir -p "$OUT"

"${CROSS}gcc" -std=c11 -Os -static -fno-pie -no-pie \
  -Wall -Wextra -Werror "$SOURCE" -o "$OUT/init"
"${CROSS}readelf" -h "$OUT/init" | grep -Eq 'Machine:[[:space:]]*RISC-V' ||
  fail 'C1 /init is not a RISC-V executable'
"${CROSS}readelf" -l "$OUT/init" | grep -Fq 'LOAD' ||
  fail 'C1 /init has no loadable code'
if "${CROSS}readelf" -l "$OUT/init" | grep -Fq 'INTERP'; then
  fail 'C1 /init must be fully static; no Debian loader is available'
fi

# Linux gen_init_cpio consumes this textual list at CONFIG_INITRAMFS_SOURCE.
# The kernel owns /dev/console, and the true Debian/Alpine ext4 root is only
# mounted AFTER the AETHERCORE_C1_GO serial release. This spec is rootfs-
# agnostic: it contains no distro-provided libraries or package database.
{
  printf 'dir /dev 0755 0 0\n'
  printf 'nod /dev/console 0600 0 0 c 5 1\n'
  printf 'dir /proc 0555 0 0\n'
  printf 'dir /sys 0555 0 0\n'
  printf 'dir /newroot 0755 0 0\n'
  printf 'file /init %s 0755 0 0\n' "$OUT/init"
} > "$OUT/initramfs.list"
{
  echo 'PRODUCT_SD1_C1_INITRAMFS_RESULT: status=READY_UNVERIFIED'
  echo 'qualification=GENERIC_LINUX_HANDOFF_ONLY_NOT_RTL_NOT_DEBIAN'
  echo 'init_protocol=AETHERCORE_C1_READY/AETHERCORE_C1_GO'
  echo "guest_init_source_sha256=$(sha256sum "$SOURCE" | cut -d' ' -f1)"
  echo "init_elf_sha256=$(sha256sum "$OUT/init" | cut -d' ' -f1)"
  echo "initramfs_spec_sha256=$(sha256sum "$OUT/initramfs.list" | cut -d' ' -f1)"
  echo "compiler_sha256=$(sha256sum "${CROSS}gcc" | cut -d' ' -f1)"
} > "$OUT/result.txt"
echo 'PRODUCT_SD1_C1_INITRAMFS_READY_UNVERIFIED no_debian_rootfs_embedded=1'
