#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SCRIPT=tools/ci/product_checkpoint_g5_short.sh
bash -n "$SCRIPT"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

rejects() {
  local label="$1"; shift
  if "$@" > "$tmp/$label.log" 2>&1; then
    echo "PRODUCT_G5_PERSISTENT_SELFTEST_FAIL: unexpectedly accepted $label" >&2
    exit 1
  fi
  grep -Eq 'PRODUCT_G5_PERSISTENT_(REJECTED|PROOF_REJECTED)' "$tmp/$label.log" || {
    echo "PRODUCT_G5_PERSISTENT_SELFTEST_FAIL: wrong failure for $label" >&2
    cat "$tmp/$label.log" >&2
    exit 1
  }
}

rejects no_opt_in env -u AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT bash "$SCRIPT" run
rejects external_sd env AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_SD_IMAGE=/tmp/forbidden-sd.img bash "$SCRIPT" run
rejects independent_dtb env AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_DTB_IMAGE=/tmp/forbidden.dtb bash "$SCRIPT" run
rejects adaptive env AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT=1 \
  SIM_SETTLE_MODE=adaptive bash "$SCRIPT" run
rejects wrong_version env AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_VERILATOR_VERSION=5.024 bash "$SCRIPT" run
rejects forged_proof bash "$SCRIPT" verify-proof "$tmp"

# The source must deliberately use the Debian CPU Top, not the G4 RAM-only
# OpenSBI Top or SD1 card, and cannot publish a PID1 success marker.
grep -Fxq 'TOP=AetherCoreProductDebianRV64SimTop' "$SCRIPT"
grep -Fq 'AETHERCORE_PRODUCT_CHECKPOINT_G5' "$SCRIPT"
grep -Fq 'pid1_qualified=0' "$SCRIPT"
grep -Fq 'AETG5CP1' sim/product_rv64_checkpoint_g4_main.cpp
! grep -Fq 'PRODUCT_G5_DEBIAN_DYNAMIC_PID1_PASS' "$SCRIPT"

# Reuse the G4 synthetic transport/Verilator declarations but require the
# *different* Debian Top type and G5 compile-time ownership. Syntax-only, not
# actual RTL elaboration or an independence proof.
G4_STUB="$ROOT/tools/ci/fixtures/product_checkpoint_g4_syntax"
G5_STUB="$ROOT/tools/ci/fixtures/product_checkpoint_g5_syntax"
SOURCE="$ROOT/sim/product_rv64_checkpoint_g4_main.cpp"
COMMON=(-std=c++20 -O2 -Wall -Wextra -Werror -I"$G5_STUB" -I"$G4_STUB" -I"$ROOT/sim" -fsyntax-only)
"${CXX:-g++}" "${COMMON[@]}" -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
  -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_DEBIAN "$SOURCE"
for rejected_macro in AETHERCORE_PRODUCT_SPI_SD AETHERCORE_PRODUCT_CHECKPOINT_G5; do
  if [[ "$rejected_macro" == AETHERCORE_PRODUCT_SPI_SD ]]; then
    flags=(-DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -DAETHERCORE_PRODUCT_CHECKPOINT_G5 \
      -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD)
  else
    flags=(-DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -DAETHERCORE_PRODUCT_CHECKPOINT_G5)
  fi
  if "${CXX:-g++}" "${COMMON[@]}" "${flags[@]}" "$SOURCE" > "$tmp/syntax.log" 2>&1; then
    echo "PRODUCT_G5_PERSISTENT_SELFTEST_FAIL: accepted invalid $rejected_macro" >&2
    exit 1
  fi
done
echo 'PRODUCT_G5_PERSISTENT_SELFTEST_PASS negative_contracts=6 exact_debian_top=1 host_CXX_syntax=PASS forbidden_macros=REJECT no_guest_cycles=1 no_pid1_claim=1'
