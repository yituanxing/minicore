#!/usr/bin/env bash
set -euo pipefail

# RAM-only, real Product G4/OpenSBI/Linux 6.6.143 UART warm checkpoint.
# Reuses the verified G3 --savable RTL archive for the SAME Product top;
# only the G4 host main is compiled/linked. No fresh RTL elaboration.
# Never claims full PID1, Debian, RV64GC, or SD1 success.
#
# AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_g4_short.sh build
# AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_g4_short.sh run
# bash tools/ci/product_checkpoint_g4_short.sh verify-proof PROOF_DIR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in build|run|verify-proof) ;; *) echo 'usage: product_checkpoint_g4_short.sh build|run|verify-proof [PROOF_DIR]' >&2; exit 2 ;; esac

reject() { echo "PRODUCT_G4_PERSISTENT_REJECTED: $*" >&2; exit 3; }

verify_proof() {
  local dir="${1:?missing proof directory}" state
  [[ -s "$dir/.complete" && -s "$dir/sha256.txt" &&
     -s "$dir/save.log" && -s "$dir/restore.log" ]] || {
    echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: missing qualification proof' >&2; return 10;
  }
  for state in persistent.pre.rtl persistent.pre.host \
      persistent.continuous.rtl persistent.continuous.host \
      persistent.restored.rtl persistent.restored.host; do
    [[ -s "$dir/$state" ]] || {
      echo "PRODUCT_G4_PERSISTENT_PROOF_REJECTED: missing $state" >&2; return 10;
    }
  done
  grep -Fxq 'complete=1' "$dir/.complete" &&
    grep -Fxq 'replay_cycles=500000' "$dir/.complete" &&
    grep -Fxq 'top=AetherCoreProductOpenSbiRV64SimTop' "$dir/.complete" &&
    grep -Fxq 'milestone=Linux version 6.6.143' "$dir/.complete" &&
    grep -Eq '^model_sha256=[0-9a-f]{64}$' "$dir/.complete" &&
    grep -Eq '^firmware_sha256=[0-9a-f]{64}$' "$dir/.complete" &&
    grep -Eq '^linux_image_sha256=[0-9a-f]{64}$' "$dir/.complete" || {
      echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: wrong G4 model/firmware/Linux identity' >&2; return 10;
    }
  [[ "$(stat -c %s "$dir/persistent.pre.host")" -gt 268435456 &&
     "$(stat -c %s "$dir/persistent.continuous.host")" -gt 268435456 &&
     "$(stat -c %s "$dir/persistent.restored.host")" -gt 268435456 &&
     "$(head -c 8 "$dir/persistent.pre.host")" == AETG4CP1 ]] || {
      echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: no full RAM or bad G4 host header' >&2; return 10;
    }
  (cd "$dir" && sha256sum --check --status sha256.txt) || {
    echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: G4 checkpoint bytes changed' >&2; return 10;
  }
  [[ "$(grep -c '^PRODUCT_G4_PERSISTENT_CHECKPOINT_SAVED ' "$dir/save.log")" == 1 &&
     "$(grep -c '^PRODUCT_G4_PERSISTENT_CHECKPOINT_RESTORED ' "$dir/restore.log")" == 1 &&
     "$(grep -c '^PRODUCT_G4_PERSISTENT_CONTINUATION ' "$dir/save.log")" == 1 &&
     "$(grep -c '^PRODUCT_G4_PERSISTENT_CONTINUATION ' "$dir/restore.log")" == 1 ]] || {
    echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: no two real saved/restored continuations' >&2; return 10;
  }
  grep '^PRODUCT_G4_PERSISTENT_CONTINUATION ' "$dir/save.log" > "$dir/continuous.state"
  grep '^PRODUCT_G4_PERSISTENT_CONTINUATION ' "$dir/restore.log" > "$dir/restored.state"
  grep -Eq ' linux_banner=1 opensbi_banner=1 ' "$dir/continuous.state" &&
    cmp -s "$dir/continuous.state" "$dir/restored.state" &&
    cmp -s "$dir/persistent.continuous.rtl" "$dir/persistent.restored.rtl" &&
    cmp -s "$dir/persistent.continuous.host" "$dir/persistent.restored.host" || {
      echo 'PRODUCT_G4_PERSISTENT_PROOF_REJECTED: G4 RTL/DDR/CPU/UART state diverged' >&2; return 10;
    }
  echo 'PRODUCT_G4_PERSISTENT_CROSS_PROCESS_PASS real_linux_banner=1 same_model=1 same_firmware=1 replay_cycles=500000 rtl_bytes=IDENTICAL host_ddr_bytes=IDENTICAL uart_irq_trace=IDENTICAL no_debian=1 no_sd1=1 no_pid1_claim=1'
}

