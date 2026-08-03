#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD_DIR="$ROOT/build/freertos-qualification"
LOG_DIR="$ROOT/build/ci/logs"
EVIDENCE_DIR="$BUILD_DIR/evidence"
LOCK_FILE="$ROOT/software/freertos/FreeRTOS-Kernel.lock"

rm -rf "$BUILD_DIR"
mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

set -o pipefail
make -f Makefile.freertos run-local \
  2>&1 | tee "$LOG_DIR/freertos-rv32-local.log"

contract="$BUILD_DIR/port-contract.json"
elf="$BUILD_DIR/aethercore-freertos.elf"
binary="$BUILD_DIR/aethercore-freertos.bin"
disassembly="$BUILD_DIR/aethercore-freertos.dis"
attributes="$BUILD_DIR/aethercore-freertos.attributes"

for path in "$contract" "$elf" "$binary" "$disassembly" "$attributes"; do
  test -s "$path"
done

grep -q '"status": "PASS"' "$contract"
grep -q '"release": "V11.3.0"' "$contract"
grep -q '"mhartid": 0' "$contract"
grep -q '<freertos_risc_v_trap_handler>:' "$disassembly"
grep -Eq '\bcsrr[[:space:]].*mhartid' "$disassembly"
grep -q '\bmret\b' "$disassembly"
grep -Fq 'FREERTOS BOOT V11.3.0 RV32IM' "$LOG_DIR/freertos-rv32-local.log"
grep -Fq 'FREERTOS PASS queue=64 semaphore=8 ticks>=16' "$LOG_DIR/freertos-rv32-local.log"
grep -Fq 'PASS: self-check exit=0' "$LOG_DIR/freertos-rv32-local.log"
! grep -Fq 'FAIL:' "$LOG_DIR/freertos-rv32-local.log"

revision="$(sed -n 's/^revision=//p' "$LOCK_FILE")"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"
binary_bytes="$(stat -c %s "$binary")"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
release=V11.3.0
revision=$revision
profile=rv32im_zicsr_m
mhartid=0
mtime=0x0200bff8
mtimecmp=0x02004000
workload_messages=64
workload_semaphore_batches=8
minimum_ticks=16
binary_bytes=$binary_bytes
binary_sha256=$binary_sha
local_stall_period=5
EOF

cp "$LOCK_FILE" "$EVIDENCE_DIR/FreeRTOS-Kernel.lock"
cp "$contract" "$EVIDENCE_DIR/port-contract.json"
cp "$attributes" "$EVIDENCE_DIR/elf-attributes.txt"
sha256sum "$elf" "$binary" > "$EVIDENCE_DIR/artifacts.sha256"
cat "$EVIDENCE_DIR/result.txt"
