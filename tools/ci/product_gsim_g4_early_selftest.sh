#!/usr/bin/env bash
set -euo pipefail

# Negative-only shell contract selftest. Does not build Product, create fake
# qualification artifacts, or run one guest cycle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/tools/ci/product_gsim_g4_early.sh"
bash -n "$SCRIPT"

TMP="$(mktemp -d)"
trap 'rm -f "$TMP/reject.log"; rmdir "$TMP"' EXIT
reject() {
  local expected="$1"
  shift
  if "$@" > "$TMP/reject.log" 2>&1; then
    echo "PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL accepted_unsafe=$expected" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" "$TMP/reject.log"; then
    echo "PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL missing_rejection=$expected" >&2
    sed -n '1,25p' "$TMP/reject.log" >&2
    exit 1
  fi
}

reject 'usage:' bash "$SCRIPT" invalid-action
reject 'Debian/SD1 media' env AETHERCORE_PRODUCT_SD_IMAGE=/dev/null bash "$SCRIPT" preflight
reject 'AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES out of range' \
  env AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES=200000001 bash "$SCRIPT" preflight
reject 'AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS out of range' \
  env AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS=286 bash "$SCRIPT" preflight

echo 'PRODUCT_GSIM_G4_SHELL_SELFTEST_PASS negative_contracts=4 guest_cycles=0'
