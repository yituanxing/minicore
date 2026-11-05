#!/usr/bin/env bash
set -euo pipefail

# Explicit, RAM-only, cross-PROCESS Product G3 state qualification. The model
# is uniquely built with Verilator 5.054 --savable (not the non-savable G3 A/B
# binary). No Debian/SD1, Linux PID1, or full distribution guest is run here.
#
#   AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT=1 \
#     bash tools/ci/product_checkpoint_g3_short.sh build
#   AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT=1 \
#     bash tools/ci/product_checkpoint_g3_short.sh run
#   bash tools/ci/product_checkpoint_g3_short.sh verify-proof PROOF_DIR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in build|run|verify-proof) ;; *) echo 'usage: product_checkpoint_g3_short.sh build|run|verify-proof [PROOF_DIR]' >&2; exit 2 ;; esac

verify_proof() {
  local dir="${1:?missing proof directory}"
  [[ -f "$dir/.complete" && -f "$dir/sha256.txt" &&
     -s "$dir/save.log" && -s "$dir/restore.log" ]] || {
    echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: incomplete proof directory' >&2; return 10;
  }
  grep -Fxq 'complete=1' "$dir/.complete" &&
    grep -Fxq 'replay_cycles=500000' "$dir/.complete" &&
    grep -Eq '^model_sha256=[0-9a-f]{64}$' "$dir/.complete" &&
    grep -Eq '^firmware_sha256=[0-9a-f]{64}$' "$dir/.complete" || {
      echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: missing exact model/firmware/replay contract' >&2; return 10;
    }
  local snapshot
  for snapshot in persistent.pre.rtl persistent.pre.host \
      persistent.continuous.rtl persistent.continuous.host \
      persistent.restored.rtl persistent.restored.host; do
    [[ -s "$dir/$snapshot" ]] || {
      echo "PRODUCT_G3_PERSISTENT_PROOF_REJECTED: missing $snapshot" >&2; return 10;
    }
  done
  # The host binary must actually include the fixed 256-MiB G3 DDR image;
  # a one-line fake proof is not a cross-process checkpoint qualification.
  [[ "$(stat -c %s "$dir/persistent.pre.host")" -gt 268435456 &&
     "$(stat -c %s "$dir/persistent.continuous.host")" -gt 268435456 &&
     "$(stat -c %s "$dir/persistent.restored.host")" -gt 268435456 &&
     "$(head -c 8 "$dir/persistent.pre.host")" == AETG3CP2 ]] || {
      echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: invalid host RAM extent or format' >&2; return 10;
    }
  (cd "$dir" && sha256sum --check --status sha256.txt) || {
    echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: model/host checkpoint bytes changed' >&2; return 10;
  }
  grep -Fq 'PRODUCT_G3_PERSISTENT_CHECKPOINT_SAVED ' "$dir/save.log" &&
    grep -Fq 'PRODUCT_G3_PERSISTENT_CHECKPOINT_RESTORED ' "$dir/restore.log" || {
      echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: did not save and restore in distinct processes' >&2; return 10;
    }
  [[ "$(grep -c '^PRODUCT_G3_PERSISTENT_CONTINUATION ' "$dir/save.log")" == 1 &&
     "$(grep -c '^PRODUCT_G3_PERSISTENT_CONTINUATION ' "$dir/restore.log")" == 1 ]] || {
    echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: missing/duplicate continuation record' >&2; return 10;
  }
  grep '^PRODUCT_G3_PERSISTENT_CONTINUATION ' "$dir/save.log" > "$dir/continuous.state"
  grep '^PRODUCT_G3_PERSISTENT_CONTINUATION ' "$dir/restore.log" > "$dir/restored.state"
  [[ -s "$dir/continuous.state" && -s "$dir/restored.state" ]] &&
    cmp -s "$dir/continuous.state" "$dir/restored.state" &&
    cmp -s "$dir/persistent.continuous.rtl" "$dir/persistent.restored.rtl" &&
    cmp -s "$dir/persistent.continuous.host" "$dir/persistent.restored.host" || {
      echo 'PRODUCT_G3_PERSISTENT_PROOF_REJECTED: CPU/RTL/host RAM/IRQ/UART state diverged after reload' >&2; return 10;
    }
  echo 'PRODUCT_G3_PERSISTENT_CROSS_PROCESS_PASS same_model=1 same_firmware=1 replay_cycles=500000 rtl_bytes=IDENTICAL host_bytes=IDENTICAL uart_cpu_irq_trace=IDENTICAL no_debian=1 no_sd1=1'
}

