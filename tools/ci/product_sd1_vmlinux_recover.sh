#!/usr/bin/env bash
set -euo pipefail

# Post-build, read-only recovery of symbols for a byte-identical frozen SD1
# GPIO-CS kernel. This script never touches RTL, a live checkpoint, or SD data.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
source software/rv64/linux_early.env

die() { printf 'SD1_VMLINUX_RECOVERY_REJECTED: %s\n' "$*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

[[ "${AETHERCORE_SD1_VMLINUX_RECOVERY:-0}" == 1 ]] || die 'explicit sidecar opt-in required'
: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?pinned GCC 13.3 toolchain required}"
[[ "$("${AETHERCORE_RV64_LINUX_CROSS_COMPILE}gcc" -dumpfullversion)" == 13.3.0 ]] ||
  die 'unexpected cross compiler version'

diag="$root/build/product-sd1-gpio-cs-diagnostic"
obj="$diag/linux/obj"
manifest="$diag/diagnostic-manifest.txt"
official_image="$root/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
official_config="$root/build/rv64-debian-linux/obj/.config"
official_sd="$root/build/rv64-debian-sd1-sd/sd-rootfs.img"
image="$obj/arch/riscv/boot/Image"
vmlinux="$obj/vmlinux"
system_map="$obj/System.map"
config="$obj/.config"
patch="$root/tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
out="$root/build/product-sd1-vmlinux-recovery"

[[ ! -e "$out" ]] || die 'recovery destination already exists; refusing overwrite'
for path in "$manifest" "$official_image" "$official_config" "$official_sd" \
            "$image" "$vmlinux" "$system_map" "$config" "$patch"; do
  [[ -s "$path" ]] || die "missing required immutable input: $path"
done

[[ "$(field qualification "$manifest")" == GPIO_CS_DIAGNOSTIC_KERNEL_FIRMWARE_NOT_DEBIAN_PASS ]] ||
  die 'missing original diagnostic source qualification'
[[ "$(field source_sha256 "$manifest")" == "$RV64_LINUX_SHA256" ]] ||
  die 'upstream source SHA differs from qualified 6.6.143 source'
[[ "$(field patch_sha256 "$manifest")" == "$(sha "$patch")" &&
   "$(sha "$patch")" == ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638 ]] ||
  die 'GPIO-CS diagnostic patch identity mismatch'
[[ "$(sha "$config")" == "$(field config_sha256 "$manifest")" ]] ||
  die 'rebuilt config differs from frozen diagnostic manifest'
cmp -s "$config" "$official_config" || die 'rebuilt config differs from frozen official config'
[[ "$(sha "$official_image")" == "$(field original_linux_sha256 "$manifest")" ]] ||
  die 'official baseline Image differs from source manifest'
[[ "$(sha "$official_sd")" == "$(field sd_image_sha256 "$manifest")" ]] ||
  die 'official signed SD image differs from source manifest'

# This is the non-negotiable proof that recovered ELF symbols describe the
# exact Linux machine code used by the unchanged Product RTL polling relay.
expected_image_sha="$(field linux_image_sha256 "$manifest")"
[[ "$expected_image_sha" =~ ^[0-9a-f]{64}$ ]] || die 'missing pinned diagnostic Image SHA'
[[ "$(sha "$image")" == "$expected_image_sha" ]] ||
  die 'rebuilt Image SHA differs from frozen RTL Image; symbol recovery disallowed'

"${AETHERCORE_RV64_LINUX_CROSS_COMPILE}readelf" -h "$vmlinux" |
  grep -Eq 'Machine:[[:space:]]*RISC-V' || die 'vmlinux is not RISC-V ELF'
"${AETHERCORE_RV64_LINUX_CROSS_COMPILE}nm" -n "$vmlinux" |
  grep -Eq '[[:space:]]init_task$' || die 'matching ELF lacks init_task symbol'
grep -Eq '[[:space:]]init_task$' "$system_map" ||
  die 'matching System.map lacks init_task symbol'

mkdir -p "$out"
cp -- "$vmlinux" "$out/vmlinux"
cp -- "$system_map" "$out/System.map"
{
  echo 'SD1_VMLINUX_RECOVERY_RESULT: status=EXACT_IMAGE_MATCH'
  echo 'qualification=OFFLINE_SYMBOL_RECOVERY_ONLY_NOT_DEBIAN_PASS'
  echo "linux_source_sha256=$RV64_LINUX_SHA256"
  echo "kernel_patch_sha256=$(sha "$patch")"
  echo "frozen_image_sha256=$expected_image_sha"
  echo "rebuilt_image_sha256=$(sha "$image")"
  echo "kernel_config_sha256=$(sha "$config")"
  echo "frozen_official_sd_sha256=$(sha "$official_sd")"
  echo "recovered_vmlinux_sha256=$(sha "$out/vmlinux")"
  echo "recovered_system_map_sha256=$(sha "$out/System.map")"
} > "$out/recovery-manifest.txt"
cat "$out/recovery-manifest.txt"
