#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${AETHERCORE_ZEPHYR_BUILD_DIR:-$ROOT/build/zephyr-host}"
APP_DIR="$ROOT/software/zephyr/apps/kernel_smoke"
EVIDENCE_DIR="$BUILD_DIR/evidence"

command -v west >/dev/null 2>&1 || {
  echo "ERROR: west is not available" >&2
  exit 1
}

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

west build -p always \
  -b aethercore_sim \
  -d "$BUILD_DIR" \
  "$APP_DIR"

for artifact in \
  zephyr/zephyr.elf \
  zephyr/zephyr.bin \
  zephyr/zephyr.map \
  zephyr/zephyr.dts \
  zephyr/.config; do
  test -s "$BUILD_DIR/$artifact" || {
    echo "ERROR: missing Zephyr artifact: $BUILD_DIR/$artifact" >&2
    exit 1
  }
done

CONFIG="$BUILD_DIR/zephyr/.config"
DTS="$BUILD_DIR/zephyr/zephyr.dts"
ELF="$BUILD_DIR/zephyr/zephyr.elf"
BIN="$BUILD_DIR/zephyr/zephyr.bin"
MAP="$BUILD_DIR/zephyr/zephyr.map"

for config in \
  CONFIG_BOARD_AETHERCORE_SIM=y \
  CONFIG_SOC_AETHERCORE32=y \
  CONFIG_RISCV_ISA_RV32I=y \
  CONFIG_RISCV_ISA_EXT_M=y \
  CONFIG_RISCV_ISA_EXT_ZICSR=y \
  CONFIG_RISCV_ISA_EXT_ZIFENCEI=y \
  CONFIG_SERIAL=y \
  CONFIG_UART_CONSOLE=y \
  CONFIG_MULTITHREADING=y; do
  grep -Fxq "$config" "$CONFIG" || {
    echo "ERROR: missing frozen Zephyr config: $config" >&2
    exit 1
  }
done

if grep -Eq '^CONFIG_RISCV_ISA_EXT_(A|C)=y$' "$CONFIG"; then
  echo "ERROR: Zephyr enabled an unsupported RISC-V A or C extension" >&2
  exit 1
fi

grep -Fq 'riscv,isa = "rv32im_zicsr_zifencei"' "$DTS"
grep -Fq 'serial@10000000' "$DTS"
grep -Fq 'interrupt-controller@c000000' "$DTS"
grep -Fq 'timer@2000000' "$DTS"
grep -Fq 'memory@80000000' "$DTS"

file "$ELF" | tee "$BUILD_DIR/elf-file.txt"
grep -Eq 'ELF 32-bit.*RISC-V' "$BUILD_DIR/elf-file.txt"

mkdir -p "$EVIDENCE_DIR"
sha256sum "$ELF" "$BIN" "$MAP" "$DTS" "$CONFIG" \
  > "$EVIDENCE_DIR/artifacts.sha256"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-z1-host-build-v1
board=aethercore_sim
soc=aethercore32
profile=rv32im_zicsr_zifencei
ram_base=0x80000000
ram_size=0x04000000
uart_tx=0x10000000
uart_rx=0x10000100
plic_base=0x0c000000
mtime=0x0200bff8
mtimecmp=0x02004000
sdk_version=${SDK_VERSION:-unknown}
EOF

cat "$EVIDENCE_DIR/result.txt"
cat "$EVIDENCE_DIR/artifacts.sha256"
