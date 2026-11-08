#!/usr/bin/env bash
# Explicitly synthetic G5 Product Sv39 test. Never use as a Linux/SD1 handoff.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${AETHERCORE_C1_P1_SV39_OUT:-$ROOT/build/product-c1-sv39-g5}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-riscv64-linux-gnu-}"

for tool in gcc objcopy nm readelf; do
  command -v "${CROSS}${tool}" >/dev/null || {
    echo "PRODUCT_C1_P1_SV39_TOOLCHAIN_MISSING: ${CROSS}${tool}" >&2
    exit 3
  }
done

mkdir -p "$OUT"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p1_sv39_fixture.py" --out-dir "$OUT"

# Reuse only the deterministic linker layout and the physical guest opcodes
# from P0. All page-table bytes and SATP state are independently constructed
# for this P1-Sv39 fixture, never by the restricted P0 SATP=0 state packer.
"${CROSS}gcc" -march=rv64imac_zicsr_zifencei -mabi=lp64 \
  -nostdlib -nostartfiles -ffreestanding -fno-pic -no-pie \
  -Wl,-T,"$ROOT/sim/product_c1_arch_p0.ld" \
  -Wl,--no-relax -Wl,--build-id=none \
  -Wl,-Map,"$OUT/fixture.map" \
  "$ROOT/sim/product_c1_arch_restore.S" \
  "$ROOT/sim/product_c1_arch_p0_fixture.S" \
  -o "$OUT/unpatched.elf"

"${CROSS}objcopy" \
  --update-section ".data.c1_arch_state=$OUT/state-424.bin" \
  "$OUT/unpatched.elf" "$OUT/linked.elf"
"${CROSS}objcopy" -O binary \
  -j .text.c1_arch_restore -j .data.c1_arch_state \
  "$OUT/linked.elf" "$OUT/restore-flat.bin"
"${CROSS}objcopy" -O binary \
  -j .text.c1_p0_target -j .data.c1_p0_markers \
  "$OUT/linked.elf" "$OUT/guest-flat.bin"

"${CROSS}nm" -n "$OUT/linked.elf" > "$OUT/symbols.txt"
"${CROSS}readelf" -SW "$OUT/linked.elf" > "$OUT/elf-sections.txt"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p1_sv39_fixture.py" \
  --out-dir "$OUT" --guest-bin "$OUT/guest-flat.bin"
sha256sum "$OUT/restore-flat.bin" "$OUT/state-424.bin" \
  "$OUT/guest-flat.bin" "$OUT/ram-256m.bin" > "$OUT/sha256sums.txt"
echo "PRODUCT_C1_P1_SV39_FIXTURE_BUILT_NOT_RTL_PASS"
