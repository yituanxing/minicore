#!/usr/bin/env bash
set -euo pipefail

# Distinct Product SD1 --savable RTL/DDR/SPI-physical-card checkpoint.
# The full 1.8-billion-cycle Debian boot is FORBIDDEN here. A short same-cycle
# cross-process comparison proves restore mechanics, not ext4 mount or PID1.
#
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_sd1_short.sh build
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_sd1_short.sh run
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_sd1_short.sh save-only
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_sd1_short.sh restore-only EXISTING_PROOF_DIR
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 bash tools/ci/product_checkpoint_sd1_short.sh advance-only EXISTING_PROOF_DIR NEXT_ABSOLUTE_CYCLE
# bash tools/ci/product_checkpoint_sd1_short.sh verify-proof EXISTING_PROOF_DIR
#
# Restore-only is a *separate-process* replay without any cold-start cycles.
# Cross-CI warm-start requires TRUSTED external storage of *.pre.{rtl,host},
# .snapshot-ready and original model/FW/DTB/SD media, not merely saved logs.
# Raw 256MiB DDR and SD COW state must never be uploaded as CI artifacts by
# default: guest distro bytes/credentials could be included in the snapshot.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in build|run|save-only|restore-only|advance-only|verify-proof) ;; *)
  echo 'usage: product_checkpoint_sd1_short.sh build|run|save-only|restore-only|advance-only|verify-proof [PROOF_DIR [NEXT_CYCLE]]' >&2
  exit 2;;
esac

