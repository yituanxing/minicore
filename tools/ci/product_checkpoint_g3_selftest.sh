#!/usr/bin/env bash
set -euo pipefail

# Host-only negative tests: cheap enough on every change. These do NOT replace
# a generated 5.054 --savable model plus a real cross-process 500k RTL replay.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SCRIPT="$ROOT/tools/ci/product_checkpoint_g3_short.sh"
TEST_DIR="$(mktemp -d)"
trap 'rm -r -- "$TEST_DIR"' EXIT

reject() {
  local label="$1"
  shift
  if "$@" > "$TEST_DIR/check.log" 2>&1; then
    echo "PRODUCT_G3_PERSISTENT_SELFTEST_FAIL: accepted $label" >&2
    exit 1
  fi
  if ! grep -Eq 'PRODUCT_G3_PERSISTENT_(REJECTED|PROOF_REJECTED)' "$TEST_DIR/check.log"; then
    echo "PRODUCT_G3_PERSISTENT_SELFTEST_FAIL: $label failed for unexpected reason" >&2
    sed -n '1,15p' "$TEST_DIR/check.log" >&2
    exit 1
  fi
}

reject no_opt_in env -u AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT bash "$SCRIPT" build
reject sd_media env AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_SD_IMAGE=forbidden.img bash "$SCRIPT" run
reject wrong_timing env AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_DATA_MEM_WAIT_CYCLES=1 bash "$SCRIPT" run
reject wrong_engine env AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_VERILATOR_VERSION=5.024 bash "$SCRIPT" run
reject nonexistent_proof bash "$SCRIPT" verify-proof "$TEST_DIR/no-such-proof"

# A few forged banner lines and same-ending trace do not prove live Product
# RTL and the full 256-MiB DDR were captured.
mkdir -p "$TEST_DIR/forged"
printf 'complete=1\nreplay_cycles=500000\nmodel_sha256=%064d\nfirmware_sha256=%064d\n' \
  0 0 > "$TEST_DIR/forged/.complete"
printf 'PRODUCT_G3_PERSISTENT_CHECKPOINT_SAVED cycles=1\nPRODUCT_G3_PERSISTENT_CONTINUATION cycles=2\n' \
  > "$TEST_DIR/forged/save.log"
printf 'PRODUCT_G3_PERSISTENT_CHECKPOINT_RESTORED cycles=1\nPRODUCT_G3_PERSISTENT_CONTINUATION cycles=2\n' \
  > "$TEST_DIR/forged/restore.log"
for state in pre.rtl pre.host continuous.rtl continuous.host restored.rtl restored.host; do
  printf 'synthetic-data-is-not-real-cpu-state' > "$TEST_DIR/forged/persistent.$state"
done
(cd "$TEST_DIR/forged" && sha256sum persistent.* > sha256.txt)
reject forged_short_host bash "$SCRIPT" verify-proof "$TEST_DIR/forged"

echo 'PRODUCT_G3_PERSISTENT_SELFTEST_PASS opt_in=REJECT sd1=REJECT bad_config=REJECT forged_state=REJECT real_RTL_pending=1'
