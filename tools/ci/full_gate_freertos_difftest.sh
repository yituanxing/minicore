#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
QUALIFICATION_ROOT="$ROOT/build/freertos-qualification"
BUILD_DIR="$QUALIFICATION_ROOT/difftest-build"
LOG_DIR="$BUILD_DIR/logs"
EVIDENCE_DIR="$QUALIFICATION_ROOT/difftest-evidence"
JOBS="${AETHERCORE_JOBS:-0}"
if [[ "$JOBS" == "0" ]]; then
  JOBS="$(nproc)"
fi
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || {
  printf 'ERROR: invalid AETHERCORE_JOBS=%s\n' "$JOBS" >&2
  exit 1
}

EXPECTED_REFERENCE_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"
EXPECTED_ADAPTER_SHA256="2ff3fa8f3c2cfc1005d7edd0b704c636d48c7ef042c002298568faad6c9aadd4"
EXPECTED_RUNNER_SHA256="e9446402b2d9f51aa636438badc7bbb338376194abd01b968a3b6d842f764744"
EXPECTED_ELF_SHA256="21a73cec3f2923708117fb71d5cd4339f22d5ed08b4994314b2c1a3b3cd242a0"
EXPECTED_BINARY_SHA256="ddb1cb7e8b50687de1ef450ff85392bbfa5987cfc4b2a93f727d5dd2e08e9572"
EXPECTED_STALL0_SUMMARY="PASS: self-check exit=0 after 242170 cycles, 167808 committed instructions, wfi-commits=2, wfi-sleep-cycles=1332, difftest=167808, zicsr-shadow=3194, trap-shadow=220, wfi-shadow=2, mret-shadow=361, interrupt-shadow=141"
EXPECTED_STALL5_SUMMARY="PASS: self-check exit=0 after 260658 cycles, 166608 committed instructions, wfi-commits=3, wfi-sleep-cycles=1693, stall-period=5, difftest=166608, zicsr-shadow=3207, trap-shadow=214, wfi-shadow=3, mret-shadow=366, interrupt-shadow=152"

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

# The local FreeRTOS stage keeps TRACE=1 and its waveform-capable simulator.
# Exact architectural qualification uses a separate clean TRACE=0 directory so
# its generated runner and hashes cannot depend on stale local-build outputs.
rm -rf "$BUILD_DIR" "$EVIDENCE_DIR"
mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

for stall in 0 5; do
  make -j"$JOBS" -f Makefile.freertos-difftest run-difftest \
    BUILD_DIR="$BUILD_DIR" JOBS="$JOBS" TRACE=0 \
    RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD="$stall"
  log="$LOG_DIR/difftest-stall${stall}.log"
  [[ -s "$log" ]] || fail "missing FreeRTOS DiffTest log for stall=$stall"
  grep -Fxq 'FREERTOS IDLE PASS wfi>=1 wake>=1' "$log"
  grep -Fxq 'FREERTOS PASS queue=64 semaphore=8 ticks>=16' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Eq 'wfi-commits=[1-9][0-9]*' "$log"
  grep -Eq 'wfi-sleep-cycles=[1-9][0-9]*' "$log"
  grep -Eq 'difftest=[1-9][0-9]*' "$log"
  grep -Eq 'zicsr-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'trap-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'wfi-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'mret-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'interrupt-shadow=[1-9][0-9]*' "$log"
  ! grep -Fq 'instruction retired while WFI sleep was asserted' "$log"
  ! grep -Fq 'FAIL:' "$log"

  actual_summary="$(grep -F 'PASS: self-check exit=0' "$log" | tail -n 1)"
  case "$stall" in
    0) expected_summary="$EXPECTED_STALL0_SUMMARY" ;;
    5) expected_summary="$EXPECTED_STALL5_SUMMARY" ;;
    *) fail "unqualified FreeRTOS DiffTest stall profile: $stall" ;;
  esac
  require_equal "FreeRTOS WFI DiffTest stall=$stall architectural summary" \
    "$actual_summary" "$expected_summary"
done

make -j"$JOBS" -f Makefile.freertos-difftest run-difftest-negative \
  BUILD_DIR="$BUILD_DIR" JOBS="$JOBS" TRACE=0 \
  RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD=5
negative_log="$LOG_DIR/difftest-negative-stall5.log"
[[ -s "$negative_log" ]] || fail "missing deliberate mismatch log"
grep -Fq 'RV32 timer DiffTest mismatch after 0 matched events' "$negative_log"

adapter="$BUILD_DIR/nemu_difftest_rv32_freertos_wfi.cpp"
runner="$BUILD_DIR/sim_main_rv32im_freertos_difftest.cpp"
elf="$BUILD_DIR/aethercore-freertos.elf"
binary="$BUILD_DIR/aethercore-freertos.bin"
for path in "$adapter" "$runner" "$elf" "$binary"; do
  [[ -s "$path" ]] || fail "missing FreeRTOS WFI DiffTest artifact: $path"
done

stall0_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall0.log" | tail -n 1)"
stall5_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall5.log" | tail -n 1)"
adapter_sha="$(sha256sum "$adapter" | awk '{print $1}')"
runner_sha="$(sha256sum "$runner" | awk '{print $1}')"
elf_sha="$(sha256sum "$elf" | awk '{print $1}')"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"

require_equal "generated FreeRTOS WFI DiffTest adapter SHA256" \
  "$adapter_sha" "$EXPECTED_ADAPTER_SHA256"
require_equal "generated FreeRTOS WFI DiffTest runner SHA256" \
  "$runner_sha" "$EXPECTED_RUNNER_SHA256"
require_equal "FreeRTOS WFI ELF SHA256" "$elf_sha" "$EXPECTED_ELF_SHA256"
require_equal "FreeRTOS WFI binary SHA256" "$binary_sha" "$EXPECTED_BINARY_SHA256"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=freertos-rv32-exact-difftest-wfi-v1
release=V11.3.0
profile=rv32im_zicsr_m
reference_revision=8601834e4889e6bf3b6113eb5f824ba7689126f5
reference_sha256=$reference_sha
trace=0
parallel_jobs=$JOBS
stall_periods=0,5
wfi_shadow=true
wfi_quiescence=true
negative_mismatch_at=0
adapter_sha256=$adapter_sha
runner_sha256=$runner_sha
elf_sha256=$elf_sha
binary_sha256=$binary_sha
stall0_summary=$stall0_summary
stall5_summary=$stall5_summary
EOF

cp "$LOG_DIR/difftest-stall0.log" "$EVIDENCE_DIR/"
cp "$LOG_DIR/difftest-stall5.log" "$EVIDENCE_DIR/"
cp "$negative_log" "$EVIDENCE_DIR/"
cp "$adapter" "$EVIDENCE_DIR/"
cp "$runner" "$EVIDENCE_DIR/"
cp "$elf" "$EVIDENCE_DIR/"
cp "$binary" "$EVIDENCE_DIR/"
reference_evidence="$(dirname "$RV32_NEMU_SO")/evidence/result.txt"
if [[ -s "$reference_evidence" ]]; then
  cp "$reference_evidence" "$EVIDENCE_DIR/rv32-nemu-result.txt"
fi
sha256sum "$adapter" "$runner" "$elf" "$binary" "$RV32_NEMU_SO" \
  > "$EVIDENCE_DIR/artifacts.sha256"
cat "$EVIDENCE_DIR/result.txt"
