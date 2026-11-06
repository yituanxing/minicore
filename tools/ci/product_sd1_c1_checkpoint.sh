#!/usr/bin/env bash
# Explicit Product RTL C1 Linux-before-rootfs checkpoint driver. This does
# not alter the frozen SD1 strict checkpoint protocol or its normal firmware.
# C1 needs a DISTINCT OpenSBI payload embedding the C1 initramfs Linux Image.
# No checkpoint or guest DDR state is uploaded by this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in save|advance|release|continue|verify) ;; *)
  echo 'usage: product_sd1_c1_checkpoint.sh save|advance|release|continue|verify [CHECKPOINT_DIR [NEXT_CYCLE | NEW_MEDIA_RESULT]]' >&2
  exit 2;;
esac

reject() { printf 'PRODUCT_SD1_C1_CHECKPOINT_REJECTED: %s\n' "$*" >&2; exit 3; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() {
  local lines value
  [[ -s "$2" && -f "$2" && ! -L "$2" ]] || reject "missing regular manifest: $2"
  lines="$(sed -n "s/^$1=//p" "$2")"
  [[ -n "$lines" && "$lines" != *$'\n'* ]] || reject "missing/duplicate manifest field $1: $2"
  printf '%s' "$lines"
}
pin_file() {
  [[ -f "$1" && ! -L "$1" && "$2" =~ ^[a-f0-9]{64}$ && "$(sha "$1")" == "$2" ]] ||
    reject "$3 SHA256/regular file identity differs"
}
bounded_cycle() {
  [[ "$1" =~ ^[1-9][0-9]{0,9}$ ]] || reject "non-canonical bounded cycle: $1"
  (( 10#$1 <= 4000000000 )) || reject 'cycle exceeds explicit C1 host limit'
}

[[ "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-}" == 1 &&
   "${AETHERCORE_SD1_C1_CONFIRM:-}" == GENERIC_LINUX_ROOTFS_BOUNDARY &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${VERILATOR_THREADS:-1}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] ||
  reject 'explicit C1+SD1 opt-in, pinned savable RTL and reference timing are required'
[[ "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY ]] ||
  reject 'portable cross-run SD1 relay profile is required'

OUT="${PRODUCT_SD1_C1_OUT:-$ROOT/build/product-sd1-c1-checkpoints}"
SIM="$ROOT/build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
MODEL_KEY="$ROOT/build/product-sd1-persistent-checkpoint/savable-model/.savable-source-key"
FW_RESULT="${PRODUCT_SD1_C1_FW_RESULT:-$ROOT/build/product-sd1-c1-boot/result.txt}"
KERNEL_RESULT="$ROOT/build/product-sd1-c1-kernel/result.txt"
KERNEL_IMAGE="$ROOT/build/product-sd1-c1-kernel/obj/arch/riscv/boot/Image"
OLD_SD_RESULT="$ROOT/build/rv64-debian-sd1-sd/result.txt"
OLD_ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"

[[ -x "$SIM" && -s "$MODEL_KEY" ]] ||
  reject 'exact qualified SD1 --savable model is absent (run strict SD1 build action first)'
[[ "$(field rootfs_binding "$KERNEL_RESULT")" == NONE &&
   "$(field kernel_sha256 "$KERNEL_RESULT")" == "$(sha "$KERNEL_IMAGE")" ]] ||
  reject 'separate C1 kernel is missing or tied to a distribution'
grep -Fxq 'PRODUCT_SD1_C1_KERNEL_BUILD_RESULT: status=BUILT_UNQUALIFIED' "$KERNEL_RESULT" ||
  reject 'C1 Linux source manifest missing'

# The unchanged SD1 OpenSBI firmware embeds the ordinary native-init Linux
# Image and cannot possibly produce C1_READY. Do not silently use it.
grep -Fxq 'PRODUCT_SD1_C1_FIRMWARE_RESULT: status=READY_UNVERIFIED' "$FW_RESULT" ||
  reject 'separately verified C1 OpenSBI+Linux firmware is REQUIRED'
[[ "$(field linux_image_sha256 "$FW_RESULT")" == "$(sha "$KERNEL_IMAGE")" &&
   "$(field linux_image "$FW_RESULT")" == "$KERNEL_IMAGE" &&
   "$(field rootfs_binding "$FW_RESULT")" == NONE &&
   "$(field spi_sd "$FW_RESULT")" == 1 &&
   "$(field product_board "$FW_RESULT")" == 1 ]] ||
  reject 'C1 firmware does not embed the exact separate C1 Linux Image'
[[ "$(field bootargs "$FW_RESULT")" == *'root=/dev/mmcblk0p1'* &&
   "$(field bootargs "$FW_RESULT")" == *'rdinit=/init'* ]] ||
  reject 'C1 firmware must select Product SD and rootfs-independent PID1'

FW="$(field firmware_bin "$FW_RESULT")"
DTB="$(field fdt "$FW_RESULT")"
OLD_SD="$(field sd_image "$OLD_SD_RESULT")"
MODEL_SHA="$(sha "$SIM")"
FW_SHA="$(field firmware_bin_sha256 "$FW_RESULT")"
DTB_SHA="$(field fdt_sha256 "$FW_RESULT")"
LINUX_SHA="$(field linux_image_sha256 "$FW_RESULT")"
OLD_SD_SHA="$(field sd_image_sha256 "$OLD_SD_RESULT")"
OLD_ROOTFS_SHA="$(sha "$OLD_ROOTFS_RESULT")"
pin_file "$FW" "$FW_SHA" 'C1 firmware'
pin_file "$DTB" "$DTB_SHA" 'C1 DTB'
pin_file "$OLD_SD" "$OLD_SD_SHA" 'old immutable SD media'
[[ "$(field rootfs_result_sha256 "$OLD_SD_RESULT")" == "$OLD_ROOTFS_SHA" &&
   "$(field stage "$OLD_SD_RESULT")" == sd1 &&
   "$(field partition_table "$OLD_SD_RESULT")" == MBR &&
   "$(field partition1_fs "$OLD_SD_RESULT")" == ext4 ]] ||
  reject 'old SD medium not bound to qualified Debian ext4 rootfs manifest'

metadata() {
  local dir="$1" cycle="$2" marker="$3" active_media="$4" active_sha="$5" active_rootfs="$6" active_manifest="$7"
  {
    printf 'c1_snapshot_ready=1\nschema=PRODUCT_SD1_C1_V1\nphase=%s\n' "$marker"
    printf 'model_sha256=%s\nfirmware_sha256=%s\ndtb_sha256=%s\n' "$MODEL_SHA" "$FW_SHA" "$DTB_SHA"
    printf 'old_sd_sha256=%s\nlinux_sha256=%s\nold_rootfs_sha256=%s\n' "$OLD_SD_SHA" "$LINUX_SHA" "$OLD_ROOTFS_SHA"
    printf 'active_sd=%s\nactive_sd_sha256=%s\nactive_rootfs_sha256=%s\nactive_media_result=%s\nactive_media_result_sha256=%s\n' \
      "$active_media" "$active_sha" "$active_rootfs" "$active_manifest" "$(sha "$active_manifest")"
    printf 'cycle=%s\nrtl_sha256=%s\nhost_sha256=%s\n' "$cycle" \
      "$(sha "$dir/persistent.pre.rtl")" "$(sha "$dir/persistent.pre.host")"
  } > "$dir/.c1-ready.tmp"
  sync -f "$dir/persistent.pre.rtl" "$dir/persistent.pre.host" "$dir/.c1-ready.tmp"
  mv "$dir/.c1-ready.tmp" "$dir/.c1-ready"
  sync -f "$dir"
}

verify_checkpoint() {
  local dir="$1" key val expected active_manifest active_media active_sha active_rootfs phase
  [[ -d "$dir" && -s "$dir/.c1-ready" && -s "$dir/persistent.pre.rtl" &&
     -s "$dir/persistent.pre.host" ]] || reject 'C1 full RTL/DDR/SPI prestate absent'
  [[ "$(field c1_snapshot_ready "$dir/.c1-ready")" == 1 &&
     "$(field schema "$dir/.c1-ready")" == PRODUCT_SD1_C1_V1 ]] ||
    reject 'not a qualified C1 wrapper manifest'
  phase="$(field phase "$dir/.c1-ready")"
  [[ "$phase" == armed || "$phase" == released ]] || reject 'unknown C1 checkpoint phase'
  for key in model firmware dtb old_sd linux old_rootfs; do
    case "$key" in
      model) expected="$MODEL_SHA";; firmware) expected="$FW_SHA";;
      dtb) expected="$DTB_SHA";; old_sd) expected="$OLD_SD_SHA";;
      linux) expected="$LINUX_SHA";; old_rootfs) expected="$OLD_ROOTFS_SHA";;
    esac
    [[ "$(field "${key}_sha256" "$dir/.c1-ready")" == "$expected" ]] ||
      reject "C1 checkpoint stale: $key identity differs"
  done
  active_manifest="$(field active_media_result "$dir/.c1-ready")"
  active_media="$(field active_sd "$dir/.c1-ready")"
  active_sha="$(field active_sd_sha256 "$dir/.c1-ready")"
  active_rootfs="$(field active_rootfs_sha256 "$dir/.c1-ready")"
  pin_file "$active_manifest" "$(field active_media_result_sha256 "$dir/.c1-ready")" 'active media manifest'
  pin_file "$active_media" "$active_sha" 'checkpoint active immutable SD image'
  [[ "$(field sd_image "$active_manifest")" == "$active_media" &&
     "$(field sd_image_sha256 "$active_manifest")" == "$active_sha" &&
     "$(field rootfs_result_sha256 "$active_manifest")" == "$active_rootfs" ]] ||
    reject 'active media/rootfs provenance differs from image manifest'
  if [[ "$phase" == armed ]]; then
    [[ "$active_sha" == "$OLD_SD_SHA" && "$active_rootfs" == "$OLD_ROOTFS_SHA" ]] ||
      reject 'pre-release C1 state consumed rootfs-dependent media'
  fi
  pin_file "$dir/persistent.pre.rtl" "$(field rtl_sha256 "$dir/.c1-ready")" 'RTL checkpoint'
  pin_file "$dir/persistent.pre.host" "$(field host_sha256 "$dir/.c1-ready")" 'host DDR/SPI checkpoint'
  [[ "$(stat -c%s "$dir/persistent.pre.host")" -gt 268435456 &&
     "$(head -c8 "$dir/persistent.pre.host")" == AETSDCP1 ]] ||
    reject 'C1 host DDR/card state missing or corrupt'
  val="$(field cycle "$dir/.c1-ready")"
  bounded_cycle "$val"
  printf '%s' "$val"
}