if [[ "$ACTION" == verify-proof ]]; then
  [[ $# == 2 ]] || { echo 'verify-proof requires exactly one proof directory' >&2; exit 2; }
  verify_proof "$2"
  exit $?
fi

[[ "${AETHERCORE_ALLOW_PRODUCT_PERSISTENT_CHECKPOINT:-0}" == 1 ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: explicit opt-in required' >&2; exit 2;
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: external SD1/Debian media forbidden' >&2; exit 2;
}
[[ "${VERILATOR_THREADS:-1}" == 1 && "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: pinned 5.054/reference single-thread/zero-wait only' >&2; exit 2;
}

TOP=AetherCoreProductOpenSbiRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
OUT="${PRODUCT_G3_PERSISTENT_OUT:-$ROOT/build/product-g3-persistent-checkpoint}"
MODEL="$OUT/savable-model"
OBJ="$MODEL/obj"
SIM="$OBJ/V$TOP"
FW_RESULT="$ROOT/build/rv64-opensbi-first-exec/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-opensbi-first-exec/evidence/sha256.txt"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
MODEL_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
VERILATOR_BIN="$TOOLCHAIN/verilator-5.054/bin/verilator"

[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: frozen G3 firmware proof missing' >&2; exit 3;
}
grep -Fxq 'RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS' "$FW_RESULT"
grep -Fxq 'opensbi_version=1.6' "$FW_RESULT"
grep -Fxq 'milestone=RV64 OpenSBI S-mode payload PASS' "$FW_RESULT"
sha256sum --check --status "$FW_SHA_LIST" || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: G3 firmware proof hashes drifted' >&2; exit 3;
}
FIRMWARE="$(sed -n 's/^firmware_bin=//p' "$FW_RESULT" | head -1)"
[[ -n "$FIRMWARE" && -s "$FIRMWARE" ]] || exit 3
FW_SHA="$(sha256sum "$FIRMWARE" | awk '{print $1}')"

# The baseline Product cache fingerprint omits the checkpoint-only host main
# and the Verilator --savable option. Bind BOTH explicitly so a normal cached
# binary can never satisfy a persistent checkpoint build ticket.
source_key() {
  {
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh fingerprint "${MODEL_ARGS[@]}"
    sha256sum sim/product_rv64_checkpoint_main.cpp tools/ci/product_checkpoint_g3_short.sh
    printf 'model-option=--savable\nmodel-source=%s\n' "$TOP"
  } | sha256sum | awk '{print $1}'
}

if [[ "$ACTION" == build ]]; then
  AETHERCORE_VERILATOR_VERSION=5.054 AETHERCORE_VERILATOR_FAST_VERIFY=1 \
    bash tools/ensure_verilator_5_024.sh
  [[ -x "$VERILATOR_BIN" ]] || {
    echo 'PRODUCT_G3_PERSISTENT_REJECTED: pinned 5.054 Verilator missing' >&2; exit 4;
  }
  mkdir -p "$OUT/logs"
  key="$(source_key)"
  if [[ -x "$SIM" && -s "$MODEL/.savable-source-key" &&
        "$(< "$MODEL/.savable-source-key")" == "$key" ]] &&
     AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
       bash tools/ci/product_sim_cache.sh verify "${MODEL_ARGS[@]}"; then
    echo "PRODUCT_G3_PERSISTENT_MODEL_REUSED source_key=$key"
    exit 0
  fi
  # Do not reuse unqualified generated objects across RTL/host mutations.
  mkdir -p "$OUT/rtl-seed" "$OBJ"
  make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
    TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$OUT/logs/rtl-build.log" 2>&1
  [[ -n "$(find "$OUT/rtl-seed/rtl" -maxdepth 1 -name '*.sv' -print -quit)" ]] || exit 4
  PATH="$TOOLCHAIN/verilator-5.054/bin:$PATH" "$VERILATOR_BIN" \
    -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --savable --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
    -Mdir "$OBJ" -CFLAGS "$FLAGS" \
    "$OUT"/rtl-seed/rtl/*.sv "$ROOT/sim/product_rv64_checkpoint_main.cpp" \
    -j "${JOBS:-4}" > "$OUT/logs/verilator-savable-build.log" 2>&1 || {
      tail -80 "$OUT/logs/verilator-savable-build.log" >&2; exit 4;
    }
  [[ "$key" == "$(source_key)" ]] || {
    echo 'PRODUCT_G3_PERSISTENT_REJECTED: sources changed during savable build' >&2; exit 4;
  }
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh record "${MODEL_ARGS[@]}"
  printf '%s\n' "$key" > "$MODEL/.savable-source-key"
  echo "PRODUCT_G3_PERSISTENT_MODEL_BUILT source_key=$key separate_savable_profile=1"
  exit 0
fi

[[ -x "$SIM" && -s "$MODEL/.savable-source-key" &&
   "$(< "$MODEL/.savable-source-key")" == "$(source_key)" ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: missing exact savable source/host model, run build first' >&2; exit 5;
}
AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh verify "${MODEL_ARGS[@]}" || exit 5
MODEL_SHA="$(sha256sum "$SIM" | awk '{print $1}')"
mkdir -p "$OUT"
PROOF="$(mktemp -d "$OUT/persist-G3.XXXXXXXX")"
PREFIX="$PROOF/persistent"
# Both processes read the exact same model and original OpenSBI firmware.
"$SIM" save "$FIRMWARE" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 30000000 \
  'RV64 OpenSBI S-mode payload PASS' 500000 > "$PROOF/save.log" 2>&1
(
  cd "$PROOF"
  sha256sum persistent.pre.rtl persistent.pre.host > sha256.txt
)
(cd "$PROOF" && sha256sum --check --status sha256.txt)
[[ "$(sha256sum "$FIRMWARE" | awk '{print $1}')" == "$FW_SHA" &&
   "$(sha256sum "$SIM" | awk '{print $1}')" == "$MODEL_SHA" ]] || {
  echo 'PRODUCT_G3_PERSISTENT_REJECTED: executable or firmware changed across process boundary' >&2; exit 5;
}
"$SIM" restore "$FIRMWARE" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 30000000 \
  'RV64 OpenSBI S-mode payload PASS' 500000 > "$PROOF/restore.log" 2>&1
(
  cd "$PROOF"
  sha256sum persistent.pre.rtl persistent.pre.host \
    persistent.continuous.rtl persistent.continuous.host \
    persistent.restored.rtl persistent.restored.host > sha256.txt
)
printf 'complete=1\nmodel_sha256=%s\nfirmware_sha256=%s\nboot_max=30000000\nreplay_cycles=500000\n' \
  "$MODEL_SHA" "$FW_SHA" > "$PROOF/.complete"
verify_proof "$PROOF" | tee "$PROOF/qualification.txt"
echo "PRODUCT_G3_PERSISTENT_PROOF_DIR=$PROOF"