reject() { echo "PRODUCT_SD1_PERSISTENT_REJECTED: $*" >&2; exit 3; }
field() {
  local key="$1" manifest="$2" value
  [[ -s "$manifest" ]] || reject "missing SD1 artifact manifest $manifest"
  value="$(sed -n "s/^${key}=//p" "$manifest" | head -1)"
  [[ -n "$value" ]] || reject "missing SD1 manifest field $key"
  printf '%s\n' "$value"
}
sha() { sha256sum "$1" | awk '{print $1}'; }
require_exact_file_hash() {
  local file="$1" expected="$2" label="$3"
  [[ -f "$file" && "$expected" =~ ^[a-f0-9]{64}$ && "$(sha "$file")" == "$expected" ]] ||
    reject "$label bytes/SHA identity differ"
}
verify_snapshot_ready() {
  local dir="$1" key expected
  [[ -s "$dir/.snapshot-ready" && -s "$dir/persistent.pre.rtl" &&
     -s "$dir/persistent.pre.host" && -s "$dir/save.log" ]] || {
    echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: no complete pre-checkpoint' >&2; return 10;
  }
  for key in 'snapshot_ready=1' 'top=AetherCoreProductDebianSdRV64SimTop' \
             'schema=AETSDCP1' 'saves_full_rtl_ddr_sd=1'; do
    grep -Fxq "$key" "$dir/.snapshot-ready" || {
      echo "PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: missing $key" >&2; return 10;
    }
  done
  for key in model firmware dtb sd_image linux_image rootfs_result; do
    grep -Eq "^${key}_sha256=[a-f0-9]{64}$" "$dir/.snapshot-ready" || {
      echo "PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: bad $key SHA" >&2; return 10;
    }
  done
  grep -Eq '^boot_cycles=[1-9][0-9]{6,9}$' "$dir/.snapshot-ready" || {
    echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: invalid bounded boot checkpoint' >&2; return 10;
  }
  grep -Fxq 'replay_cycles=500000' "$dir/.snapshot-ready" || return 10
  [[ "$(stat -c%s "$dir/persistent.pre.host")" -gt 268435456 &&
     "$(head -c8 "$dir/persistent.pre.host")" == AETSDCP1 ]] || {
    echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: host DDR/card state absent' >&2; return 10;
  }
  expected="$(sed -n 's/^snapshot_rtl_sha256=//p' "$dir/.snapshot-ready" | head -1)"
  [[ "$expected" =~ ^[a-f0-9]{64}$ && "$(sha "$dir/persistent.pre.rtl")" == "$expected" ]] || return 10
  expected="$(sed -n 's/^snapshot_host_sha256=//p' "$dir/.snapshot-ready" | head -1)"
  [[ "$expected" =~ ^[a-f0-9]{64}$ && "$(sha "$dir/persistent.pre.host")" == "$expected" ]] || return 10
  grep -Eq '^PRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles=[0-9]+ commits=[1-9][0-9]* |^PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=[0-9]+ new_cycles=[0-9]+ delta=[0-9]+ commits=[1-9][0-9]* ' "$dir/save.log" || return 10
  echo 'PRODUCT_SD1_PERSISTENT_PRESTATE_VALIDATED full_rtl_ddr_spi_card=1 immutable_media_identity=1'
}
verify_proof() {
  local dir="$1" file sd_commands
  verify_snapshot_ready "$dir" || return 10
  [[ -s "$dir/.complete" && -s "$dir/sha256.txt" && -s "$dir/restore.log" ]] || {
    echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: no completed cross-process proof' >&2; return 10;
  }
  for file in persistent.continuous.rtl persistent.continuous.host \
              persistent.restored.rtl persistent.restored.host; do
    [[ -s "$dir/$file" ]] || return 10
  done
  grep -Fxq 'complete=1' "$dir/.complete" &&
    grep -Fxq 'top=AetherCoreProductDebianSdRV64SimTop' "$dir/.complete" &&
    grep -Fxq 'no_pid1_claim=1' "$dir/.complete" &&
    (cd "$dir" && sha256sum --check --status sha256.txt) || {
      echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: proof files tampered with' >&2; return 10;
    }
  [[ "$(grep -c '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$dir/save.log")" == 1 &&
     "$(grep -c '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$dir/restore.log")" == 1 &&
     "$(grep -c '^PRODUCT_SD1_PERSISTENT_CHECKPOINT_RESTORED ' "$dir/restore.log")" == 1 ]] || return 10
  grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$dir/save.log" > "$dir/continuous.state"
  grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$dir/restore.log" > "$dir/restored.state"
  cmp -s "$dir/continuous.state" "$dir/restored.state" &&
    cmp -s "$dir/persistent.continuous.rtl" "$dir/persistent.restored.rtl" &&
    cmp -s "$dir/persistent.continuous.host" "$dir/persistent.restored.host" || {
      echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: RTL/DDR/LRSC/SD/UART/IRQ continuation diverged' >&2; return 10;
    }
  sd_commands="$(sed -n 's/.* sd-cmds=\([0-9][0-9]*\) .*/\1/p' "$dir/continuous.state")"
  [[ "$sd_commands" =~ ^[0-9]+$ ]] || {
    echo 'PRODUCT_SD1_PERSISTENT_PROOF_REJECTED: SD card progress missing' >&2; return 10;
  }
  echo "PRODUCT_SD1_PERSISTENT_CROSS_PROCESS_PASS real_sd1_top=1 full_rtl=IDENTICAL host_256m_ddr=IDENTICAL sd_card_state=IDENTICAL uart_irq_trace=IDENTICAL replay_cycles=500000 sd_commands_observed=$sd_commands no_ext4_mount_claim=1 no_debian_pid1_claim=1"
}

