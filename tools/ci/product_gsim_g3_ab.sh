#!/usr/bin/env bash
set -euo pipefail

# G3-only, one genuine OpenSBI 1.6 + S-mode UART firmware, two simulators,
# precisely 30M cycles each. Historical PA32 GSIM never counts as Product PASS.
# Use ONE Product RTL Verilator 5.054 reference model, not the 3-way builds.
# GSIM/Verilator model binaries are reused across repeated attempts. No Debian.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_AB:-0}" == 1 ]] || {
  echo 'Set AETHERCORE_ALLOW_PRODUCT_GSIM_AB=1 for the opt-in G3 gate' >&2; exit 2;
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'Product GSIM G3 forbids Debian SDHC/independent DTB' >&2; exit 2;
}
OUT="${PRODUCT_GSIM_G3_OUT:-$ROOT/build/product-gsim-g3-ab}"
FW_RESULT="$ROOT/build/rv64-opensbi-first-exec/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-opensbi-first-exec/evidence/sha256.txt"
TOP=AetherCoreProductOpenSbiRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
CYCLES=30000000
MODE=reference
VERSION=5.054
NAME=verilator-$VERSION-$MODE
FLAGS="-std=c++20 -O3 -march=native -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
MODEL="${PRODUCT_GSIM_G3_VERILATOR_MODEL:-$ROOT/build/product-g3-short-ab/model-$NAME}"
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
mkdir -p "$OUT/logs"
: > "$OUT/inprogress"
fail() { echo "PRODUCT_GSIM_G3_AB_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }

[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" ]] || fail 'real G3 OpenSBI manifest missing; run rv64_opensbi_first_exec.sh'
grep -Fxq 'RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS' "$FW_RESULT" || fail 'G3 firmware not qualified'
grep -Fxq 'opensbi_version=1.6' "$FW_RESULT" || fail 'wrong OpenSBI version'
grep -Fxq 'milestone=RV64 OpenSBI S-mode payload PASS' "$FW_RESULT" || fail 'wrong S-mode UART payload'
sha256sum --check --status "$FW_SHA_LIST" || fail 'firmware/build bytes do not match historical G3 evidence'
FIRMWARE="$(sed -n 's/^firmware_bin=//p' "$FW_RESULT" | head -1)"
[[ -n "$FIRMWARE" && -s "$FIRMWARE" ]] || fail 'G3 firmware missing'
FW_SHA="$(sha_file "$FIRMWARE")"

# Product GSIM first. Do not incur Verilator compilation if the generated
# same-tick host bridge or model compiler cannot build this exact Chisel top.
bash tools/ci/product_gsim_build.sh
GSIM="${PRODUCT_GSIM_OUT:-$ROOT/build/product-gsim-bridge}/product-gsim-prefix"
[[ -x "$GSIM" ]] || fail 'Product GSIM native host/model missing'
GSIM_SHA="$(sha_file "$GSIM")"

# Verified caches from the old G3 gate are preferred. If absent, compile only
# one exact Product model; G3 and later Linux prefixes can reuse this binary.
AETHERCORE_VERILATOR_VERSION="$VERSION" AETHERCORE_VERILATOR_FAST_VERIFY=1 \
  bash tools/ensure_verilator_5_024.sh > "$OUT/logs/verilator-toolchain.log" 2>&1 || {
    tail -50 "$OUT/logs/verilator-toolchain.log" >&2; fail 'pinned Verilator unavailable';
  }
VERILATOR_BIN="$TOOLCHAIN/verilator-$VERSION/bin/verilator"
[[ -x "$VERILATOR_BIN" ]] || fail 'pinned Verilator binary missing'
unset VERILATOR_ROOT
VERILATOR_CACHE_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
if ! AETHERCORE_VERILATOR_VERSION="$VERSION" SIM_SETTLE_MODE="$MODE" \
  bash tools/ci/product_sim_cache.sh verify "${VERILATOR_CACHE_ARGS[@]}" > "$OUT/logs/verilator-cache.log" 2>&1; then
  # Do not overwrite a G3 three-way qualification folder with an unrelated
  # single-model build. Build into our separate managed scratch directory.
  MODEL="$OUT/model-$NAME"
  VERILATOR_CACHE_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
  if ! AETHERCORE_VERILATOR_VERSION="$VERSION" SIM_SETTLE_MODE="$MODE" \
    bash tools/ci/product_sim_cache.sh verify "${VERILATOR_CACHE_ARGS[@]}" > "$OUT/logs/verilator-cache.log" 2>&1; then
    mkdir -p "$OUT/rtl-seed/rtl" "$MODEL/obj"
    make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
      TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$OUT/logs/rtl-build.log" 2>&1 || {
        tail -60 "$OUT/logs/rtl-build.log" >&2; fail 'Product Verilator RTL elaboration';
      }
    [[ -n "$(find "$OUT/rtl-seed/rtl" -maxdepth 1 -name '*.sv' -print -quit)" ]] || fail 'No Product SV generated'
    PATH="$TOOLCHAIN/verilator-$VERSION/bin:$PATH" "$VERILATOR_BIN" \
      -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
      --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
      -Mdir "$MODEL/obj" -CFLAGS "$FLAGS" \
      "$OUT"/rtl-seed/rtl/*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
      -j "${JOBS:-4}" > "$OUT/logs/verilator-build.log" 2>&1 || {
        tail -70 "$OUT/logs/verilator-build.log" >&2; fail 'Product Verilator host build';
      }
    AETHERCORE_VERILATOR_VERSION="$VERSION" SIM_SETTLE_MODE="$MODE" \
      bash tools/ci/product_sim_cache.sh record "${VERILATOR_CACHE_ARGS[@]}" > "$OUT/logs/verilator-cache.log"
  fi
fi
VERILATOR="$MODEL/obj/V$TOP"
[[ -x "$VERILATOR" ]] || fail 'verified Product Verilator model missing'
MODEL_ID="$(AETHERCORE_VERILATOR_VERSION="$VERSION" SIM_SETTLE_MODE="$MODE" \
  bash tools/ci/product_sim_cache.sh fingerprint "${VERILATOR_CACHE_ARGS[@]}")"
VERILATOR_SHA="$(sha_file "$VERILATOR")"

run() {
  local name="$1" log="$2"; shift 2
  local start end rc
  start="$(date +%s%N)"
  set +e
  timeout --signal=TERM --kill-after=10s "${PRODUCT_GSIM_G3_WALL_SECONDS:-900}s" \
    "$@" > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  (( rc != 124 && rc != 137 )) || fail "$name timed out; no GSIM speed claim"
  case "$name:$rc" in gsim:0|verilator:2) ;; *) tail -70 "$log" >&2; fail "$name exit=$rc" ;; esac
  RUN_SECONDS="$(awk -v a="$start" -v b="$end" 'BEGIN{printf "%.6f",(b-a)/1000000000}')"
}

MARKER=__PRODUCT_SIM_AB_NEVER_MATCH__
run verilator "$OUT/logs/verilator.run.log" "$VERILATOR" "$FIRMWARE" "$CYCLES" "$MARKER" \
  0 0 '' '' 0 "$CYCLES" 0 0 0
V_SECONDS="$RUN_SECONDS"
[[ "$(sha_file "$VERILATOR")" == "$VERILATOR_SHA" ]] || fail 'Verilator executable changed'
run gsim "$OUT/logs/gsim.run.log" "$GSIM" "$FIRMWARE" "$CYCLES" "$MARKER"
G_SECONDS="$RUN_SECONDS"
python3 tools/ci/product_gsim_compare.py \
  --verilator "$OUT/logs/verilator.run.log" --gsim "$OUT/logs/gsim.run.log" \
  --cycles "$CYCLES" > "$OUT/compare.txt" || { cat "$OUT/compare.txt" >&2; fail 'CPU/RAM/UART/IRQ mismatch'; }
[[ "$(sha_file "$FIRMWARE")" == "$FW_SHA" && "$(sha_file "$GSIM")" == "$GSIM_SHA" ]] || fail 'firmware or GSIM executable changed'
AETHERCORE_VERILATOR_VERSION="$VERSION" SIM_SETTLE_MODE="$MODE" \
  bash tools/ci/product_sim_cache.sh verify "${VERILATOR_CACHE_ARGS[@]}" >/dev/null || fail 'Verilator cache invalidated'

awk -v vc="$CYCLES" -v v="$V_SECONDS" -v g="$G_SECONDS" \
  'BEGIN {if(v<=0||g<=0) exit 2; printf "engine\tseconds\tcycles_per_second\nVerilator\t%.6f\t%.1f\nGSIM\t%.6f\t%.1f\n", v,vc/v,g,vc/g}' \
  > "$OUT/results.tsv"
SPEEDUP="$(awk -v v="$V_SECONDS" -v g="$G_SECONDS" 'BEGIN{if(g<=0) exit 2; printf "%.3f", v/g}')"
{
  printf 'qualification=PRODUCT_GSIM_G3_IDENTICAL_NOT_LINUX_PASS\n'
  printf 'top=%s\ncycles=%s\nfirmware_sha256=%s\n' "$TOP" "$CYCLES" "$FW_SHA"
  printf 'verilator_model_id=%s\nverilator_sha256=%s\n' "$MODEL_ID" "$VERILATOR_SHA"
  printf 'gsim_sha256=%s\n' "$GSIM_SHA"
  printf 'gsim_identity_sha256=%s\n' "$(sha_file "$(dirname "$GSIM")/build-identity.txt")"
  printf 'verilator_seconds=%s\ngsim_seconds=%s\nspeedup=%s\n' "$V_SECONDS" "$G_SECONDS" "$SPEEDUP"
  printf 'promotion_eligible=%s\n' "$(awk -v speedup="$SPEEDUP" 'BEGIN{print(speedup>=1.03?1:0)}')"
} > "$OUT/qualification.txt"
rm -f "$OUT/inprogress"
cat "$OUT/compare.txt"
echo "PRODUCT_GSIM_G3_AB_PASS same_cycle=$CYCLES speedup=${SPEEDUP}x (G3 only; NO Linux PID1/Debian claim)"
