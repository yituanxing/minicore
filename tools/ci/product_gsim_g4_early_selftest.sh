#!/usr/bin/env bash
set -euo pipefail

# Negative-only shell contract selftest. Does not build Product, create fake
# qualification artifacts, or run one guest cycle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/tools/ci/product_gsim_g4_early.sh"
bash -n "$SCRIPT"

# The default must be the same bounded 100M window requested by the Product
# G4 workflow, while explicit diagnostic overrides retain distinct evidence.
grep -Fqx 'EARLY_CYCLES="${AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES:-100000000}"' "$SCRIPT" || {
  echo 'PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL default_is_not_100M' >&2
  exit 1
}
grep -Fq 'qualification=PRODUCT_GSIM_G4_%s_CYCLES_LINUX_BANNER_GSIM_ONLY_NOT_VERILATOR_AB_NOT_PID1_PASS' "$SCRIPT" || {
  echo 'PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL qualification_does_not_name_actual_cycles' >&2
  exit 1
}

reject() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL accepted_unsafe=$expected" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" <<< "$output"; then
    echo "PRODUCT_GSIM_G4_SHELL_SELFTEST_FAIL missing_rejection=$expected" >&2
    printf '%s\n' "$output" | sed -n '1,25p' >&2
    exit 1
  fi
}

reject 'usage:' bash "$SCRIPT" invalid-action
reject 'Debian/SD1 media' env AETHERCORE_PRODUCT_SD_IMAGE=/dev/null bash "$SCRIPT" preflight
reject 'AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES out of range' \
  env AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES=200000001 bash "$SCRIPT" preflight
reject 'AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES out of range' \
  env AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES=49999999 bash "$SCRIPT" preflight
reject 'AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS out of range' \
  env AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS=286 bash "$SCRIPT" preflight

echo 'PRODUCT_GSIM_G4_SHELL_SELFTEST_PASS negative_contracts=5 default_cycles=100000000 dynamic_qualification=1 guest_cycles=0'
