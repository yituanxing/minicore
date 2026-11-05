#!/usr/bin/env bash
set -euo pipefail

# Minimal, real Product SD1 transport-only inputs. This firmware is not
# OpenSBI, Linux, PID1 or Debian; its evidence must never qualify G5.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
action="${1:-}"
out="${AETHERCORE_SD1_TRANSPORT_INPUT_ROOT:-$root/build/product-sd1-transport-input}"
[[ "$out" = /* ]] || out="$root/$out"
manifest="$out/result.txt"
src="$root/software/rv64_userspace/product_sd1_transport_smoke.c"
linker="$root/software/rv64_userspace/product_sd1_transport.ld"
build_script="$root/tools/ci/product_sd1_transport_short_build.sh"
fail() { echo "PRODUCT_SD1_TRANSPORT_INPUT_REJECTED: $*" >&2; exit 3; }
entry() { sed -n "s/^$1=//p" "$manifest"; }
sha() { sha256sum "$1" | awk '{print $1}'; }

preflight() {
  [[ -s "$manifest" ]] || fail 'missing transport-only manifest'
  [[ "$(entry qualification)" == PRODUCT_SD1_TRANSPORT_INPUTS_ONLY_NOT_LINUX_NOT_G5 ]] || fail 'wrong qualification scope'
  [[ "$(entry source_sha256)" == "$(sha "$src")" &&
     "$(entry linker_sha256)" == "$(sha "$linker")" &&
     "$(entry build_script_sha256)" == "$(sha "$build_script")" ]] || fail 'sources changed since build'
  for name in firmware dtb sd_image; do
    path="$(entry "${name}_path")"
    [[ -n "$path" && "$path" == "$out/"* && -s "$path" ]] || fail "invalid ${name}_path"
    [[ "$(entry "${name}_sha256")" == "$(sha "$path")" ]] || fail "${name} SHA mismatch"
  done
  [[ "$(entry sd_image_path)" == "$out/card-64m.img" &&
     "$(stat -c%s "$(entry sd_image_path)")" == 67108864 ]] || fail 'transport card must be exactly 64MiB'
  python3 - "$(entry sd_image_path)" <<'PY'
import os, struct, sys
image = sys.argv[1]
with open(image, 'rb') as fp:
    mbr = fp.read(512)
    if mbr[510:512] != b'\x55\xaa':
        raise SystemExit('transport card missing MBR signature')
    start, length = struct.unpack_from('<II', mbr, 446 + 8)
    if mbr[450] != 0x83 or start != 2048 or length != 129024:
        raise SystemExit('transport card partition does not match 63MiB ext4 layout')
    fp.seek(start * 512 + 1024 + 56)
    if fp.read(2) != b'\x53\xef':
        raise SystemExit('transport card missing ext4 superblock')
PY
  printf 'PRODUCT_SD1_TRANSPORT_INPUT_PREFLIGHT_PASS: firmware_sha256=%s dtb_sha256=%s sd_image_sha256=%s scope=transport-only\n' \
    "$(entry firmware_sha256)" "$(entry dtb_sha256)" "$(entry sd_image_sha256)"
}

case "$action" in
  preflight) preflight ;;
  build)
    : "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision pinned RV64 GCC 13.3 cross toolchain}"
    cross="$AETHERCORE_RV64_LINUX_CROSS_COMPILE"
    [[ -x "${cross}gcc" && -x "${cross}objcopy" && -x "${cross}readelf" ]] || fail 'missing RV64 GCC/objcopy/readelf'
    [[ "$("${cross}gcc" -dumpmachine)" == riscv64*linux* &&
       "$("${cross}gcc" -dumpfullversion)" == 13.3.0 ]] || fail 'requires pinned riscv64 GCC 13.3.0'
    for tool in mkfs.ext4 truncate dd dtc python3 sha256sum; do
      command -v "$tool" >/dev/null || fail "missing $tool"
    done
    mkdir -p "$out"
    "${cross}gcc" -O2 -std=c11 -march=rv64ima_zicsr_zifencei -mabi=lp64 \
      -mcmodel=medany -fno-pic -fno-pie -fno-stack-protector \
      -ffreestanding -fno-asynchronous-unwind-tables -fno-unwind-tables \
      -nostdlib -static -no-pie -Wl,--build-id=none \
      -Wl,--no-relax -Wl,-z,max-page-size=4096 -Wl,-T,"$linker" \
      -o "$out/firmware.elf" "$src"
    "${cross}readelf" -h "$out/firmware.elf" | grep -Fq 'RISC-V' || fail 'firmware ELF is not RV64'
    entry="$("${cross}readelf" -h "$out/firmware.elf" | awk -F: '/Entry point address:/ {gsub(/[[:space:]]/, "", $2); print $2}')"
    first_load_paddr="$("${cross}readelf" -l "$out/firmware.elf" | awk '$1 == "LOAD" {print $4; exit}')"
    [[ "$entry" == 0x* && "$first_load_paddr" == 0x* ]] || fail 'firmware has no ELF entry/PT_LOAD'
    (( entry == 0x80000000 && first_load_paddr == 0x80000000 )) || fail "firmware raw entry mismatch: entry=$entry first_load_paddr=$first_load_paddr"
    "${cross}objcopy" -O binary "$out/firmware.elf" "$out/firmware.bin"
    (( $(stat -c%s "$out/firmware.bin") < 1048576 )) || fail 'firmware exceeds 1MiB smoke limit'
    "$root/mill" aethercore.runMain aethercore.EmitAetherSoCDts \
      --output "$out/board.dts" --isa rv64ima_zicsr_zifencei \
      --mmu sv39 --bootargs 'console=ttyS0,115200' --product --spi-sd \
      > "$out/dts-build.log"
    dtc -I dts -O dtb -o "$out/board.dtb" "$out/board.dts"
    # Sparse 64MiB media with genuine ext4 at LBA2048. The raw medium is
    # deliberately smaller than Debian's >=512MiB production image.
    truncate -s 66060288 "$out/partition.ext4"
    mkfs.ext4 -q -F -U 96207020-4765-4230-a110-001122334455 -L SD1_PROBE "$out/partition.ext4"
    truncate -s 67108864 "$out/card-64m.img"
    dd if="$out/partition.ext4" of="$out/card-64m.img" bs=1048576 seek=1 conv=notrunc status=none
    python3 - "$out/card-64m.img" <<'PY'
import sys, struct
image = sys.argv[1]
mbr = bytearray(512)
struct.pack_into('<BBBBBBBBII', mbr, 446, 0, 0, 2, 0, 0x83, 0, 0, 0, 2048, 129024)
mbr[510:512] = b'\x55\xaa'
with open(image, 'r+b') as fp:
    fp.write(mbr)
PY
    {
      printf 'qualification=PRODUCT_SD1_TRANSPORT_INPUTS_ONLY_NOT_LINUX_NOT_G5\n'
      printf 'source_sha256=%s\n' "$(sha "$src")"
      printf 'linker_sha256=%s\n' "$(sha "$linker")"
      printf 'build_script_sha256=%s\n' "$(sha "$build_script")"
      printf 'firmware_path=%s\nfirmware_sha256=%s\n' "$out/firmware.bin" "$(sha "$out/firmware.bin")"
      printf 'dtb_path=%s\ndtb_sha256=%s\n' "$out/board.dtb" "$(sha "$out/board.dtb")"
      printf 'sd_image_path=%s\nsd_image_sha256=%s\n' "$out/card-64m.img" "$(sha "$out/card-64m.img")"
    } > "$manifest.$$.tmp"
    mv -f "$manifest.$$.tmp" "$manifest"
    preflight
    ;;
  *) echo 'usage: product_sd1_transport_short_build.sh {build|preflight}' >&2; exit 2 ;;
esac
