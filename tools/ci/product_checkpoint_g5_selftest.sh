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
rejects long_diagnosis_no_extra_opt env -u AETHERCORE_ALLOW_PRODUCT_G5_LONG_DIAGNOSE \
  AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT=1 bash "$SCRIPT" diagnose
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
# Cheap native G5 CLI rejection tests use synthetic Verilator declarations.
# No actual guest is clocked, and in particular no Debian cold prefix runs.
"${CXX:-g++}" -std=c++20 -O0 -Wall -Wextra -Werror \
  -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -DAETHERCORE_PRODUCT_CHECKPOINT_G5 \
  -DAETHERCORE_PRODUCT_DEBIAN -I"$G5_STUB" -I"$G4_STUB" -I"$ROOT/sim" \
  "$SOURCE" -o "$tmp/g5-fake-host"
sha="$(printf 'f%.0s' {1..64})"
native_rejects() {
  local label="$1" reason="$2"; shift 2
  if "$@" > "$tmp/$label.log" 2>&1; then
    echo "PRODUCT_G5_PERSISTENT_SELFTEST_FAIL: unexpectedly accepted $label" >&2
    exit 1
  fi
  grep -Fq "$reason" "$tmp/$label.log" || {
    echo "PRODUCT_G5_PERSISTENT_SELFTEST_FAIL: wrong error for $label" >&2
    sed -n '1,12p' "$tmp/$label.log" >&2
    exit 1
  }
}
diagnose=("$tmp/g5-fake-host" resume-diagnose /missing/firmware "$tmp/persistent" \
  "$sha" "$sha" 1800000000 'Linux version 6.6.143' 1000000)
native_rejects invalid_silence_budget 'requires decimal silence budget' \
  env AETHERCORE_G5_DIAGNOSTIC_MILESTONE=DEBIAN_RISCV64_GLIBC_PID1_OK \
  "${diagnose[@]}"
native_rejects unsafe_output_suffix 'suffix invalid/unsafe or collides' \
  env AETHERCORE_G5_DIAGNOSTIC_MILESTONE=DEBIAN_RISCV64_GLIBC_PID1_OK \
  AETHERCORE_G5_STALL_WATCHDOG_CYCLES=1000000 \
  AETHERCORE_G5_DIAGNOSTIC_OUTPUT_SUFFIX=pre "${diagnose[@]}"
native_rejects invalid_host_snapshot 'cannot open G4 host checkpoint' \
  env AETHERCORE_G5_DIAGNOSTIC_MILESTONE=DEBIAN_RISCV64_GLIBC_PID1_OK \
  AETHERCORE_G5_STALL_WATCHDOG_CYCLES=1000000 "${diagnose[@]}"
grep -Fq 'if (getString(is, 64) != modelSha || getString(is, 64) != firmwareSha)' "$SOURCE"
grep -Fq 'if (occupied(outRtl) || occupied(outHost))' "$SOURCE"
grep -Fq 'sha256sum --check --status "${input_suffix}.sha256"' "$SCRIPT"
grep -Fq 'PRODUCT_G5_LATE_INITCALL_CHECKPOINT_SAVED' "$SOURCE"
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
echo 'PRODUCT_G5_PERSISTENT_SELFTEST_PASS negative_contracts=10 native_resume_rejections=3 exact_debian_top=1 host_CXX_syntax=PASS forbidden_macros=REJECT no_guest_cycles=1 no_pid1_claim=1'
