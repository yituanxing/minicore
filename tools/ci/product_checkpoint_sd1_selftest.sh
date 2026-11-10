#!/usr/bin/env bash
set -euo pipefail

# Host contract and fail-closed checks only; zero real guest cycles, zero RTL
# build, no SD Debian boot. The distinct Verilator5.054 model must still pass
# its opt-in real CI before claiming hardware checkpoint qualification.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SCRIPT="$ROOT/tools/ci/product_checkpoint_sd1_short.sh"
TMP="$(mktemp -d)"
trap 'rm -r -- "$TMP"' EXIT

reject() {
  local label="$1"
  shift
  if "$@" > "$TMP/result.log" 2>&1; then
    echo "PRODUCT_SD1_PERSISTENT_SELFTEST_FAIL: unexpectedly accepted $label" >&2
    exit 1
  fi
  if ! grep -Eq 'PRODUCT_SD1_PERSISTENT_(REJECTED|PROOF_REJECTED)' "$TMP/result.log"; then
    echo "PRODUCT_SD1_PERSISTENT_SELFTEST_FAIL: wrong rejection $label" >&2
    sed -n '1,12p' "$TMP/result.log" >&2
    exit 1
  fi
}

reject no_opt_in env -u AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT bash "$SCRIPT" build
reject wrong_verilator env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_VERILATOR_VERSION=5.024 bash "$SCRIPT" run
reject adaptive_settle env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  SIM_SETTLE_MODE=adaptive bash "$SCRIPT" build
reject threads env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  VERILATOR_THREADS=2 bash "$SCRIPT" run
reject data_wait env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_DATA_MEM_WAIT_CYCLES=2 bash "$SCRIPT" run
reject manual_sd env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_SD_IMAGE=unsafe.img bash "$SCRIPT" run
reject manual_dtb env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  AETHERCORE_PRODUCT_DTB_IMAGE=unsafe.dtb bash "$SCRIPT" run
reject incomplete_advance env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  bash "$SCRIPT" advance-only "$TMP/absent" 32000000
reject incomplete_restore env AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
  bash "$SCRIPT" restore-only "$TMP/absent"
reject absent_proof bash "$SCRIPT" verify-proof "$TMP/absent"

# A forged textual log and exact-looking source hashes cannot authenticate a
# tiny fake host. Proof needs full 256MiB DDR plus actual savable model and
# checked card bytes. The SHA envelope also blocks a truncated disk import.
mkdir -p "$TMP/forged"
for name in model firmware dtb sd_image linux_image rootfs_result; do
  printf '%s_sha256=%064d\n' "$name" 0 >> "$TMP/forged/.snapshot-ready"
done
printf 'snapshot_ready=1\ntop=AetherCoreProductDebianSdRV64SimTop\nschema=AETSDCP1\nsaves_full_rtl_ddr_sd=1\nboot_cycles=12000000\nreplay_cycles=500000\n' \
  >> "$TMP/forged/.snapshot-ready"
printf 'PRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles=12000000 commits=10\n' > "$TMP/forged/save.log"
printf 'AETSDCP1-fake-host' > "$TMP/forged/persistent.pre.host"
printf 'fake-RTL' > "$TMP/forged/persistent.pre.rtl"
printf 'snapshot_rtl_sha256=%s\nsnapshot_host_sha256=%s\n' \
  "$(sha256sum "$TMP/forged/persistent.pre.rtl" | awk '{print $1}')" \
  "$(sha256sum "$TMP/forged/persistent.pre.host" | awk '{print $1}')" \
  >> "$TMP/forged/.snapshot-ready"
reject forged_host bash "$SCRIPT" verify-proof "$TMP/forged"

# Type-check all actual SD1 checkpoint source against synthetic physical pins
# and Verilated save/restore declarations, including the entire card codec.
SD_STUB="$ROOT/tools/ci/fixtures/product_checkpoint_sd1_syntax"
G4_STUB="$ROOT/tools/ci/fixtures/product_checkpoint_g4_syntax"
SOURCE="$ROOT/sim/product_rv64_checkpoint_sd1_main.cpp"
COMMON=(-std=c++20 -O2 -Wall -Wextra -Werror -fsyntax-only \
  -I"$SD_STUB" -I"$G4_STUB" -I"$ROOT/sim")
"${CXX:-g++}" "${COMMON[@]}" -DAETHERCORE_PRODUCT_DEBIAN \
  -DAETHERCORE_PRODUCT_SPI_SD -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE "$SOURCE"
for mode in missing_savable missing_sd missing_debian; do
  case "$mode" in
    missing_savable) defs=(-DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD);;
    missing_sd) defs=(-DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE);;
    missing_debian) defs=(-DAETHERCORE_PRODUCT_SPI_SD -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE);;
  esac
  if "${CXX:-g++}" "${COMMON[@]}" "${defs[@]}" "$SOURCE" > "$TMP/syntax.log" 2>&1; then
    echo "PRODUCT_SD1_PERSISTENT_SELFTEST_FAIL: accepted $mode" >&2
    exit 1
  fi
done

bash -n "$SCRIPT"
echo 'PRODUCT_SD1_PERSISTENT_SELFTEST_PASS opt_in=REJECT mismatched_profile=REJECT manual_media=REJECT tiny_fake_ddr=REJECT incomplete_restore=REJECT cxx_syntax=PASS non_sd1_macro_combos=REJECT guest_cycles=0'