if [[ "$ACTION" == verify-proof ]]; then
  [[ $# == 2 ]] || reject 'verify-proof needs the exact snapshot directory'
  verify_proof "$2"
  exit $?
fi

[[ "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-0}" == 1 ]] ||
  reject 'explicit opt-in required (SD1 model and real firmware/media)'
[[ "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${VERILATOR_THREADS:-1}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] ||
  reject 'pinned Verilator5.054/reference, single-thread, zero DDR wait mandatory'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  reject 'SD/DTB media are selected by immutable official SD1 manifest only'
[[ "$ACTION" != restore-only || $# == 2 ]] || reject 'restore-only needs snapshot directory'
[[ "$ACTION" != advance-only || $# == 3 ]] || reject 'advance-only needs source snapshot and next absolute cycle'
if [[ "$ACTION" != restore-only && "$ACTION" != advance-only && $# != 1 ]]; then
  reject 'only restore-only/advance-only accept snapshot directory arguments'
fi

TOP=AetherCoreProductDebianSdRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductDebianSdRV64SimTop
OUT="${PRODUCT_SD1_PERSISTENT_OUT:-$ROOT/build/product-sd1-persistent-checkpoint}"
MODEL="$OUT/savable-model"
OBJ="$MODEL/obj"
SIM="$OBJ/V$TOP"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
MODEL_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
VERILATOR_BIN="$TOOLCHAIN/verilator-5.054/bin/verilator"
FW_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
SD_RESULT="$ROOT/build/rv64-debian-sd1-sd/result.txt"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"

# This is preflight only: never call product_sim_sd1_manual.sh longrun.
# It validates full official SD1 Debian board/payload/FDT/rootfs/ext4 lineage
# and checks immutable SD media bytes before any model or checkpoint use.
AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_sd1_manual.sh preflight >/dev/null ||
  reject 'official SD1 Debian firmware/DTB/SD media preflight failed'
FW="$(field firmware_bin "$FW_RESULT")"
DTB="$(field fdt "$FW_RESULT")"
SD="$(field SD_IMAGE "$SD_RESULT")"
LINUX="$(field linux_image "$FW_RESULT")"
FW_SHA="$(field firmware_bin_sha256 "$FW_RESULT")"
DTB_SHA="$(field fdt_sha256 "$FW_RESULT")"
SD_SHA="$(field sd_image_sha256 "$SD_RESULT")"
LINUX_SHA="$(field linux_image_sha256 "$FW_RESULT")"
ROOTFS_SHA="$(field rootfs_result_sha256 "$FW_RESULT")"
require_exact_file_hash "$FW" "$FW_SHA" 'firmware'
require_exact_file_hash "$DTB" "$DTB_SHA" 'independent DTB'
require_exact_file_hash "$LINUX" "$LINUX_SHA" 'Linux Image'
require_exact_file_hash "$SD" "$SD_SHA" 'immutable SD image'
require_exact_file_hash "$ROOTFS_RESULT" "$ROOTFS_SHA" 'official Debian rootfs manifest'
grep -Fxq 'linux_stage=sd1' "$FW_RESULT" || reject 'real Debian SD1 firmware required'
grep -Fxq 'spi_sd=1' "$FW_RESULT" || reject 'non-SD payload forbidden'
grep -Fxq 'product_board=1' "$FW_RESULT" || reject '52-source Product board required'
grep -Fq 'root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init' "$FW_RESULT" ||
  reject 'SD1 canonical ext4 root bootargs absent'

source_key() {
  {
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh fingerprint "${MODEL_ARGS[@]}"
    sha256sum sim/product_rv64_checkpoint_sd1_main.cpp \
      tools/ci/product_checkpoint_sd1_short.sh \
      sim/l32_sd_spi_card.h sim/l32_sd_spi_snapshot_codec.h
    printf 'top=%s\nrtl-savable=1\nprofile=5.054/reference\n' "$TOP"
  } | sha256sum | awk '{print $1}'
}

if [[ "$ACTION" == build ]]; then
  AETHERCORE_VERILATOR_VERSION=5.054 AETHERCORE_VERILATOR_FAST_VERIFY=1 \
    bash tools/ensure_verilator_5_024.sh
  [[ -x "$VERILATOR_BIN" ]] || reject 'pinned Verilator5.054 unavailable'
  mkdir -p "$OUT/logs"
  key="$(source_key)"
  if [[ -x "$SIM" && -s "$MODEL/.savable-source-key" &&
        "$(< "$MODEL/.savable-source-key")" == "$key" ]] &&
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh verify "${MODEL_ARGS[@]}" >/dev/null; then
    echo "PRODUCT_SD1_PERSISTENT_MODEL_REUSED source_key=$key top=$TOP"
    exit 0
  fi
  mkdir -p "$OUT/rtl-seed" "$OBJ"
  make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
    TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$OUT/logs/rtl-build.log" 2>&1 || {
      tail -80 "$OUT/logs/rtl-build.log" >&2; reject 'SD1 RTL elaboration failed';
    }
  [[ -n "$(find "$OUT/rtl-seed/rtl" -maxdepth 1 -name '*.sv' -print -quit)" ]] ||
    reject 'distinct SD1 RTL not generated'
  PATH="$TOOLCHAIN/verilator-5.054/bin:$PATH" "$VERILATOR_BIN" \
    -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --savable --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
    -Mdir "$OBJ" -CFLAGS "$FLAGS" \
    "$OUT"/rtl-seed/rtl/*.sv "$ROOT/sim/product_rv64_checkpoint_sd1_main.cpp" \
    -j "${JOBS:-4}" > "$OUT/logs/verilator-savable-build.log" 2>&1 || {
      tail -80 "$OUT/logs/verilator-savable-build.log" >&2; reject 'SD1 --savable compile failed';
    }
  [[ "$key" == "$(source_key)" ]] || reject 'SD1 model/host source modified during compile'
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh record "${MODEL_ARGS[@]}" >/dev/null
  printf '%s\n' "$key" > "$MODEL/.savable-source-key"
  echo "PRODUCT_SD1_PERSISTENT_MODEL_BUILT source_key=$key separate_sd1_savable_rtl=1"
  exit 0
fi

[[ -x "$SIM" && -s "$MODEL/.savable-source-key" &&
   "$(< "$MODEL/.savable-source-key")" == "$(source_key)" ]] ||
  reject 'exact SD1 --savable simulator missing or stale; run build'
AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh verify "${MODEL_ARGS[@]}" >/dev/null ||
  reject 'SD1 --savable generated executable SHA/source/ABI invalid'
MODEL_SHA="$(sha "$SIM")"
BOOT_CYCLES="${AETHERCORE_SD1_PERSISTENT_BOOT_CYCLES:-12000000}"
REPLAY_CYCLES=500000
if [[ "$ACTION" == restore-only || "$ACTION" == advance-only ]]; then
  verify_snapshot_ready "$2" >/dev/null || reject 'source checkpoint incomplete or corrupted'
  BOOT_CYCLES="$(field boot_cycles "$2/.snapshot-ready")"
else
  [[ "$BOOT_CYCLES" =~ ^[1-9][0-9]*$ ]] &&
    (( BOOT_CYCLES >= 1000000 && BOOT_CYCLES <= 20000000 )) ||
    reject 'SD1 cold checkpoint only 1M..20M cycles, NEVER 1.8B Debian cold boot'
fi
UART_TRIGGER="${AETHERCORE_SD1_PERSISTENT_UART_TRIGGER:-}"
UART_COMMAND="${AETHERCORE_SD1_PERSISTENT_UART_COMMAND:-}"
POST_BUDGET="${AETHERCORE_SD1_PERSISTENT_UART_POST_BUDGET:-0}"
[[ "$POST_BUDGET" =~ ^[0-9]+$ ]] && (( POST_BUDGET <= 5000000 )) ||
  reject 'invalid SD1 UART policy/budget'
if [[ -n "$UART_TRIGGER" || -n "$UART_COMMAND" || "$POST_BUDGET" != 0 ]]; then
  POLICY_ARGS=("$UART_TRIGGER" "$UART_COMMAND" "$POST_BUDGET")
else
  POLICY_ARGS=()
fi
RUN_ARGS=("$FW" "$DTB" "$SD" "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" \
          "$LINUX_SHA" "$ROOTFS_SHA" "$BOOT_CYCLES" "$REPLAY_CYCLES" "${POLICY_ARGS[@]}")

current_id_matches() {
  local dir="$1" name expected
  for name in model firmware dtb sd_image linux_image rootfs_result; do
    case "$name" in
      model) expected="$MODEL_SHA";; firmware) expected="$FW_SHA";;
      dtb) expected="$DTB_SHA";; sd_image) expected="$SD_SHA";;
      linux_image) expected="$LINUX_SHA";; rootfs_result) expected="$ROOTFS_SHA";;
    esac
    grep -Fxq "${name}_sha256=$expected" "$dir/.snapshot-ready" ||
      reject "snapshot stale: $name bytes/source/ABI differ"
  done
  grep -Fxq "boot_cycles=$BOOT_CYCLES" "$dir/.snapshot-ready" ||
    reject 'snapshot boot cycle policy differs'
  grep -Fxq "replay_cycles=$REPLAY_CYCLES" "$dir/.snapshot-ready" ||
    reject 'snapshot replay cycle budget differs'
  grep -Fxq "uart_policy_sha256=$(printf '%s\0' "$UART_TRIGGER" "$UART_COMMAND" "$POST_BUDGET" | sha256sum | awk '{print $1}')" \
    "$dir/.snapshot-ready" || reject 'snapshot UART/IRQ policy differs'
}

run_restore() {
  local dir="$1" prefix="$1/persistent"
  verify_snapshot_ready "$dir" >/dev/null || reject 'existing save-only checkpoint invalid/incomplete'
  current_id_matches "$dir"
  [[ ! -e "$dir/persistent.restored.rtl" && ! -e "$dir/persistent.restored.host" ]] ||
    reject 'restore output exists; snapshots are immutable, use a fresh copied directory'
  # No guest cold-start loop: the new process reads the on-disk 12M-cycle
  # prestate, verifies model/FW/DTB/SD SHA and replays only 500k cycles.
  timeout --signal=TERM --kill-after=10s 210s \
    "$SIM" restore "${RUN_ARGS[@]:0:3}" "$prefix" "${RUN_ARGS[@]:3}" \
    > "$dir/restore.log" 2>&1 || {
      tail -60 "$dir/restore.log" >&2; reject 'SD1 restored process/replay failed';
    }
  require_exact_file_hash "$SIM" "$MODEL_SHA" 'model executable after replay'
  require_exact_file_hash "$FW" "$FW_SHA" 'firmware after replay'
  require_exact_file_hash "$DTB" "$DTB_SHA" 'DTB after replay'
  require_exact_file_hash "$SD" "$SD_SHA" 'immutable SD backing after replay'
}

# The .snapshot-ready manifest is a publish-once completion barrier. The
# component blobs are already closed; their content hashes and the immutable
# image/tool identities are all recorded before atomically publishing.
write_snapshot_ready() {
  local dir="$1" cycle="$2"
  {
    printf 'snapshot_ready=1\ntop=%s\nschema=AETSDCP1\nsaves_full_rtl_ddr_sd=1\n' "$TOP"
    printf 'model_sha256=%s\nfirmware_sha256=%s\ndtb_sha256=%s\nsd_image_sha256=%s\nlinux_image_sha256=%s\nrootfs_result_sha256=%s\n' \
      "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA"
    printf 'boot_cycles=%s\nreplay_cycles=%s\n' "$cycle" "$REPLAY_CYCLES"
    printf 'uart_policy_sha256=%s\n' "$(printf '%s\0' "$UART_TRIGGER" "$UART_COMMAND" "$POST_BUDGET" | sha256sum | awk '{print $1}')"
    printf 'snapshot_rtl_sha256=%s\nsnapshot_host_sha256=%s\n' \
      "$(sha "$dir/persistent.pre.rtl")" "$(sha "$dir/persistent.pre.host")"
  } > "$dir/.snapshot-ready.tmp"
  # Publish only after all snapshot payload and identity bytes are durable.
  # A process kill before rename leaves no eligible .snapshot-ready marker.
  sync -f "$dir/persistent.pre.rtl" "$dir/persistent.pre.host" "$dir/.snapshot-ready.tmp"
  mv "$dir/.snapshot-ready.tmp" "$dir/.snapshot-ready"
  sync -f "$dir"
  verify_snapshot_ready "$dir" >/dev/null || reject 'new prestate manifest is inconsistent'
}

mkdir -p "$OUT"
if [[ "$ACTION" == restore-only ]]; then
  PROOF="$(realpath "$2")"
  [[ -d "$PROOF" ]] || reject 'restore-only snapshot directory missing'
  run_restore "$PROOF"
  echo "PRODUCT_SD1_PERSISTENT_RESTORE_ONLY_COMPLETED prestate=$PROOF cross_process=1 cold_boot_cycles=0 no_same_cycle_equivalence_claim=1"
  if [[ -s "$PROOF/persistent.continuous.host" && -s "$PROOF/persistent.continuous.rtl" ]]; then
    (cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host \
      persistent.continuous.rtl persistent.continuous.host \
      persistent.restored.rtl persistent.restored.host > sha256.txt)
    printf 'complete=1\ntop=%s\nno_pid1_claim=1\n' "$TOP" > "$PROOF/.complete.tmp"
    mv "$PROOF/.complete.tmp" "$PROOF/.complete"
    verify_proof "$PROOF" | tee "$PROOF/qualification.txt"
  fi
  echo "PRODUCT_SD1_PERSISTENT_PROOF_DIR=$PROOF"
  exit 0
fi

if [[ "$ACTION" == advance-only ]]; then
  PREV="$(realpath "$2")"
  [[ -d "$PREV" ]] || reject 'source snapshot directory missing'
  verify_snapshot_ready "$PREV" >/dev/null || reject 'source checkpoint invalid'
  current_id_matches "$PREV"
  NEXT="$3"
  [[ "$NEXT" =~ ^[1-9][0-9]{0,9}$ ]] &&
    (( NEXT > BOOT_CYCLES && NEXT <= 1800000000 && NEXT-BOOT_CYCLES <= 20000000 )) ||
    reject 'stage NEXT_CYCLE must advance by 1..20M, never cold-reboot or overrun 1.8B'
  DELTA=$(( NEXT-BOOT_CYCLES ))
  STAGE="$(mktemp -d "$OUT/persist-SD1-stage.XXXXXXXX")"
  # Source prestate untouched; destination is distinct and initially empty.
  timeout --signal=TERM --kill-after=10s 330s \
    "$SIM" advance "${RUN_ARGS[@]:0:3}" "$PREV/persistent" \
      "${RUN_ARGS[@]:3:7}" "$DELTA" "${POLICY_ARGS[@]}" "$STAGE/persistent" \
    > "$STAGE/save.log" 2>&1 || {
      tail -60 "$STAGE/save.log" >&2; reject 'SD1 incremental warm advance failed';
    }
  require_exact_file_hash "$SIM" "$MODEL_SHA" 'model after staged advance'
  require_exact_file_hash "$FW" "$FW_SHA" 'firmware after staged advance'
  require_exact_file_hash "$DTB" "$DTB_SHA" 'DTB after staged advance'
  require_exact_file_hash "$SD" "$SD_SHA" 'SD image after staged advance'
  write_snapshot_ready "$STAGE" "$NEXT"
  echo "PRODUCT_SD1_PERSISTENT_STAGE_READY directory=$STAGE source=$PREV cold_boot_cycles=0 advanced_cycles=$DELTA absolute_cycle=$NEXT no_ext4_mount_claim=1 no_pid1_claim=1"
  echo "PRODUCT_SD1_PERSISTENT_PROOF_DIR=$STAGE"
  exit 0
fi

PROOF="$(mktemp -d "$OUT/persist-SD1.XXXXXXXX")"
PREFIX="$PROOF/persistent"
# Distinct fixed-cycle real SD1 model: short boundary, never Linux distro boot.
MODE=save
[[ "$ACTION" == save-only ]] && MODE=snapshot
timeout --signal=TERM --kill-after=10s 270s \
  "$SIM" "$MODE" "${RUN_ARGS[@]:0:3}" "$PREFIX" "${RUN_ARGS[@]:3}" \
  > "$PROOF/save.log" 2>&1 || {
    tail -60 "$PROOF/save.log" >&2; reject 'SD1 true Product bounded prestate save failed';
  }
require_exact_file_hash "$SIM" "$MODEL_SHA" 'model executable after save'
require_exact_file_hash "$FW" "$FW_SHA" 'firmware after save'
require_exact_file_hash "$DTB" "$DTB_SHA" 'DTB after save'
require_exact_file_hash "$SD" "$SD_SHA" 'immutable SD backing after save'
write_snapshot_ready "$PROOF" "$BOOT_CYCLES"
echo "PRODUCT_SD1_PERSISTENT_SNAPSHOT_READY directory=$PROOF boot_cycles=$BOOT_CYCLES full_rtl_ddr_card=1 no_pid1_claim=1"
if [[ "$ACTION" == save-only ]]; then
  echo "PRODUCT_SD1_PERSISTENT_PROOF_DIR=$PROOF"
  exit 0
fi
run_restore "$PROOF"
(cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host \
  persistent.continuous.rtl persistent.continuous.host \
  persistent.restored.rtl persistent.restored.host > sha256.txt)
printf 'complete=1\ntop=%s\nno_pid1_claim=1\n' "$TOP" > "$PROOF/.complete.tmp"
mv "$PROOF/.complete.tmp" "$PROOF/.complete"
verify_proof "$PROOF" | tee "$PROOF/qualification.txt"
echo "PRODUCT_SD1_PERSISTENT_PROOF_DIR=$PROOF"
