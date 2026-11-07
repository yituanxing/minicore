#!/usr/bin/env bash
set -euo pipefail

# Manual bounded RTL proof using the fixed official ext4 SD, but a NEW patched
# Linux/Image/OpenSBI pair. Does not touch the frozen 312M SD1 chain.
# The existing Product --savable model must be built separately (via
# product_checkpoint_sd1_short.sh build using the official seed).
#
#   AETHERCORE_SD1_GPIO_CS_RTL_DIAG=1 bash ... run
#   AETHERCORE_SD1_GPIO_CS_RTL_DIAG=1 bash ... advance SOURCE_DIR NEXT_CYCLE
# Outputs a full state and a content-addressed manifest under the distinct
# build/product-sd1-gpio-cs-rtl-checkpoint directory.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
fail() { echo "PRODUCT_SD1_GPIO_CS_RTL_DIAG_REJECTED: $*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

[[ "${AETHERCORE_SD1_GPIO_CS_RTL_DIAG:-0}" == 1 ]] || fail 'explicit diagnostic opt-in required'
[[ "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${VERILATOR_THREADS:-1}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY ]] ||
  fail 'pinned portable Verilator 5.054/reference, one thread and explicit relay profile required'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'no unbound media overrides allowed'

mode="${1:-}"
case "$mode" in
  run) [[ $# == 1 ]] || fail 'run accepts no positional arguments' ;;
  advance) [[ $# == 3 ]] || fail 'advance requires SOURCE_DIR NEXT_ABSOLUTE_CYCLE' ;;
  *) fail 'usage: run | advance SOURCE_DIR NEXT_ABSOLUTE_CYCLE' ;;
esac

# This checks the ORIGINAL signed official Debian media, not our patched FW.
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  fail 'original signed Product SD1 media verification failed'
official="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
official_rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
diag="$ROOT/build/product-sd1-gpio-cs-diagnostic"
manifest="$diag/diagnostic-manifest.txt"
linux="$diag/linux/obj/arch/riscv/boot/Image"
boot="$diag/boot/result.txt"
[[ -s "$official" && -s "$official_rootfs" && -s "$sd" && -s "$linux" &&
   -s "$boot" && -s "$manifest" ]] || fail 'complete diagnostic Image/OpenSBI and official SD missing'
grep -Fxq 'PRODUCT_SD1_GPIO_CS_DIAG_ARTIFACTS: status=READY_FOR_SHORT_RTL_ONLY' "$manifest" ||
  fail 'diagnostic build has no explicit ready-for-short-RTL marker'
grep -Fxq 'qualification=GPIO_CS_DIAGNOSTIC_KERNEL_FIRMWARE_NOT_DEBIAN_PASS' "$manifest" ||
  fail 'diagnostic build qualification missing'
patch_sha=ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638
[[ "$(field patch_sha256 "$manifest")" == "$patch_sha" &&
   "$(field original_linux_sha256 "$manifest")" == "$(sha "$official")" &&
   "$(field linux_image_sha256 "$manifest")" == "$(sha "$linux")" &&
   "$(field rootfs_result_sha256 "$manifest")" == "$(sha "$official_rootfs")" &&
   "$(field sd_image_sha256 "$manifest")" == "$(sha "$sd")" ]] ||
  fail 'diagnostic patch/Linux and immutable official SD/rootfs identities differ'
fw="$(field firmware_bin "$boot")"
dtb="$(field fdt "$boot")"
[[ "$fw" == "$diag/boot/"* && "$dtb" == "$diag/boot/"* &&
   -s "$fw" && -s "$dtb" ]] || fail 'only isolated Product diagnostic firmware and DTB allowed'
fw_sha="$(sha "$fw")"
dtb_sha="$(sha "$dtb")"
sd_sha="$(sha "$sd")"
linux_sha="$(sha "$linux")"
rootfs_sha="$(sha "$official_rootfs")"
[[ "$(field firmware_sha256 "$manifest")" == "$fw_sha" &&
   "$(field dtb_sha256 "$manifest")" == "$dtb_sha" &&
   "$(field linux_image_sha256 "$boot")" == "$linux_sha" &&
   "$(field spi_sd "$boot")" == 1 &&
   "$(field product_board "$boot")" == 1 ]] ||
  fail 'Product patched firmware/DTB identities or SPI/PLIC profile mismatch'
grep -Fq 'root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init' "$boot" ||
  fail 'native SD1 ext4 /sbin/init root bootargs missing'

out="$ROOT/build/product-sd1-gpio-cs-rtl-checkpoint"
sim="$ROOT/build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -x "$sim" ]] || fail 'exact savable RTL model not built yet'
model_sha="$(sha "$sim")"
sim_source_key="$ROOT/build/product-sd1-persistent-checkpoint/savable-model/.savable-source-key"
[[ -s "$sim_source_key" ]] || fail 'source/ABI model fingerprint missing'

if [[ "$mode" == run ]]; then
  [[ ! -e "$out" ]] || fail 'existing diagnostic checkpoints must not be overwritten'
  mkdir -p "$out"
  stage="$(mktemp -d "$out/boot-12m.XXXXXXXX")"
  prefix="$stage/persistent"
  start_cycles=12000000
  save_started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  save_started_epoch="$(date -u +%s)"
  # Independent processes replay the same 500k cycles from a complete,
  # content-verified prestate. Different binaries/media fail in C++.
  timeout --signal=TERM --kill-after=10s 300s \
    "$sim" save "$fw" "$dtb" "$sd" "$prefix" \
      "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
      "$start_cycles" 500000 > "$stage/save.log" 2>&1 || {
        tail -50 "$stage/save.log" >&2; fail 'patched 12M Product cold-start/save failed';
      }
  save_elapsed_seconds="$(( $(date -u +%s) - save_started_epoch ))"
  restore_started_epoch="$(date -u +%s)"
  timeout --signal=TERM --kill-after=10s 210s \
    "$sim" restore "$fw" "$dtb" "$sd" "$prefix" \
      "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
      "$start_cycles" 500000 > "$stage/restore.log" 2>&1 || {
        tail -50 "$stage/restore.log" >&2; fail 'patched 12M same-cycle restore failed';
      }
  restore_elapsed_seconds="$(( $(date -u +%s) - restore_started_epoch ))"
  grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$stage/save.log" > "$stage/continuous.state"
  grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$stage/restore.log" > "$stage/restored.state"
  cmp -s "$stage/continuous.state" "$stage/restored.state" &&
    cmp -s "$stage/persistent.continuous.rtl" "$stage/persistent.restored.rtl" &&
    cmp -s "$stage/persistent.continuous.host" "$stage/persistent.restored.host" ||
    fail 'new kernel 12M RTL+DDR+SD cross-process state mismatch'
  grep -Fq 'PRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles=12000000 ' "$stage/save.log" ||
    fail '12M authentic retired RTL checkpoint missing'
else
  old="$(realpath "$2")"
  [[ -d "$old" && -s "$old/.diag-ready" ]] || fail 'source diagnostic checkpoint missing'
  [[ "$old" == "$out/"* ]] || fail 'source must belong to isolated diagnostic chain'
  [[ ! -L "$old/.diag-ready" && ! -L "$old/persistent.pre.rtl" &&
     ! -L "$old/persistent.pre.host" ]] || fail 'source contains symlinks'
  [[ "$(field qualification "$old/.diag-ready")" == SD1_GPIO_CS_DIAGNOSTIC_CHECKPOINT_NOT_DEBIAN_PASS &&
     "$(field top "$old/.diag-ready")" == AetherCoreProductDebianSdRV64SimTop &&
     "$(field schema "$old/.diag-ready")" == AETSDCP1 &&
     "$(field patch_sha256 "$old/.diag-ready")" == "$patch_sha" &&
     "$(field source_model_key "$old/.diag-ready")" == "$(< "$sim_source_key")" ]] ||
    fail 'source diagnostic qualification/patch/model build key incompatible'
  duplicate_fields="$(cut -d = -f 1 "$old/.diag-ready" | sort | uniq -d)"
  [[ -z "$duplicate_fields" ]] || fail 'duplicate source checkpoint manifest fields'
  for key in model firmware dtb sd_image linux_image rootfs_result; do
    case "$key" in
      model) expected="$model_sha";; firmware) expected="$fw_sha";;
      dtb) expected="$dtb_sha";; sd_image) expected="$sd_sha";;
      linux_image) expected="$linux_sha";; rootfs_result) expected="$rootfs_sha";;
    esac
    [[ "$(field "${key}_sha256" "$old/.diag-ready")" == "$expected" ]] ||
      fail "source checkpoint incompatible $key SHA"
  done
  for key in rtl host; do
    [[ "$(field "snapshot_${key}_sha256" "$old/.diag-ready")" == "$(sha "$old/persistent.pre.$key")" ]] ||
      fail 'source state bytes changed'
  done
  start_cycles="$(field boot_cycles "$old/.diag-ready")"
  next="$3"
  [[ "$start_cycles" =~ ^[0-9]+$ && "$next" =~ ^[0-9]+$ &&
     "$next" -gt "$start_cycles" && "$next" -le 4000000000 &&
     "$((next-start_cycles))" -le 20000000 ]] ||
    fail 'each warm advance must be 1..20M and within 4B cycles'
  stage="$(mktemp -d "$out/advance.XXXXXXXX")"
  prefix="$stage/persistent"
  save_started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  save_started_epoch="$(date -u +%s)"
  advancement_cycles="$((next-start_cycles))"
  timeout --signal=TERM --kill-after=10s 330s \
    "$sim" advance "$fw" "$dtb" "$sd" "$old/persistent" \
      "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
      "$start_cycles" "$((next-start_cycles))" "$prefix" \
      > "$stage/save.log" 2>&1 || {
        tail -50 "$stage/save.log" >&2; fail 'patched Product warm advance failed';
      }
  save_elapsed_seconds="$(( $(date -u +%s) - save_started_epoch ))"
  grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$start_cycles new_cycles=$next " \
    "$stage/save.log" || fail 'advance completion marker missing'
  start_cycles="$next"
