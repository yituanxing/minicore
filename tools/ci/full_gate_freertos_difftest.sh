#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/freertos-qualification"
LOG_DIR="$BUILD_DIR/logs"
EVIDENCE_DIR="$BUILD_DIR/difftest-evidence"

EXPECTED_REFERENCE_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"
EXPECTED_ADAPTER_SHA256="abe83bef060a77198cfd5f511195ad5501dcae000523cd8425dc49fd4153434c"
EXPECTED_ELF_SHA256="6ed3b12aecb4c117ff91494b72c32cba05315108506e0031d5d738f8d6a1a89b"
EXPECTED_BINARY_SHA256="9ed834712909c0f51caf0d00bec032a35bd170a2dea442c11f862dc95a683f28"
EXPECTED_STALL0_SUMMARY="PASS: self-check exit=0 after 238306 cycles, 166011 committed instructions, difftest=166011, zicsr-shadow=3155, trap-shadow=219, mret-shadow=356, interrupt-shadow=137"
EXPECTED_STALL5_SUMMARY="PASS: self-check exit=0 after 256013 cycles, 164650 committed instructions, stall-period=5, difftest=164650, zicsr-shadow=3163, trap-shadow=213, mret-shadow=360, interrupt-shadow=147"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_equal() {
  local label="$1"
  local actual="$2"
  local expected="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'ERROR: %s changed\n' "$label" >&2
    printf '  expected: %s\n' "$expected" >&2
    printf '  actual:   %s\n' "$actual" >&2
    exit 1
  fi
}

[[ -f "$PATH_FILE" ]] || fail "missing frozen RV32 NEMU path file: $PATH_FILE"
RV32_NEMU_SO="$(cat "$PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing frozen RV32 NEMU reference: $RV32_NEMU_SO"
reference_sha="$(sha256sum "$RV32_NEMU_SO" | awk '{print $1}')"
require_equal "frozen single-step RV32 NEMU SHA256" \
  "$reference_sha" "$EXPECTED_REFERENCE_SHA256"

mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

for stall in 0 5; do
  make -f Makefile.freertos-difftest run-difftest \
    RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD="$stall"
  log="$LOG_DIR/difftest-stall${stall}.log"
  [[ -s "$log" ]] || fail "missing FreeRTOS DiffTest log for stall=$stall"
  grep -Fq 'FREERTOS PASS queue=64 semaphore=8 ticks>=16' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Eq 'difftest=[1-9][0-9]*' "$log"
  grep -Eq 'zicsr-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'trap-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'mret-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'interrupt-shadow=[1-9][0-9]*' "$log"
  ! grep -Fq 'FAIL:' "$log"

  actual_summary="$(grep -F 'PASS: self-check exit=0' "$log" | tail -n 1)"
  case "$stall" in
    0) expected_summary="$EXPECTED_STALL0_SUMMARY" ;;
    5) expected_summary="$EXPECTED_STALL5_SUMMARY" ;;
    *) fail "unqualified FreeRTOS DiffTest stall profile: $stall" ;;
  esac
  require_equal "FreeRTOS DiffTest stall=$stall architectural summary" \
    "$actual_summary" "$expected_summary"
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
adapter_sha="$(sha256sum "$adapter" | awk '{print $1}')"
elf_sha="$(sha256sum "$elf" | awk '{print $1}')"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"

require_equal "generated FreeRTOS DiffTest adapter SHA256" \
  "$adapter_sha" "$EXPECTED_ADAPTER_SHA256"
require_equal "FreeRTOS ELF SHA256" "$elf_sha" "$EXPECTED_ELF_SHA256"
require_equal "FreeRTOS binary SHA256" "$binary_sha" "$EXPECTED_BINARY_SHA256"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=freertos-rv32-exact-difftest-v1
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
