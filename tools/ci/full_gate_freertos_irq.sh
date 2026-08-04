#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD_DIR="$ROOT/build/freertos-irq-qualification"
LOG_DIR="$ROOT/build/ci/logs"
EVIDENCE_DIR="$BUILD_DIR/evidence"
JOBS="${AETHERCORE_JOBS:-0}"
if [[ "$JOBS" == "0" ]]; then
  JOBS="$(nproc)"
fi
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || {
  echo "ERROR: invalid AETHERCORE_JOBS=$JOBS" >&2
  exit 1
}

rm -rf "$BUILD_DIR"
mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

set -o pipefail
make -j"$JOBS" -f Makefile.freertos-irq \
  BUILD_DIR="$BUILD_DIR" JOBS="$JOBS" TRACE=0 run-irq-local \
  2>&1 | tee "$LOG_DIR/freertos-irq-matrix.log"

contract="$BUILD_DIR/port-contract.json"
elf="$BUILD_DIR/aethercore-freertos.elf"
binary="$BUILD_DIR/aethercore-freertos.bin"
disassembly="$BUILD_DIR/aethercore-freertos.dis"
attributes="$BUILD_DIR/aethercore-freertos.attributes"

for path in "$contract" "$elf" "$binary" "$disassembly" "$attributes"; do
  test -s "$path"
done

grep -q '"status": "PASS"' "$contract"
grep -q '<freertos_risc_v_trap_handler>:' "$disassembly"
grep -q '<xEventGroupWaitBits>:' "$disassembly"
grep -q '<xTimerCreateTimerTask>:' "$disassembly"
grep -q '<xStreamBufferSend>:' "$disassembly"
grep -q '<xStreamBufferReceive>:' "$disassembly"
grep -q '\bmret\b' "$disassembly"
grep -q '\bwfi\b' "$disassembly"

for stall in 0 1 3 5 7; do
  log="$BUILD_DIR/logs/irq-stall-$stall.log"
  test -s "$log"
  grep -Fq 'FREERTOS EVENT GROUP PASS all=3 clear=1' "$log"
  grep -Fq 'FREERTOS SOFTWARE TIMER PASS one-shot=1 daemon=1' "$log"
  grep -Fq 'FREERTOS STREAM BUFFER PASS bytes=8 handoff=1' "$log"
  grep -Fq 'FREERTOS MESSAGE BUFFER PASS bytes=7 handoff=1' "$log"
  grep -Fq 'FREERTOS MUTEX PASS inherited=4' "$log"
  grep -Fq 'FREERTOS IRQ PASS queue=1 semaphore=1 notify=1 claim=1 yield>=1 early>=1' "$log"
  grep -Fq 'FREERTOS TICKLESS PASS sleep>=1 wake>=1 suppressed>=2' "$log"
  grep -Fq "stall-period=$stall" "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  ! grep -Fq 'FAIL:' "$log"
done

wrong_log="$BUILD_DIR/logs/irq-negative-wrong-byte.log"
missing_log="$BUILD_DIR/logs/irq-negative-missing-event.log"
test -s "$wrong_log"
test -s "$missing_log"
grep -Fq 'FREERTOS_ASSERT' "$wrong_log"
grep -Fq 'FAIL: self-check program returned code' "$wrong_log"
grep -Fq 'FAIL: timeout after 600000 cycles' "$missing_log"
grep -Fq 'uart-rx-injected=0' "$missing_log"
grep -Fq 'external=0' "$missing_log"

binary_sha="$(sha256sum "$binary" | awk '{print $1}')"
binary_bytes="$(stat -c %s "$binary")"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=freertos-rv32-machine-external-unified-v1
profile=rv32im_zicsr_m
machine_external_interrupt=true
plic_sources=8
uart_rx_source=1
isr_queue=true
isr_binary_semaphore=true
isr_task_notification=true
port_yield_from_isr=true
mutex_priority_inheritance=true
event_groups=true
software_timers=true
stream_buffers=true
message_buffers=true
tickless_external_early_wake=true
stall_periods=0,1,3,5,7
negative_wrong_byte=true
negative_missing_external_event=true
parallel_jobs=$JOBS
binary_bytes=$binary_bytes
binary_sha256=$binary_sha
EOF

cp "$contract" "$EVIDENCE_DIR/port-contract.json"
cp "$attributes" "$EVIDENCE_DIR/elf-attributes.txt"
sha256sum "$elf" "$binary" > "$EVIDENCE_DIR/artifacts.sha256"
sha256sum "$BUILD_DIR"/logs/*.log > "$EVIDENCE_DIR/logs.sha256"
cat "$EVIDENCE_DIR/result.txt"
