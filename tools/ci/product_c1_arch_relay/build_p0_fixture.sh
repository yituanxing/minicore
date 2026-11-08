#!/usr/bin/env bash
# Build the synthetic RAM-only P0 instruction-based C1 restore experiment.
# This is never an official SD1 or a QEMU-to-RTL acceptance.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${AETHERCORE_C1_P0_FIXTURE_OUT:-$ROOT/build/product-c1-arch-p0}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-riscv64-linux-gnu-}"

for tool in gcc objcopy nm readelf; do
  command -v "${CROSS}${tool}" >/dev/null || {
    echo "PRODUCT_C1_P0_FIXTURE_TOOLCHAIN_MISSING: ${CROSS}${tool}" >&2
    echo 'CI can provision the frozen Bootlin GCC with tools/ensure_riscv64_linux_gcc_13_3.sh' >&2
    exit 3
  }
done

mkdir -p "$OUT"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p0_fixture.py" --out-dir "$OUT"

"${CROSS}gcc" -march=rv64imac_zicsr_zifencei -mabi=lp64 \
  -nostdlib -nostartfiles -ffreestanding -fno-pic -no-pie \
  -Wl,-T,"$ROOT/sim/product_c1_arch_p0.ld" \
  -Wl,--no-relax -Wl,--build-id=none \
  -Wl,-Map,"$OUT/fixture.map" \
  "$ROOT/sim/product_c1_arch_restore.S" \
  "$ROOT/sim/product_c1_arch_p0_fixture.S" \
  -o "$OUT/unpatched.elf"

# Update only the 424-byte restore state section.  Its virtual/physical
# address stays fixed; all other ELF sections retain linker-assigned offsets.
"${CROSS}objcopy" \
  --update-section ".data.c1_arch_state=$OUT/state-424.bin" \
  "$OUT/unpatched.elf" "$OUT/linked.elf"

# Crucial: never create a single combined flat image that spans the overlay
# and the target.  The recovery overlay covers only the first reserved MiB;
# the target program is loaded independently into source DDR at +0x100000.
"${CROSS}objcopy" -O binary \
  -j .text.c1_arch_restore -j .data.c1_arch_state \
  "$OUT/linked.elf" "$OUT/restore-flat.bin"
"${CROSS}objcopy" -O binary \
  -j .text.c1_p0_target -j .data.c1_p0_markers \
  "$OUT/linked.elf" "$OUT/guest-flat.bin"

"${CROSS}nm" -n "$OUT/linked.elf" > "$OUT/symbols.txt"
"${CROSS}readelf" -SW "$OUT/linked.elf" > "$OUT/elf-sections.txt"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p0_fixture.py" \
  --out-dir "$OUT" --guest-bin "$OUT/guest-flat.bin"
sha256sum "$OUT/restore-flat.bin" "$OUT/state-424.bin" \
  "$OUT/guest-flat.bin" "$OUT/ram-256m.bin" > "$OUT/sha256sums.txt"

echo "PRODUCT_C1_P0_FIXTURE_BUILT_NOT_RTL_RESUMED restore_bytes=$(stat -c%s "$OUT/restore-flat.bin") guest_bytes=$(stat -c%s "$OUT/guest-flat.bin")"
