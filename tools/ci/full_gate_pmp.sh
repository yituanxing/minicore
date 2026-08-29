#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/build/ci/logs"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
BUILD_DIR="$ROOT/build/rv32imu-pmp"
SOFTWARE_DIR="$BUILD_DIR/software"

# Self-hosted runners retain their worktree between jobs. PMP evidence must never
# inherit binaries, labels, or logs from an earlier run.
rm -rf "$BUILD_DIR"
rm -f "$LOG_DIR"/rv32imu-pmp*.log
mkdir -p "$LOG_DIR" "$BUILD_DIR"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$RV32_PATH_FILE" ]] || fail "missing shared RV32 NEMU path: $RV32_PATH_FILE"
RV32_NEMU_SO="$(cat "$RV32_PATH_FILE")"
[[ -f "$RV32_NEMU_SO" ]] || fail "missing shared RV32 NEMU reference: $RV32_NEMU_SO"

make -f Makefile.rv32imu-pmp software contract

checkout_sha="$(git rev-parse HEAD)"
github_head_sha="$checkout_sha"
if [[ -n "${GITHUB_EVENT_PATH:-}" && -f "$GITHUB_EVENT_PATH" ]]; then
  event_head_sha="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("pull_request", {}).get("head", {}).get("sha", ""))' "$GITHUB_EVENT_PATH")"
  if [[ -n "$event_head_sha" ]]; then
    github_head_sha="$event_head_sha"
  fi
fi

{
  printf 'git_head=%s\n' "$checkout_sha"
  printf 'checkout_sha=%s\n' "$checkout_sha"
  printf 'github_sha=%s\n' "${GITHUB_SHA:-local}"
  printf 'github_head_sha=%s\n' "$github_head_sha"
  printf 'github_head_ref=%s\n' "${GITHUB_HEAD_REF:-local}"
  printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-local}"
  printf 'github_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-local}"
  printf 'nemu_so=%s\n' "$RV32_NEMU_SO"
  sha256sum \
    "$SOFTWARE_DIR/pmp-isolation.bin" \
    "$SOFTWARE_DIR/labels.txt" \
    "$SOFTWARE_DIR/manifest.txt" \
    "$SOFTWARE_DIR/contract.txt"
} > "$SOFTWARE_DIR/provenance.txt"
cat "$SOFTWARE_DIR/provenance.txt"

grep -q '^march=rv32im_zicsr$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^mabi=ilp32$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^privileges=MU$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^hardware_pmp_entries=16$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmp_shadow_entries=4$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmp_entries_configured=0,1,2$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmp_mode=TOR$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^pmpcfg0=0x000b0d08$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^kernel_region=0x80000000-0x80001000:---$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_text_region=0x80001000-0x80002000:r-x$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_data_region=0x80002000-0x80003000:rw-$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^user_default=deny$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^expected_fault_stages=6$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^message=PMP isolation via SYS_WRITE$' "$SOFTWARE_DIR/manifest.txt"
grep -q '^attack_byte=0x40$' "$SOFTWARE_DIR/manifest.txt"

grep -q '^status=PASS$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_start=0x80000000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_end=0x80001000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_text_start=0x80001000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_text_end=0x80002000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_data_start=0x80002000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_data_end=0x80003000$' "$SOFTWARE_DIR/contract.txt"
grep -q '^pmp_csr_writes=4$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_store_sites=5$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_load_sites=3$' "$SOFTWARE_DIR/contract.txt"
grep -q '^user_jalr_attack_sites=2$' "$SOFTWARE_DIR/contract.txt"
grep -q '^kernel_sys_write_store_sites=1$' "$SOFTWARE_DIR/contract.txt"
grep -q '^expected_fault_stages=6$' "$SOFTWARE_DIR/contract.txt"

make -f Makefile.rv32imu-pmp local-reference \
  2>&1 | tee "$LOG_DIR/rv32imu-pmp-local.log"
make -f Makefile.rv32imu-pmp run RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-pmp.log"
make -f Makefile.rv32imu-pmp mismatch-probe RV32_NEMU_SO="$RV32_NEMU_SO" \
  2>&1 | tee "$LOG_DIR/rv32imu-pmp-mismatch-probe.log"

for stall in 0 5; do
  log="$BUILD_DIR/logs/stall-$stall.log"
  test "$(grep -c '^PMP isolation via SYS_WRITE$' "$log")" -eq 1
  ! grep -Fq '@' "$log"
  grep -Fq 'PASS: self-check exit=0' "$log"
  grep -Fq 'trap-shadow=9' "$log"
  grep -Fq 'mret-shadow=9' "$log"
done
grep -q 'RV32 DiffTest mismatch after 0 matched events' "$BUILD_DIR/logs/mismatch.log"
grep -q 'x31' "$BUILD_DIR/logs/mismatch.log"

echo "PASS: RV32IMU PMP16 hardware with frozen four-entry isolation shadow gate"
