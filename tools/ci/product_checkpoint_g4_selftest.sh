#!/usr/bin/env bash
set -euo pipefail

# Cheap fail-closed contract checks. No RTL build and no Linux guest cycles.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SCRIPT="$ROOT/tools/ci/product_checkpoint_g4_short.sh"
TMP="$(mktemp -d)"
trap 'rm -r -- "$TMP"' EXIT

reject() {
  local label="$1"
  shift
  if "$@" > "$TMP/result.log" 2>&1; then
    echo "PRODUCT_G4_PERSISTENT_SELFTEST_FAIL: unexpectedly accepted $label" >&2
    exit 1
  fi
  if ! grep -Eq 'PRODUCT_G4_PERSISTENT_(REJECTED|PROOF_REJECTED)' "$TMP/result.log"; then
    echo "PRODUCT_G4_PERSISTENT_SELFTEST_FAIL: wrong failure mode $label" >&2
    sed -n '1,12p' "$TMP/result.log" >&2
    exit 1
  fi
}

reject no_opt_in env -u AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT bash "$SCRIPT" build
reject sd_image env AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_SD_IMAGE=forbidden.img bash "$SCRIPT" run
reject independent_dtb env AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_DTB_IMAGE=forbidden.dtb bash "$SCRIPT" run
reject wrong_version env AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_VERILATOR_VERSION=5.024 bash "$SCRIPT" run
reject adaptive_settle env AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 \
  SIM_SETTLE_MODE=adaptive bash "$SCRIPT" run
reject nonzero_memory_wait env AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_DATA_MEM_WAIT_CYCLES=2 bash "$SCRIPT" run
reject missing_proof bash "$SCRIPT" verify-proof "$TMP/nonexistent"

# Same textual result must not certify a checkpoint without 256MiB host DDR.
mkdir -p "$TMP/forged"
printf 'complete=1\ntop=AetherCoreProductOpenSbiRV64SimTop\nreplay_cycles=500000\nmilestone=Linux version 6.6.143\nmodel_sha256=%064d\nfirmware_sha256=%064d\nlinux_image_sha256=%064d\n' \
  0 0 0 > "$TMP/forged/.complete"
printf 'PRODUCT_G4_PERSISTENT_CHECKPOINT_SAVED opensbi=1 linux_banner=1\nPRODUCT_G4_PERSISTENT_CONTINUATION linux_banner=1 opensbi_banner=1 end=42\n' \
  > "$TMP/forged/save.log"
printf 'PRODUCT_G4_PERSISTENT_CHECKPOINT_RESTORED opensbi=1 linux_banner=1\nPRODUCT_G4_PERSISTENT_CONTINUATION linux_banner=1 opensbi_banner=1 end=42\n' \
  > "$TMP/forged/restore.log"
for state in pre.rtl pre.host continuous.rtl continuous.host restored.rtl restored.host; do
  printf 'not-real-RTL-state' > "$TMP/forged/persistent.$state"
done
(cd "$TMP/forged" && sha256sum persistent.* > sha256.txt)
reject forged_tiny_host bash "$SCRIPT" verify-proof "$TMP/forged"

# Compile the actual new G4 host code against deliberately tiny, synthetic
# Verilated port/API declarations. This is strictly a syntax/type gate, NOT
# an RTL build, snapshot restoration or Linux execution claim.
STUB="$ROOT/tools/ci/fixtures/product_checkpoint_g4_syntax"
SOURCE="$ROOT/sim/product_rv64_checkpoint_g4_main.cpp"
COMMON=(-std=c++20 -O2 -Wall -Wextra -Werror -I"$STUB" -I"$ROOT/sim" -fsyntax-only)
"${CXX:-g++}" "${COMMON[@]}" -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE "$SOURCE"
if "${CXX:-g++}" "${COMMON[@]}" "$SOURCE" > "$TMP/syntax.log" 2>&1; then
  echo 'PRODUCT_G4_PERSISTENT_SELFTEST_FAIL: accepted non-savable build' >&2
  exit 1
fi
for forbidden in AETHERCORE_PRODUCT_SPI_SD AETHERCORE_PRODUCT_DEBIAN; do
  if "${CXX:-g++}" "${COMMON[@]}" -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
      -D"$forbidden" "$SOURCE" > "$TMP/syntax.log" 2>&1; then
    echo "PRODUCT_G4_PERSISTENT_SELFTEST_FAIL: accepted $forbidden" >&2
    exit 1
  fi
done

echo 'PRODUCT_G4_PERSISTENT_SELFTEST_PASS opt_in=REJECT sd1=REJECT dtb=REJECT wrong_engine=REJECT fake_ram=REJECT host_CXX_syntax=PASS forbidden_cpp_macros=REJECT guest_cycles=0'