if [[ "$ACTION" == save ]]; then
  [[ $# == 1 ]] || reject 'save takes no positional parameters'
  CYCLES="${PRODUCT_SD1_C1_BOOT_CYCLES:-12000000}"
  bounded_cycle "$CYCLES"
  (( CYCLES >= 1000000 && CYCLES <= 20000000 )) || reject 'C1 cold prefix exceeds 20M'
  mkdir -p "$OUT"
  DEST="$(mktemp -d "$OUT/c1-cold.XXXXXXXX")"
  timeout --signal=TERM --kill-after=10s 300s "$SIM" c1-save \
    "$FW" "$DTB" "$OLD_SD" "$DEST/persistent" \
    "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$OLD_SD_SHA" "$LINUX_SHA" "$OLD_ROOTFS_SHA" \
    "$CYCLES" 1 > "$DEST/save.log" 2>&1 || {
      tail -30 "$DEST/save.log" >&2; reject 'C1 cold checkpoint failed';
    }
  grep -Fq 'PRODUCT_SD1_C1_PREFIX_SAVED ' "$DEST/save.log" ||
    reject 'C1 guest read tracking did not arm'
  metadata "$DEST" "$CYCLES" armed "$OLD_SD" "$OLD_SD_SHA" "$OLD_ROOTFS_SHA" "$OLD_SD_RESULT"
  verify_checkpoint "$DEST" >/dev/null
  printf 'PRODUCT_SD1_C1_CHECKPOINT_SAVED directory=%s cycle=%s rootfs_binding=NONE\n' "$DEST" "$CYCLES"
  exit 0
fi

[[ ( "$ACTION" == verify && $# == 2 ) ||
   ( "$ACTION" != verify && $# == 3 ) ]] ||
  reject 'verify needs checkpoint; advance/continue need checkpoint and next cycle; release needs checkpoint and new media manifest'
SOURCE="$(realpath "$2")"
CURRENT="$(verify_checkpoint "$SOURCE")"
if [[ "$ACTION" == verify ]]; then
  [[ $# == 2 ]] || reject 'verify takes one checkpoint'
  echo "PRODUCT_SD1_C1_CHECKPOINT_VERIFIED directory=$SOURCE cycle=$CURRENT"
  exit 0
fi

PHASE="$(field phase "$SOURCE/.c1-ready")"
if [[ "$ACTION" == advance ]]; then
  [[ "$PHASE" == armed ]] || reject 'C1 read-ledger advance must precede media release'
elif [[ "$ACTION" == continue ]]; then
  [[ "$PHASE" == released ]] || reject 'native ext4/PID1 continuation requires released C1 media'
else
  [[ "$PHASE" == armed ]] || reject 'C1 media release must occur exactly once'
fi
mkdir -p "$OUT"
DEST="$(mktemp -d "$OUT/c1-stage.XXXXXXXX")"
if [[ "$ACTION" == advance ]]; then
  NEXT="$3"
  bounded_cycle "$NEXT"
  (( NEXT > CURRENT && NEXT-CURRENT <= 20000000 )) ||
    reject 'C1 incremental stage must advance 1..20M guest cycles'
  DELTA=$((NEXT-CURRENT))
  timeout --signal=TERM --kill-after=10s 360s "$SIM" c1-advance \
    "$FW" "$DTB" "$OLD_SD" "$SOURCE/persistent" \
    "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$OLD_SD_SHA" "$LINUX_SHA" "$OLD_ROOTFS_SHA" \
    "$CURRENT" "$DELTA" "$DEST/persistent" > "$DEST/save.log" 2>&1 || {
      tail -30 "$DEST/save.log" >&2; reject 'C1 warm advance rejected';
    }
  grep -Fq 'PRODUCT_SD1_C1_PREFIX_ADVANCED ' "$DEST/save.log" ||
    reject 'C1 warm state/read-ledger did not survive'
  metadata "$DEST" "$NEXT" armed "$OLD_SD" "$OLD_SD_SHA" "$OLD_ROOTFS_SHA" "$OLD_SD_RESULT"
  verify_checkpoint "$DEST" >/dev/null
  echo "PRODUCT_SD1_C1_CHECKPOINT_ADVANCED directory=$DEST cycle=$NEXT cold_cycles=0"
  exit 0
fi

if [[ "$ACTION" == continue ]]; then
  NEXT="$3"
  bounded_cycle "$NEXT"
  (( NEXT > CURRENT && NEXT-CURRENT <= 20000000 )) ||
    reject 'C1 released continuation must advance 1..20M guest cycles'
  DELTA=$((NEXT-CURRENT))
  ACTIVE_RESULT="$(field active_media_result "$SOURCE/.c1-ready")"
  ACTIVE_SD="$(field active_sd "$SOURCE/.c1-ready")"
  ACTIVE_SHA="$(field active_sd_sha256 "$SOURCE/.c1-ready")"
  ACTIVE_ROOTFS="$(field active_rootfs_sha256 "$SOURCE/.c1-ready")"
  # A strict host advance reads the post-C1 identity and the exact UART
  # injection policy saved when c1-release delivered GO. It skips cold boot.
  timeout --signal=TERM --kill-after=10s 360s "$SIM" advance \
    "$FW" "$DTB" "$ACTIVE_SD" "$SOURCE/persistent" \
    "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$ACTIVE_SHA" "$LINUX_SHA" "$ACTIVE_ROOTFS" \
    "$CURRENT" "$DELTA" '' $'AETHERCORE_C1_GO\n' 0 "$DEST/persistent" > "$DEST/save.log" 2>&1 || {
      tail -30 "$DEST/save.log" >&2; reject 'released C1 native-init continuation rejected';
    }
  grep -Fq 'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED ' "$DEST/save.log" ||
    reject 'released C1 warm continuation lost the full RTL/DDR/card state'
  metadata "$DEST" "$NEXT" released "$ACTIVE_SD" "$ACTIVE_SHA" "$ACTIVE_ROOTFS" "$ACTIVE_RESULT"
  verify_checkpoint "$DEST" >/dev/null
  echo "PRODUCT_SD1_C1_RELEASED_CONTINUED directory=$DEST cycle=$NEXT cold_cycles=0 not_native_login_pass=1"
  exit 0
fi

NEW_SD_RESULT="$(realpath "$3")"
[[ "$(field partition_table "$NEW_SD_RESULT")" == MBR &&
   "$(field partition1_fs "$NEW_SD_RESULT")" == ext4 ]] ||
  reject 'C1 replacement must be an ext4 MBR-rootfs image'
NEW_SD="$(field sd_image "$NEW_SD_RESULT")"
NEW_SD_SHA="$(field sd_image_sha256 "$NEW_SD_RESULT")"
NEW_ROOTFS_SHA="$(field rootfs_result_sha256 "$NEW_SD_RESULT")"
pin_file "$NEW_SD" "$NEW_SD_SHA" 'new rootfs SD image'
[[ "$NEW_ROOTFS_SHA" =~ ^[a-f0-9]{64}$ ]] ||
  reject 'new rootfs source manifest SHA absent'
[[ "$(realpath "$NEW_SD")" != "$(realpath "$OLD_SD")" ]] ||
  reject 'C1 rootfs release needs a distinct immutable image'
RELEASE_CYCLES="${PRODUCT_SD1_C1_RELEASE_CYCLES:-1000000}"
bounded_cycle "$RELEASE_CYCLES"
(( RELEASE_CYCLES <= 5000000 )) || reject 'C1 release budget exceeds 5M'
timeout --signal=TERM --kill-after=10s 360s "$SIM" c1-release \
  "$FW" "$DTB" "$OLD_SD" "$SOURCE/persistent" \
  "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$OLD_SD_SHA" "$LINUX_SHA" "$OLD_ROOTFS_SHA" \
  "$CURRENT" "$RELEASE_CYCLES" "$NEW_SD" "$NEW_SD_SHA" "$NEW_ROOTFS_SHA" \
  "$DEST/persistent" > "$DEST/save.log" 2>&1 || {
    tail -30 "$DEST/save.log" >&2; reject 'C1 guest boundary/media rebind rejected';
  }
grep -Fq 'PRODUCT_SD1_C1_MEDIA_REBOUND_AND_GO_SENT ' "$DEST/save.log" ||
  reject 'C1 release did not inject GO after validated media rebind'
metadata "$DEST" "$((CURRENT+RELEASE_CYCLES))" released "$NEW_SD" "$NEW_SD_SHA" "$NEW_ROOTFS_SHA" "$NEW_SD_RESULT"
verify_checkpoint "$DEST" >/dev/null
echo "PRODUCT_SD1_C1_ROOTFS_RELEASED directory=$DEST cycle=$((CURRENT+RELEASE_CYCLES)) cold_cycles=0 not_native_init_pass=1"
