#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_ROOT="${AETHERCORE_ZEPHYR_BUILD_DIR:-$ROOT/build/zephyr-stage/host-build}"
IMAGE="$BUILD_ROOT/zephyr/zephyr.bin"
SIM_BUILD="${AETHERCORE_ZEPHYR_SIM_BUILD_DIR:-$ROOT/build/zephyr-z2}"
LOG_DIR="$SIM_BUILD/evidence"
LOG_FILE="$LOG_DIR/boot.log"
MAX_CYCLES="${AETHERCORE_ZEPHYR_MAX_CYCLES:-8000000}"
STALL_PERIOD="${AETHERCORE_ZEPHYR_STALL_PERIOD:-0}"

mkdir -p "$LOG_DIR"
test -s "$IMAGE"

# Reuse the ordinary AetherCoreSimTop harness: it already models RAM, UART TX,
# semantic simulator exit, bounded execution and optional memory backpressure.
make -C "$ROOT" \
  BUILD_DIR="$SIM_BUILD" \
  sim

runner="$SIM_BUILD/obj/VAetherCoreSimTop"
test -x "$runner"

args=("$IMAGE" --max-cycles "$MAX_CYCLES" --self-check-exit)
if [[ "$STALL_PERIOD" != "0" ]]; then
  args+=(--stall-period "$STALL_PERIOD")
fi

set +e
"$runner" "${args[@]}" 2>&1 | tee "$LOG_FILE"
rc=${PIPESTATUS[0]}
set -e

if [[ $rc -ne 0 ]]; then
  echo "ERROR: Zephyr Z2 simulation failed with exit code $rc" >&2
  exit "$rc"
fi

grep -Fq 'AETHERCORE ZEPHYR BOOT' "$LOG_FILE"
grep -Fq 'AETHERCORE ZEPHYR WORKER READY' "$LOG_FILE"
grep -Fq 'AETHERCORE ZEPHYR PASS handoffs=4' "$LOG_FILE"
grep -Fq 'PASS: self-check exit code 0' "$LOG_FILE"

cat > "$LOG_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-z2-boot-console-v1
image=$IMAGE
runner=$runner
max_cycles=$MAX_CYCLES
stall_period=$STALL_PERIOD
boot_signature=AETHERCORE ZEPHYR BOOT
pass_signature=AETHERCORE ZEPHYR PASS handoffs=4
exit_code=0
EOF

sha256sum "$IMAGE" "$runner" "$LOG_FILE" > "$LOG_DIR/artifacts.sha256"
cat "$LOG_DIR/result.txt"
cat "$LOG_DIR/artifacts.sha256"
