#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_ROOT="${AETHERCORE_ZEPHYR_WORKSPACE_ROOT:-$(dirname "$ROOT")}" 
CACHE_ROOT="${AETHERCORE_ZEPHYR_CACHE_ROOT:-$HOME/.cache/aethercore/zephyr-v3.7.2}"
VENV_DIR="$CACHE_ROOT/venv-west-1.3.0"
SDK_DIR="$CACHE_ROOT/zephyr-sdk-0.16.9"
HOST_TOOLS_PREFIX="$CACHE_ROOT/host-tools"
BUILD_ROOT="${AETHERCORE_ZEPHYR_Z4_BUILD_ROOT:-$ROOT/build/zephyr-z4}"
BUILD_DIR="$BUILD_ROOT/host-build"
APP_DIR="$ROOT/software/zephyr/apps/uart_irq_smoke"
EVIDENCE_DIR="$BUILD_ROOT/evidence"
RUNNER="${AETHERCORE_ZEPHYR_RUNNER:-$ROOT/build/zephyr-z2/obj/VAetherCoreSimTop}"
MAX_CYCLES="${AETHERCORE_ZEPHYR_Z4_MAX_CYCLES:-2000000}"
RX_START_CYCLE="${AETHERCORE_ZEPHYR_Z4_RX_START_CYCLE:-150000}"
RX_GAP_CYCLES="${AETHERCORE_ZEPHYR_Z4_RX_GAP_CYCLES:-1000}"
STALL_PERIODS=(0 3)

WEST="$VENV_DIR/bin/west"
test -x "$WEST"
test -x "$RUNNER"
test -d "$WORKSPACE_ROOT/.west"
test -d "$APP_DIR"

dtc_binary="$(find "$SDK_DIR" -type f -path '*/bin/dtc' -perm -u+x -print -quit)"
test -n "$dtc_binary"
export PATH="$HOST_TOOLS_PREFIX/bin:$VENV_DIR/bin:$(dirname "$dtc_binary"):$PATH"
export ZEPHYR_SDK_INSTALL_DIR="$SDK_DIR"

rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_DIR" "$EVIDENCE_DIR"

"$WEST" build -p always \
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
    echo "ERROR: missing Zephyr Z4 artifact: $BUILD_DIR/$artifact" >&2
    exit 1
  }
done

CONFIG="$BUILD_DIR/zephyr/.config"
IMAGE="$BUILD_DIR/zephyr/zephyr.bin"
for config in \
  CONFIG_UART_INTERRUPT_DRIVEN=y \
  CONFIG_RISCV_HAS_PLIC=y \
  CONFIG_MULTITHREADING=y \
  CONFIG_AETHERCORE_SIM_EXIT=y; do
  grep -Fxq "$config" "$CONFIG" || {
    echo "ERROR: missing frozen Zephyr Z4 config: $config" >&2
    exit 1
  }
done

run_positive() {
  local stall_period="$1"
  local log_file="$EVIDENCE_DIR/positive-stall-${stall_period}.log"
  local args=(
    "$IMAGE"
    --max-cycles "$MAX_CYCLES"
    --self-check-exit
    --rx-byte 0x5a
    --rx-byte 0x34
    --rx-start-cycle "$RX_START_CYCLE"
    --rx-gap-cycles "$RX_GAP_CYCLES"
  )
  if [[ "$stall_period" != "0" ]]; then
    args+=(--stall-period "$stall_period")
  fi

  set +e
  "$RUNNER" "${args[@]}" 2>&1 | tee "$log_file"
  local rc=${PIPESTATUS[0]}
  set -e
  if [[ $rc -ne 0 ]]; then
    echo "ERROR: Zephyr Z4 positive run failed with rc=$rc stall=$stall_period" >&2
    exit "$rc"
  fi

  grep -Fq 'AETHERCORE ZEPHYR Z4 BOOT' "$log_file"
  grep -Fq 'AETHERCORE ZEPHYR IRQ ARMED' "$log_file"
  grep -Fq 'AETHERCORE ZEPHYR WORK byte=0x5a index=0' "$log_file"
  grep -Fq 'AETHERCORE ZEPHYR WORK byte=0x34 index=1' "$log_file"
  grep -Eq 'AETHERCORE ZEPHYR IRQ PASS bytes=2 isr=[1-9][0-9]* work=2' "$log_file"
  grep -Fq 'PASS: self-check exit=0' "$log_file"
  grep -Fq 'rx-bytes=2' "$log_file"
}

for stall_period in "${STALL_PERIODS[@]}"; do
  run_positive "$stall_period"
done

negative_log="$EVIDENCE_DIR/negative-no-rx.log"
set +e
"$RUNNER" "$IMAGE" --max-cycles 600000 --self-check-exit \
  2>&1 | tee "$negative_log"
negative_rc=${PIPESTATUS[0]}
set -e
if [[ $negative_rc -ne 3 ]]; then
  echo "ERROR: Zephyr Z4 no-RX probe returned rc=$negative_rc, expected 3" >&2
  exit 1
fi
grep -Fq 'AETHERCORE ZEPHYR IRQ ARMED' "$negative_log"
grep -Fq 'AETHERCORE ZEPHYR IRQ FAIL reason=timeout' "$negative_log"
if grep -Fq 'AETHERCORE ZEPHYR IRQ PASS' "$negative_log"; then
  echo "ERROR: Zephyr Z4 no-RX probe emitted a false positive signature" >&2
  exit 1
fi

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-z4-external-interrupt-v1
image=$IMAGE
runner=$RUNNER
profile=rv32im_zicsr
interrupt_path=uart-rx,plic-meip,machine-trap,uart-isr,msgq,system-workqueue
rx_bytes=0x5a,0x34
rx_start_cycle=$RX_START_CYCLE
rx_gap_cycles=$RX_GAP_CYCLES
stall_periods=0,3
negative_probe=no-rx-timeout-exit-3
exit_code=0
EOF

sha256sum \
  "$IMAGE" \
  "$RUNNER" \
  "$EVIDENCE_DIR/positive-stall-0.log" \
  "$EVIDENCE_DIR/positive-stall-3.log" \
  "$negative_log" \
  > "$EVIDENCE_DIR/artifacts.sha256"

cat "$EVIDENCE_DIR/result.txt"
cat "$EVIDENCE_DIR/artifacts.sha256"
