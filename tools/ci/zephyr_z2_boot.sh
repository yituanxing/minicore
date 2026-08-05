#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_ROOT="${AETHERCORE_ZEPHYR_BUILD_DIR:-$ROOT/build/zephyr-stage/host-build}"
IMAGE="$BUILD_ROOT/zephyr/zephyr.bin"
SIM_BUILD="${AETHERCORE_ZEPHYR_SIM_BUILD_DIR:-$ROOT/build/zephyr-z2}"
RTL_DIR="$SIM_BUILD/rtl"
OBJ_DIR="$SIM_BUILD/obj"
LOG_DIR="$SIM_BUILD/evidence"
LOG_FILE="$LOG_DIR/boot.log"
MAX_CYCLES="${AETHERCORE_ZEPHYR_MAX_CYCLES:-8000000}"
STALL_PERIOD="${AETHERCORE_ZEPHYR_STALL_PERIOD:-0}"
COMMIT_TRACE="${AETHERCORE_ZEPHYR_COMMIT_TRACE:-0}"

mkdir -p "$RTL_DIR" "$OBJ_DIR" "$LOG_DIR"
test -s "$IMAGE"

# Zephyr must run on the actual RV32IM+Zicsr profile. Unlike the short smoke
# harness, an OS must also continue across synchronous traps and timer
# interrupts instead of treating the first observed trap as terminal.
"$ROOT/mill" aethercore.runMain aethercore.ElaborateZephyr --target-dir "$RTL_DIR"
mapfile -t rtl_sources < <(find "$RTL_DIR" -maxdepth 1 -type f -name '*.sv' -print | sort)
if (( ${#rtl_sources[@]} == 0 )); then
  echo "ERROR: no generated Zephyr SystemVerilog sources" >&2
  exit 1
fi

verilator --cc --exe --build --trace -Wall -Wno-fatal \
  --top-module AetherCoreSimTop -Mdir "$OBJ_DIR" \
  -CFLAGS "-std=c++20 -O2" -LDFLAGS "-ldl" \
  "${rtl_sources[@]}" "$ROOT/sim/sim_main.cpp" "$ROOT/sim/nemu_difftest.cpp"

runner="$OBJ_DIR/VAetherCoreSimTop"
test -x "$runner"

# Keep the normal qualification log as a clean UART stream.  Commit tracing is
# still available for focused diagnosis, but enabling it interleaves trace
# records with individual UART bytes and makes whole-line signature checks
# unreliable even when the guest has completed successfully.
args=("$IMAGE" --max-cycles "$MAX_CYCLES" --self-check-exit)
if [[ "$COMMIT_TRACE" == "1" ]]; then
  args+=(--commit-trace)
fi
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
grep -Fq 'PASS: self-check exit=0' "$LOG_FILE"

cat > "$LOG_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-z2-boot-console-v1
image=$IMAGE
runner=$runner
max_cycles=$MAX_CYCLES
stall_period=$STALL_PERIOD
commit_trace=$COMMIT_TRACE
profile=rv32im_zicsr
stop_on_trap=false
boot_signature=AETHERCORE ZEPHYR BOOT
pass_signature=AETHERCORE ZEPHYR PASS handoffs=4
exit_code=0
EOF

sha256sum "$IMAGE" "$runner" "$LOG_FILE" > "$LOG_DIR/artifacts.sha256"
cat "$LOG_DIR/result.txt"
cat "$LOG_DIR/artifacts.sha256"
