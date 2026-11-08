#!/usr/bin/env bash
# Bounded G5 SV39 single-RVC instruction experiment, never SD1/Linux handoff.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${AETHERCORE_C1_P1_RVC_OUT:-$ROOT/build/product-c1-g5-rvc}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-riscv64-linux-gnu-}"

for tool in gcc objcopy nm readelf; do
  command -v "${CROSS}${tool}" >/dev/null || {
    echo "PRODUCT_C1_G5_RVC_TOOLCHAIN_MISSING: ${CROSS}${tool}" >&2
    exit 3
  }
done

mkdir -p "$OUT"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p1_rvc_fixture.py" --out-dir "$OUT"

# Only use P0's fixed restore shim/linker layout. The Linux-derived first
# opcode is placed at its independently observed PA in source DDR by Python;
# synthetic SATP, register table and sentinel are never passed as QEMU state.
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

"${CROSS}nm" -n "$OUT/linked.elf" > "$OUT/symbols.txt"
"${CROSS}readelf" -SW "$OUT/linked.elf" > "$OUT/elf-sections.txt"
python3 "$ROOT/tools/ci/product_c1_arch_relay/p1_rvc_fixture.py" \
  --out-dir "$OUT" --finalize
sha256sum "$OUT/restore-flat.bin" "$OUT/state-424.bin" \
  "$OUT/source-first-rvc-then-synthetic-sentinel.bin" \
  "$OUT/ram-256m.bin" > "$OUT/sha256sums.txt"
echo 'PRODUCT_C1_G5_RVC_FIXTURE_BUILT_NOT_RTL_PASS'