fi

# Persist measured host-time separately from guest-cycle milestones. Future
# Linux stage profiling can compare versions without confusing CPU cycles,
# simulation throughput, replay cost, or boot wall-time.
{
  echo "mode=$mode"
  echo "started_utc=$save_started_utc"
  echo "ended_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "save_or_advance_elapsed_seconds=$save_elapsed_seconds"
  if [[ "$mode" == run ]]; then
    echo 'cold_boot_target_cycles=12000000'
    echo 'same_cycle_replay_cycles=500000'
    echo "restore_elapsed_seconds=$restore_elapsed_seconds"
  else
    echo "advance_delta_cycles=$advancement_cycles"
    echo "checkpoint_total_cycles=$start_cycles"
  fi
} > "$stage/phase-timing.txt"

[[ -s "$stage/persistent.pre.rtl" && -s "$stage/persistent.pre.host" ]] ||
  fail 'full Product state was not saved'
{
  echo 'qualification=SD1_GPIO_CS_DIAGNOSTIC_CHECKPOINT_NOT_DEBIAN_PASS'
  echo 'top=AetherCoreProductDebianSdRV64SimTop'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$start_cycles"
  echo "source_model_key=$(< "$sim_source_key")"
  echo "patch_sha256=$patch_sha"
  echo "model_sha256=$model_sha"
  echo "firmware_sha256=$fw_sha"
  echo "dtb_sha256=$dtb_sha"
  echo "sd_image_sha256=$sd_sha"
  echo "linux_image_sha256=$linux_sha"
  echo "rootfs_result_sha256=$rootfs_sha"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo "phase_timing_sha256=$(sha "$stage/phase-timing.txt")"
} > "$stage/.diag-ready.tmp"
mv "$stage/.diag-ready.tmp" "$stage/.diag-ready"
sha "$stage/.diag-ready" > "$stage/diag-ready.sha256"
echo "PRODUCT_SD1_GPIO_CS_DIAG_SHORT_STATE_SAVED cycle=$start_cycles full_rtl_ddr_sd=1 prefix=$prefix no_CMD0_or_Debian_success_claim=1"
