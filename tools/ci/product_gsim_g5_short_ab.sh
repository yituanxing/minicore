#!/usr/bin/env bash
set -euo pipefail

# Explicit, RAM-only Product G5 RV64GC Debian GSIM/Verilator short qualification.
# Never import G3's IMAC model or its speed result, never use SD1 media, and
# never launch a PID1 or distribution longrun from this runner.
#   AETHERCORE_ALLOW_PRODUCT_GSIM_G5_AB=1 bash ... build
#   AETHERCORE_ALLOW_PRODUCT_GSIM_G5_AB=1 bash ... run

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in build|run|all) ;; *) echo 'usage: product_gsim_g5_short_ab.sh build|run|all' >&2; exit 2 ;; esac
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_G5_AB:-0}" == 1 ]] || {
  echo 'PRODUCT_GSIM_G5_REJECTED: explicit bounded G5 A/B opt-in required' >&2; exit 2;
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_GSIM_G5_REJECTED: external SD1 media is not in this RAM-only bridge' >&2; exit 2;
}
[[ "${SIM_SETTLE_MODE:-reference}" == reference && "${VERILATOR_THREADS:-1}" == 1 ]] || {
  echo 'PRODUCT_GSIM_G5_REJECTED: only 5.054/reference/single-thread comparison' >&2; exit 2;
}

