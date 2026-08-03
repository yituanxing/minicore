#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/freertos-qualification"
LOG_DIR="$BUILD_DIR/logs"
EVIDENCE_DIR="$BUILD_DIR/difftest-evidence"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ -f "$PATH_FILE" ]] || fail "missing frozen RV32 NEMU path file: $PATH_FILE"
RV32_NEMU_SO="$(cat "$PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing frozen RV32 NEMU reference: $RV32_NEMU_SO"

mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

for stall in 0 5; do
  make -f Makefile.freertos-difftest run-difftest \
    RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD="$stall"
  log="$LOG_DIR/difftest-stall${stall}.log"
  [[ -s "$log" ]] || fail "missing FreeRTOS DiffTest log for stall=$stall"
  grep -Fq 'FREERTOS PASS queue=64 semaphore=8 ticks>=16' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Eq 'difftest=[1-9][0-9]*' "$log"
  grep -Eq 'trap-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'interrupt-shadow=[1-9][0-9]*' "$log"
  ! grep -Fq 'FAIL:' "$log"
done

make -f Makefile.freertos-difftest run-difftest-negative \
  RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD=5
negative_log="$LOG_DIR/difftest-negative-stall5.log"
[[ -s "$negative_log" ]] || fail "missing deliberate mismatch log"
grep -Fq 'RV32 timer DiffTest mismatch after 0 matched events' "$negative_log"

adapter="$BUILD_DIR/nemu_difftest_rv32_freertos.cpp"
elf="$BUILD_DIR/aethercore-freertos.elf"
binary="$BUILD_DIR/aethercore-freertos.bin"
for path in "$adapter" "$elf" "$binary"; do
  [[ -s "$path" ]] || fail "missing FreeRTOS DiffTest artifact: $path"
done

stall0_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall0.log" | tail -n 1)"
stall5_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall5.log" | tail -n 1)"
reference_sha="$(sha256sum "$RV32_NEMU_SO" | awk '{print $1}')"
adapter_sha="$(sha256sum "$adapter" | awk '{print $1}')"
elf_sha="$(sha256sum "$elf" | awk '{print $1}')"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
release=V11.3.0
profile=rv32im_zicsr_m
reference_revision=8601834e4889e6bf3b6113eb5f824ba7689126f5
reference_sha256=$reference_sha
stall_periods=0,5
negative_mismatch_at=0
adapter_sha256=$adapter_sha
elf_sha256=$elf_sha
binary_sha256=$binary_sha
stall0_summary=$stall0_summary
stall5_summary=$stall5_summary
EOF

cp "$LOG_DIR/difftest-stall0.log" "$EVIDENCE_DIR/"
cp "$LOG_DIR/difftest-stall5.log" "$EVIDENCE_DIR/"
cp "$negative_log" "$EVIDENCE_DIR/"
sha256sum "$adapter" "$elf" "$binary" "$RV32_NEMU_SO" \
  > "$EVIDENCE_DIR/artifacts.sha256"
cat "$EVIDENCE_DIR/result.txt"