if [[ "$ACTION" == verify-proof ]]; then
  [[ $# == 2 ]] || reject 'verify-proof requires exactly one directory'
  verify_proof "$2"
  exit $?
fi

[[ "${AETHERCORE_ALLOW_PRODUCT_G4_PERSISTENT_CHECKPOINT:-0}" == 1 ]] || reject 'explicit G4 checkpoint opt-in required'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || reject 'SD1/raw media/independent DTB forbidden'
[[ "${VERILATOR_THREADS:-1}" == 1 && "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] || reject 'only pinned 5.054/reference/one thread/zero-wait supported'

TOP=AetherCoreProductOpenSbiRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
G3_ROOT="${PRODUCT_G3_PERSISTENT_OUT:-$ROOT/build/product-g3-persistent-checkpoint}"
G3_MODEL="$G3_ROOT/savable-model"
OBJ="$G3_MODEL/obj"
ARCHIVE="$OBJ/V${TOP}__ALL.a"
G3_BIN="$OBJ/V$TOP"
OUT="${PRODUCT_G4_PERSISTENT_OUT:-$ROOT/build/product-g4-persistent-checkpoint}"
SIM="$OUT/product-g4-checkpoint"
HOST_OBJ="$OUT/product-g4-checkpoint.o"
G4_SOURCE="$ROOT/sim/product_rv64_checkpoint_g4_main.cpp"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
G3_ARGS=("$G3_MODEL" "$TOP" "$ELABORATE" "$FLAGS")
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
VERILATOR_INCLUDE="$TOOLCHAIN/verilator-5.054/include"
FW_RESULT="$ROOT/build/rv64-minimal-init-boot/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-minimal-init-boot/evidence/sha256.txt"
LINUX_RESULT="$ROOT/build/rv64-linux-initramfs/result.txt"

[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" && -s "$LINUX_RESULT" ]] || reject 'real G4 OpenSBI+Linux firmware and Image proof missing'
grep -Fxq 'RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT" || reject 'G4 fw_payload build did not PASS'
grep -Fxq 'spi_sd=0' "$FW_RESULT" || reject 'G4 must use RAM-initramfs, never SPI-SD'
grep -Fxq 'RV64_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || reject 'Linux/initramfs image not qualified'
sha256sum --check --status "$FW_SHA_LIST" || reject 'G4 fw_payload/DTB/Linux Image hashes drifted'
FW_BIN="$(sed -n 's/^firmware_bin=//p' "$FW_RESULT" | head -1)"
LINUX_IMAGE="$(sed -n 's/^linux_image=//p' "$FW_RESULT" | head -1)"
[[ -s "$FW_BIN" && -s "$LINUX_IMAGE" ]] || reject 'G4 payload/Image missing'
LINUX_SHA="$(sha256sum "$LINUX_IMAGE" | awk '{print $1}')"
grep -Fxq "linux_image_sha256=$LINUX_SHA" "$FW_RESULT" || reject 'G4 Linux Image SHA differs from payload'
FW_SHA="$(sha256sum "$FW_BIN" | awk '{print $1}')"

# The proven G3 --savable archive is compiled for *exactly* the same Product
# hardware top. Verify its original source/ABI/runtime/host-manifest identity
# before using it. The generated model is read-only, and relink is cheap.
g3_key() {
  {
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh fingerprint "${G3_ARGS[@]}"
    sha256sum sim/product_rv64_checkpoint_main.cpp tools/ci/product_checkpoint_g3_short.sh
    printf 'model-option=--savable\nmodel-source=%s\n' "$TOP"
  } | sha256sum | awk '{print $1}'
}
verify_g3_model() {
  [[ -s "$ARCHIVE" && -s "$OBJ/verilated.o" && -s "$OBJ/verilated_save.o" &&
     -x "$G3_BIN" && -s "$G3_MODEL/.savable-source-key" ]] || return 1
  [[ "$(< "$G3_MODEL/.savable-source-key")" == "$(g3_key)" ]] || return 1
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh verify "${G3_ARGS[@]}" >/dev/null
}
verify_g3_model || reject 'G3 pinned --savable RTL archive is missing/stale; restore G3 savable-model cache or build G3 first'
[[ -s "$VERILATOR_INCLUDE/verilated_save.h" && -s "$OBJ/VAetherCoreProductOpenSbiRV64SimTop.h" ]] || reject 'generated G3 savable model headers missing'

# Cache identity includes the *relinked host* plus exact immutable G3 RTL
# archive/binary and host ABI. A G3 firmware snapshot itself is NEVER reused.
g4_key() {
  {
    printf '%s\n' 'g4=linux-6.6.143-real-uart savable=5.054/reference relink-no-rtl-build'
    printf 'g3-model-key=%s\n' "$(< "$G3_MODEL/.savable-source-key")"
    sha256sum "$G3_BIN" "$ARCHIVE" sim/product_rv64_checkpoint_g4_main.cpp tools/ci/product_checkpoint_g4_short.sh
    printf 'cxx=%s\nflags=%s\n' "${CXX:-g++}" "$FLAGS"
    command -v "${CXX:-g++}"
    sha256sum "$(readlink -f "$(command -v "${CXX:-g++}")")"
  } | sha256sum | awk '{print $1}'
}

if [[ "$ACTION" == build ]]; then
  mkdir -p "$OUT/logs"
  key="$(g4_key)"
  if [[ -x "$SIM" && -s "$OUT/.g4-source-key" &&
        "$(< "$OUT/.g4-source-key")" == "$key" && -s "$OUT/.g4-binary-sha" &&
        "$(< "$OUT/.g4-binary-sha")" == "$(sha256sum "$SIM" | awk '{print $1}')" ]]; then
    echo "PRODUCT_G4_PERSISTENT_MODEL_REUSED source_key=$key g3_savable_reused=1"
    exit 0
  fi
  "${CXX:-g++}" -std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
    -I"$OBJ" -I"$ROOT/sim" -I"$VERILATOR_INCLUDE" -I"$VERILATOR_INCLUDE/vltstd" \
    -c "$G4_SOURCE" -o "$HOST_OBJ" > "$OUT/logs/host-compile.log" 2>&1 || {
      tail -80 "$OUT/logs/host-compile.log" >&2; exit 4;
    }
  runtime_objs=("$OBJ/verilated.o" "$OBJ/verilated_save.o")
  for extra in verilated_threads.o verilated_timing.o verilated_dpi.o; do
    [[ ! -s "$OBJ/$extra" ]] || runtime_objs+=("$OBJ/$extra")
  done
  "${CXX:-g++}" -O3 "$HOST_OBJ" "$ARCHIVE" "${runtime_objs[@]}" \
    -pthread -ldl -latomic -o "$SIM" > "$OUT/logs/host-link.log" 2>&1 || {
      tail -80 "$OUT/logs/host-link.log" >&2; exit 4;
    }
  [[ "$key" == "$(g4_key)" ]] || reject 'G4 runner or pinned G3 model mutated while linking'
  printf '%s\n' "$key" > "$OUT/.g4-source-key"
  sha256sum "$SIM" | awk '{print $1}' > "$OUT/.g4-binary-sha"
  echo "PRODUCT_G4_PERSISTENT_MODEL_RELINKED source_key=$key g3_savable_reused=1 rtl_elaboration=0"
  exit 0
fi

[[ -x "$SIM" && -s "$OUT/.g4-source-key" && -s "$OUT/.g4-binary-sha" &&
   "$(< "$OUT/.g4-source-key")" == "$(g4_key)" &&
   "$(< "$OUT/.g4-binary-sha")" == "$(sha256sum "$SIM" | awk '{print $1}')" ]] ||
  reject 'G4 Linux host re-link cache missing or stale; run build first'

MODEL_SHA="$(sha256sum "$SIM" | awk '{print $1}')"
PROOF="$(mktemp -d "$OUT/persist-G4.XXXXXXXX")"
PREFIX="$PROOF/persistent"
timeout --signal=TERM --kill-after=10s 900s \
  "$SIM" save "$FW_BIN" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 100000000 \
    'Linux version 6.6.143' 500000 > "$PROOF/save.log" 2>&1 || {
      tail -70 "$PROOF/save.log" >&2; reject 'G4 real OpenSBI/Linux banner save failed';
    }
(cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host > sha256.txt)
(cd "$PROOF" && sha256sum --check --status sha256.txt) || reject 'saved G4 pre-state hash mismatch'
[[ "$(sha256sum "$FW_BIN" | awk '{print $1}')" == "$FW_SHA" &&
   "$(sha256sum "$LINUX_IMAGE" | awk '{print $1}')" == "$LINUX_SHA" &&
   "$(sha256sum "$SIM" | awk '{print $1}')" == "$MODEL_SHA" ]] ||
   reject 'G4 firmware/kernel/model changed across process boundary'
timeout --signal=TERM --kill-after=10s 180s \
  "$SIM" restore "$FW_BIN" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 100000000 \
    'Linux version 6.6.143' 500000 > "$PROOF/restore.log" 2>&1 || {
      tail -70 "$PROOF/restore.log" >&2; reject 'G4 new-process restore/replay failed';
    }
(cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host \
  persistent.continuous.rtl persistent.continuous.host \
  persistent.restored.rtl persistent.restored.host > sha256.txt)
printf 'complete=1\ntop=%s\nmodel_sha256=%s\nfirmware_sha256=%s\nlinux_image_sha256=%s\nmilestone=Linux version 6.6.143\nboot_max=100000000\nreplay_cycles=500000\n' \
  "$TOP" "$MODEL_SHA" "$FW_SHA" "$LINUX_SHA" > "$PROOF/.complete"
verify_proof "$PROOF" | tee "$PROOF/qualification.txt"
echo "PRODUCT_G4_PERSISTENT_PROOF_DIR=$PROOF"