TOP=AetherCoreProductDebianRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductDebianRV64SimTop
VERSION=5.054
CYCLES="${AETHERCORE_GSIM_G5_AB_CYCLES:-30000000}"
[[ "$CYCLES" =~ ^[0-9]+$ ]] && (( CYCLES >= 1000000 && CYCLES <= 60000000 )) || {
  echo 'PRODUCT_GSIM_G5_REJECTED: compare only 1M..60M cycles' >&2; exit 2;
}
WALL="${AETHERCORE_GSIM_G5_WALL_SECONDS:-900}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 60 && WALL <= 1800 )) || exit 2
OUT="${PRODUCT_GSIM_G5_AB_OUT:-$ROOT/build/product-gsim-g5-ab}"
GSIM_OUT="${PRODUCT_GSIM_G5_OUT:-$ROOT/build/product-gsim-debian-bridge}"
GSIM="$GSIM_OUT/product-gsim-debian-prefix"
MODEL="$OUT/model-verilator-5.054-reference"
VERILATOR="$MODEL/obj/V$TOP"
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
VERILATOR_BIN="$TOOLCHAIN/verilator-5.054/bin/verilator"
FW_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-debian-boot/evidence/sha256.txt"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_GSIM_DEBIAN_AB_TRACE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
MODEL_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
TICKET="$OUT/model-ready.txt"
fail() { echo "PRODUCT_GSIM_G5_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
model_cache() {
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh "$1" "${MODEL_ARGS[@]}"
}

[[ -s "$FW_RESULT" && -s "$LINUX_RESULT" && -s "$ROOTFS_RESULT" && -s "$FW_SHA_LIST" ]] ||
  fail 'must build real Debian Linux/rootfs/OpenSBI D1/D2 artifacts first'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT" || fail 'Debian payload not qualified'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || fail 'Debian Linux Image not qualified'
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'Debian rootfs not qualified'
grep -Fxq 'spi_sd=0' "$FW_RESULT" || fail 'SD1 is explicitly excluded'
grep -Eq '^stage=d[12]$' "$ROOTFS_RESULT" || fail 'real D1/D2 native rootfs only'
sha256sum --check --status "$FW_SHA_LIST" || fail 'Debian payload bytes changed'
FW_BIN="$(sed -n 's/^firmware_bin=//p' "$FW_RESULT" | head -1)"
[[ -s "$FW_BIN" ]] || fail 'real Debian firmware.bin absent'
FW_SHA="$(sha_file "$FW_BIN")"
mkdir -p "$OUT/logs"

if [[ "$ACTION" != run ]]; then
  rm -f "$TICKET" "$OUT/qualification.txt"
  # G5 CHIRRTL/host model is compiled separately from the historical G3 IMAC
  # artifact and cannot inherit its binary cache or A/B ticket.
  bash tools/ci/product_gsim_g5_build.sh
fi
[[ -x "$GSIM" && -s "$GSIM_OUT/build-identity.txt" ]] || fail 'real G5 GSIM binary/identity missing'
grep -Fxq "top=$TOP" "$GSIM_OUT/build-identity.txt" || fail 'GSIM is not the Debian RV64GC top'
grep -Fxq 'debian_mode=1' "$GSIM_OUT/build-identity.txt" || fail 'G3 IMAC model cannot qualify G5'
GSIM_SHA="$(sha_file "$GSIM")"
GSIM_ID_SHA="$(sha_file "$GSIM_OUT/build-identity.txt")"

if [[ "$ACTION" != run ]]; then
  AETHERCORE_VERILATOR_VERSION=5.054 AETHERCORE_VERILATOR_FAST_VERIFY=1 \
    bash tools/ensure_verilator_5_024.sh > "$OUT/logs/verilator-toolchain.log" 2>&1 || {
      tail -50 "$OUT/logs/verilator-toolchain.log" >&2; fail '5.054 pinned simulator unavailable';
    }
fi
[[ -x "$VERILATOR_BIN" ]] || fail 'Verilator 5.054 binary not found'
if ! model_cache verify > "$OUT/logs/model-cache.log" 2>&1; then
  [[ "$ACTION" != run ]] || fail 'run cannot build an unqualified/stale model'
  mkdir -p "$OUT/rtl-seed/rtl" "$MODEL/obj"
  make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
    TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$OUT/logs/rtl-build.log" 2>&1 || {
      tail -70 "$OUT/logs/rtl-build.log" >&2; fail 'G5 Verilator RTL elaboration';
    }
  PATH="$TOOLCHAIN/verilator-5.054/bin:$PATH" "$VERILATOR_BIN" \
    -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
    -Mdir "$MODEL/obj" -CFLAGS "$FLAGS" \
    "$OUT"/rtl-seed/rtl/*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
    -j "${JOBS:-4}" > "$OUT/logs/verilator-build.log" 2>&1 || {
      tail -90 "$OUT/logs/verilator-build.log" >&2; fail 'real G5 trace-enabled model build';
    }
  model_cache record > "$OUT/logs/model-cache.log"
fi
[[ -x "$VERILATOR" ]] && model_cache verify >/dev/null || fail 'exact G5 Verilator model mismatch'
MODEL_ID="$(model_cache fingerprint)"
VERILATOR_SHA="$(sha_file "$VERILATOR")"

if [[ "$ACTION" == run ]]; then
  [[ -s "$TICKET" ]] || fail 'matching build ticket absent'
  for exact in "top=$TOP" "cycles=$CYCLES" "firmware_sha256=$FW_SHA" \
      "gsim_sha256=$GSIM_SHA" "gsim_identity_sha256=$GSIM_ID_SHA" \
      "verilator_model_identity=$MODEL_ID" "verilator_sha256=$VERILATOR_SHA"; do
    grep -Fxq "$exact" "$TICKET" || fail "stale model build ticket: $exact"
  done
else
  {
    printf 'top=%s\ncycles=%s\nfirmware_sha256=%s\n' "$TOP" "$CYCLES" "$FW_SHA"
    printf 'gsim_sha256=%s\ngsim_identity_sha256=%s\n' "$GSIM_SHA" "$GSIM_ID_SHA"
    printf 'verilator_model_identity=%s\nverilator_sha256=%s\n' "$MODEL_ID" "$VERILATOR_SHA"
  } > "$TICKET"
  echo "PRODUCT_GSIM_G5_MODELS_VERIFIED gs=$GSIM vs=$VERILATOR (no guest runtime claim)"
  [[ "$ACTION" == build ]] && exit 0
fi

run_model() {
  local engine="$1" log="$2"; shift 2
  local start end rc
  start="$(date +%s%N)"
  set +e
  timeout --signal=TERM --kill-after=10s "${WALL}s" "$@" > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  (( rc != 124 && rc != 137 )) || fail "$engine host timeout"
  case "$engine:$rc" in verilator:2|gsim:0) ;; *) tail -70 "$log" >&2; fail "$engine returned $rc" ;; esac
  RUN_SECONDS="$(awk -v a="$start" -v b="$end" 'BEGIN {printf "%.6f",(b-a)/1000000000}')"
}

run_model verilator "$OUT/logs/verilator.run.log" \
  "$VERILATOR" "$FW_BIN" "$CYCLES" '__PRODUCT_G5_GSIM_AB_NO_PID1__' 0 0 '' '' 0 0 0 0 0
V_SECONDS="$RUN_SECONDS"
run_model gsim "$OUT/logs/gsim.run.log" \
  "$GSIM" "$FW_BIN" "$CYCLES" '__PRODUCT_G5_GSIM_AB_NO_PID1__'
G_SECONDS="$RUN_SECONDS"
python3 tools/ci/product_gsim_g5_compare.py \
  --verilator "$OUT/logs/verilator.run.log" --gsim "$OUT/logs/gsim.run.log" \
  --cycles "$CYCLES" > "$OUT/compare.txt" || {
    cat "$OUT/compare.txt" >&2; fail 'G5 CPU/FPU/RAM/UART/IRQ strict same-cycle divergence';
  }
[[ "$(sha_file "$FW_BIN")" == "$FW_SHA" && "$(sha_file "$GSIM")" == "$GSIM_SHA" &&
   "$(sha_file "$VERILATOR")" == "$VERILATOR_SHA" ]] || fail 'firmware or executable mutated'
model_cache verify >/dev/null || fail 'G5 reference model changed after guest run'
SPEEDUP="$(awk -v v="$V_SECONDS" -v g="$G_SECONDS" 'BEGIN{if(g<=0) exit 2; printf "%.3f",v/g}')"
{
  printf 'qualification=PRODUCT_GSIM_G5_DEBIAN_RAM_SAME_CYCLE_NOT_PID1_PASS\n'
  printf 'top=%s\ncycles=%s\nfirmware_sha256=%s\n' "$TOP" "$CYCLES" "$FW_SHA"
  printf 'gsim_sha256=%s\ngsim_identity_sha256=%s\n' "$GSIM_SHA" "$GSIM_ID_SHA"
  printf 'verilator_model_identity=%s\nverilator_sha256=%s\n' "$MODEL_ID" "$VERILATOR_SHA"
  printf 'verilator_seconds=%s\ngsim_seconds=%s\nspeedup=%sx\n' "$V_SECONDS" "$G_SECONDS" "$SPEEDUP"
  printf 'promotion_eligible=%s\n' "$(awk -v x="$SPEEDUP" 'BEGIN{print(x>=1.03?1:0)}')"
} > "$OUT/qualification.txt"
cat "$OUT/compare.txt"
echo "PRODUCT_GSIM_G5_AB_PASS same_cycle=$CYCLES speedup=${SPEEDUP}x real_debian_top=1 no_pid1=1 no_sd1=1"
