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
EXPECTED_ADAPTER_SHA256="ef8871db8119ec7ae3091e289f55c8c621042fd44d044bfb746028572d616810"
EXPECTED_RUNNER_SHA256="91f8831718240acdc6541f0b749ba3983d4627487fbebe0d056e5f19c0794ab9"
EXPECTED_ELF_SHA256="81870780703da81e49d521f8137d8ec2f695d8fc0f7e6bd86aeb96b6fca91396"
EXPECTED_BINARY_SHA256="eb98b161627a9226bd3fe94077d714aedbb116f85bca875dcd5c43e5613468df"
EXPECTED_STALL0_SUMMARY="PASS: self-check exit=0 after 271664 cycles, 168046 committed instructions, wfi-commits=1, masked-wfi-commits=1, wfi-sleep-cycles=30199, uart-rx-injected=0, external-seen=0, difftest=168046, zicsr-shadow=3191, trap-shadow=220, fence-shadow=1, wfi-shadow=1, mret-shadow=360, interrupt-shadow=140"
EXPECTED_STALL5_SUMMARY="PASS: self-check exit=0 after 294180 cycles, 169735 committed instructions, wfi-commits=1, masked-wfi-commits=1, wfi-sleep-cycles=30059, uart-rx-injected=0, external-seen=0, stall-period=5, difftest=169735, zicsr-shadow=3259, trap-shadow=219, fence-shadow=1, wfi-shadow=1, mret-shadow=374, interrupt-shadow=155"

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

rm -rf "$BUILD_DIR" "$EVIDENCE_DIR"
mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

for stall in 0 5; do
  make -j"$JOBS" -f Makefile.freertos-difftest run-difftest \
    BUILD_DIR="$BUILD_DIR" JOBS="$JOBS" TRACE=0 \
    RV32_NEMU_SO="$RV32_NEMU_SO" STALL_PERIOD="$stall"
  log="$LOG_DIR/difftest-stall${stall}.log"
  [[ -s "$log" ]] || fail "missing FreeRTOS DiffTest log for stall=$stall"
  grep -Fxq 'FREERTOS TICKLESS PASS sleep>=1 wake>=1 suppressed>=2' "$log"
  grep -Fxq 'FREERTOS PASS queue=64 semaphore=8 ticks>=48' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Eq 'wfi-commits=[1-9][0-9]*' "$log"
  grep -Eq 'masked-wfi-commits=[1-9][0-9]*' "$log"
  grep -Eq 'wfi-sleep-cycles=[1-9][0-9]*' "$log"
  grep -Eq 'difftest=[1-9][0-9]*' "$log"
  grep -Eq 'zicsr-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'trap-shadow=[1-9][0-9]*' "$log"
  grep -Eq 'fence-shadow=[1-9][0-9]*' "$log"
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
  require_equal "FreeRTOS tickless DiffTest stall=$stall architectural summary" \
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
  [[ -s "$path" ]] || fail "missing FreeRTOS tickless DiffTest artifact: $path"
done

stall0_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall0.log" | tail -n 1)"
stall5_summary="$(grep -F 'PASS: self-check exit=0' "$LOG_DIR/difftest-stall5.log" | tail -n 1)"
adapter_sha="$(sha256sum "$adapter" | awk '{print $1}')"
runner_sha="$(sha256sum "$runner" | awk '{print $1}')"
elf_sha="$(sha256sum "$elf" | awk '{print $1}')"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"

require_equal "generated FreeRTOS tickless DiffTest adapter SHA256" \
  "$adapter_sha" "$EXPECTED_ADAPTER_SHA256"
require_equal "generated FreeRTOS tickless DiffTest runner SHA256" \
  "$runner_sha" "$EXPECTED_RUNNER_SHA256"
require_equal "FreeRTOS tickless ELF SHA256" "$elf_sha" "$EXPECTED_ELF_SHA256"
require_equal "FreeRTOS tickless binary SHA256" "$binary_sha" "$EXPECTED_BINARY_SHA256"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
contract=freertos-rv32-exact-difftest-tickless-v1
release=V11.3.0
profile=rv32im_zicsr_m
reference_revision=8601834e4889e6bf3b6113eb5f824ba7689126f5
reference_sha256=$reference_sha
trace=0
parallel_jobs=$JOBS
stall_periods=0,5
tickless_idle=true
masked_wfi_wake=true
fence_shadow=true
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
