#!/usr/bin/env bash
# V5 15B authenticated model *continuation*, never a new cold boot or a migration.
# INERT unless explicit qualification, signed model identity and PR workflow gate.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
reject() { echo "PRODUCT_SD1_V5_POSTCROSS_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
readonly MODEL_ROOT="$ROOT/build/product-sd1-model-migration-v5-15b-qualified"
readonly MODEL="$MODEL_ROOT/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
readonly QUAL="$MODEL_ROOT/qualification-manifest.txt"
readonly V5_MODEL="${AETHERCORE_SD1_V5_REVIEWED_MODEL_SHA:-}"
readonly V4_MODEL=ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a
readonly ORIGINAL_MODEL=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e
if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  echo 'PRODUCT_SD1_V5_PLAN_ONLY no_guest_cycles=1 dynamic_20M_reviewed_9992M_to_14992M=1'
  exit 0
fi
[[ $# == 3 && "$1" == --execute-reviewed-postcross-20m ]] ||
  reject 'usage: --plan | --execute-reviewed-postcross-20m ABS_STAGE_DIR TARGET_M'
[[ "${AETHERCORE_SD1_V5_15B_APPROVED:-}" == QUALIFIED_V5_15B_SIGNED_9992_MODEL_MIGRATION &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V6_MIGRATED_15B &&
   "$V5_MODEL" =~ ^[a-f0-9]{64}$ && "$V5_MODEL" != "$V4_MODEL" &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  reject 'reviewed V5 model/timing/approval qualification absent'
[[ "$2" == /* && -d "$2" && "$(realpath -e "$2")" == "$2" ]] || reject 'noncanonical source'
readonly SOURCE="$2" TARGET_M="$3"
[[ "$TARGET_M" =~ ^[0-9]+$ && "$TARGET_M" -ge 10012 && "$TARGET_M" -le 14992 &&
   "$(((TARGET_M-10012)%20))" -eq 0 ]] || reject 'outside strict V5 20M target lattice/15B cap'
readonly SOURCE_M=$((TARGET_M-20))
readonly START=$((SOURCE_M*1000000)) TARGET=$((TARGET_M*1000000))
readonly OUT="${AETHERCORE_SD1_V5_OUT:-}"
[[ "$OUT" == "$ROOT"/build/product-sd1-v5-chain-* && -d "$OUT" &&
   "$(realpath -e "$OUT")" == "$OUT" &&
   ! -e "$OUT/${TARGET_M}m" ]] || reject 'stage output path invalid or exists'
[[ "$SOURCE_M" == 9992 || "$SOURCE" == "$OUT/${SOURCE_M}m" ||
   "$SOURCE" == "$ROOT/build/product-sd1-v5-source/${SOURCE_M}m" ]] ||
  reject 'source not previous full reviewed state'
[[ -x "$MODEL" && ! -L "$MODEL" && -s "$QUAL" && ! -L "$QUAL" &&
   "$(sha "$MODEL")" == "$V5_MODEL" &&
   "$(field candidate_model_sha256 "$QUAL")" == "$V5_MODEL" &&
   "$(field previous_model_sha256 "$QUAL")" == "$V4_MODEL" &&
   "$(field replay_cycles "$QUAL")" == 20000000 &&
   "$(field full_rtl_identical "$QUAL")" == 1 &&
   "$(field full_host_except_model_sha_identical "$QUAL")" == 1 &&
   "$(field two_path_9992_host_rtl_identical "$QUAL")" == 1 &&
   "$(field two_path_9992_sd_cow_body_identical "$QUAL")" == 1 &&
   "$(field differential_card_progress_official_bytes_identical "$QUAL")" == 1 &&
   "$(field terminal_migration_guest_cycles "$QUAL")" == 0 &&
   "$(field cross_10b_executed "$QUAL")" == 0 &&
   "$(field debian_login_claim "$QUAL")" == 0 ]] ||
  reject 'V5 exact model and zero-step/differential qualification absent'
ready="$SOURCE/.migration-ready"
[[ -s "$ready" && ! -L "$ready" &&
   "$(field schema "$ready")" == AETSDCP1 &&
   "$(field boot_cycles "$ready")" == "$START" &&
   "$(field model_sha256 "$ready")" == "$V5_MODEL" &&
   "$(field previous_model_sha256 "$ready")" == "$V4_MODEL" &&
   "$(field original_model_sha256 "$ready")" == "$ORIGINAL_MODEL" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" &&
   "$(field debian_login_claim "$ready")" == 0 ]] ||
  reject 'source model/guest timing/sealed media lineage drift'
for kind in host rtl; do
  f="$SOURCE/persistent.pre.$kind"
  [[ -s "$f" && ! -L "$f" && "$(sha "$f")" == "$(field "snapshot_${kind}_sha256" "$ready")" ]] ||
    reject "source $kind snapshot SHA invalid"
done
[[ -s "$SOURCE/card-progress.json" && ! -L "$SOURCE/card-progress.json" &&
   "$(sha "$SOURCE/card-progress.json")" == "$(field card_progress_sha256 "$ready")" ]] ||
  reject 'source SD progress SHA invalid'
mapfile -d '' manifests < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ ${#manifests[@]} == 1 ]] || reject 'polling firmware manifest ambiguous'
polling="$(dirname "${manifests[0]}")"
fw="$polling/boot/fw_payload-polling.bin"
dtb="$polling/boot/product-spi-polling.dtb"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
linux="$ROOT/build/product-sd1-gpio-cs-diagnostic/linux/obj/arch/riscv/boot/Image"
rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
[[ "$(sha "$fw")" == "$FW_SHA" && "$(sha "$dtb")" == "$DTB_SHA" &&
   "$(sha "$sd")" == "$SD_SHA" && "$(sha "$linux")" == "$LINUX_SHA" &&
   "$(sha "$rootfs")" == "$ROOTFS_SHA" &&
   "$(field old_checkpoint_compatibility "${manifests[0]}")" == FORBIDDEN ]] ||
  reject 'signed firmware/DTB/SD/Linux/rootfs differs'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null || reject 'signed seed rejected'

mkdir -p "$OUT/logs"
stage="$(mktemp -d "$OUT/.stage-${TARGET_M}m.XXXXXXXX")"
log="$OUT/logs/advance-${TARGET_M}m.log"
started="$(date -u +%s)"
timeout --signal=TERM --kill-after=12s 20m \
  "$MODEL" advance "$fw" "$dtb" "$sd" "$SOURCE/persistent" \
  "$V5_MODEL" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$START" 20000000 "$stage/persistent" > "$log" 2>&1 || reject 'true V5 RTL advance failed'
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$START new_cycles=$TARGET delta=20000000 " "$log" ||
  reject 'true RTL 20M completion absent'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] || reject 'savable state missing'
python3 - "$SOURCE/persistent.pre.host" "$stage/persistent.pre.host" "$SD_SHA" \
  "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect,compare
before,after=map(Path,sys.argv[1:3])
old,new=decode(before),decode(after)
card_before,card_after=card_state_from_host(before),card_state_from_host(after)
with before.open('rb') as fh: cpu_before=inspect(fh)
with after.open('rb') as fh: cpu_after=inspect(fh)
cpu=compare(cpu_before,cpu_after)
if (new['cycles']-old['cycles']!=20000000 or new['commits']<=old['commits'] or
    cpu['retired_delta']!=new['commits']-old['commits'] or
    old['identity']!=new['identity'] or new['failure'] or
    card_after['sd_media_sha256']!=sys.argv[3]):
    raise SystemExit('V5_POSTCROSS_REJECTED: signed runtime/20M continuation drift')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if card_after[key]<card_before[key]:
        raise SystemExit('V5_POSTCROSS_REJECTED: SD counter regression')
Path(sys.argv[5]).write_text(json.dumps({
    'qualification':'MIGRATED_V5_REAL_PRODUCT_RTL_NOT_DEBIAN_PASS',
    'from_cycles':old['cycles'],'to_cycles':new['cycles'],
    'retired_delta':cpu['retired_delta'],'sim_wall_seconds':int(sys.argv[4]),
    'sd_command_delta':card_after['sd_command_count']-card_before['sd_command_count'],
    'native_init':new['native_init_started'],'ext4_root':new['ext4_root_mounted'],
    'host_login':new['login'],'cpu_observer_login':cpu_after['login_seen'],
    'guest_uart_login_observed':bool(new['login'] and cpu_after['login_seen']),
    'guest_uart_service_verified':False,'service_login_gate_pass':False,
    'debian_login_claim':False,'cpu_observer':cpu,
},sort_keys=True,indent=2)+'\n')
PY
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TARGET"
  echo "source_cycles=$START"
  echo "model_sha256=$V5_MODEL"
  echo "previous_model_sha256=$V4_MODEL"
  echo "original_model_sha256=$ORIGINAL_MODEL"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$ROOTFS_SHA"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$SOURCE/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$SOURCE/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$stage/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$stage/.migration-ready"
cp -- "$log" "$stage/advance.log"
[[ ! -e "$OUT/${TARGET_M}m" ]] || reject 'target appeared concurrently'
mv -T -- "$stage" "$OUT/${TARGET_M}m"
echo "PRODUCT_SD1_V5_REAL_RTL_STAGE_SAVED source=$START target=$TARGET complete_host_rtl_sd=1 no_debian_login_claim=1"
