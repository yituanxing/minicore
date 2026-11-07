#!/usr/bin/env bash
set -euo pipefail

# Repeatable, publish-once real Product RTL 20M stage.  This script NEVER
# starts at reset, never recompiles RTL, and never mutates the source snapshot.
# Invoked five times by a single label-only workflow, with each successful
# complete snapshot uploaded *before* the following stage is attempted.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
die() { echo "PRODUCT_SD1_MIGRATED_AUTORELAY_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
[[ $# == 2 ]] || die 'usage: SOURCE_COMPLETE_STAGE_DIR TARGET_MILLIONS (4032..4112 in 20M steps)'
[[ "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V2_MIGRATED &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  die 'exact original SD1 migrated Product RTL timing profile required'

case "$2" in
  4032|4052|4072|4092|4112) target_m="$2" ;;
  *) die 'unauthorized milestone or missing 20M stage' ;;
esac
target="$((target_m*1000000))"
source_dir="$(realpath -e "$1")"
[[ "$source_dir" == "$ROOT/build/product-sd1-migrated-cross4b/4012m" ||
   "$source_dir" == "$ROOT/build/product-sd1-migrated-autorelay/"* ]] ||
  die 'foreign state directory; only authenticated prior Product checkpoint permitted'
ready="$source_dir/.migration-ready"
[[ -s "$ready" && ! -L "$ready" ]] || die 'missing source publish-once seal'
start="$(field boot_cycles "$ready")"
[[ "$start" =~ ^[0-9]+$ && "$((target-start))" -eq 20000000 &&
   "$((start%1000000))" -eq 0 && "$target" -le 4112000000 ]] ||
  die 'stage must restore immediately previous 20M milestone'
[[ "$(field schema "$ready")" == AETSDCP1 ]] || die 'source Host schema mismatch'
case "$(field qualification "$ready")" in
  PRODUCT_SD1_MIGRATED_REAL_RTL_CROSS_4B_NOT_DEBIAN_PASS|\
  PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS) ;;
  *) die 'source is not a complete migrated Product RTL stage' ;;
esac

MODEL_SHA=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461
OLD_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
qual="$ROOT/build/product-sd1-model-migration-v1/qualification-manifest.txt"
sim="$ROOT/build/product-sd1-model-migration-v1/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -s "$qual" && ! -L "$qual" && -x "$sim" && ! -L "$sim" &&
   "$(field candidate_model_sha256 "$qual")" == "$MODEL_SHA" &&
   "$(field original_model_sha256 "$qual")" == "$OLD_SHA" &&
   "$(sha "$sim")" == "$MODEL_SHA" ]] || die 'frozen QUALIFIED candidate executable mismatch'
grep -Fxq 'PRODUCT_SD1_MODEL_MIGRATION_V1: status=QUALIFIED_UNDER_4B_ONLY' "$qual" ||
  die 'first 20M differential was never qualified'
[[ "$(field model_sha256 "$ready")" == "$MODEL_SHA" &&
   "$(field original_model_sha256 "$ready")" == "$OLD_SHA" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" =~ ^[a-f0-9]{64}$ ]] ||
  die 'previous stage model/media identity drift'
for kind in host rtl; do
  file="$source_dir/persistent.pre.$kind"
  [[ -s "$file" && ! -L "$file" &&
     "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
    die "previous 20M source $kind snapshot unsealed"
done
[[ -s "$source_dir/card-progress.json" && ! -L "$source_dir/card-progress.json" &&
   "$(field card_progress_sha256 "$ready")" == "$(sha "$source_dir/card-progress.json")" ]] ||
  die 'previous stage activity evidence unsealed'
if [[ "$start" == 4012000000 ]]; then
  [[ "$(sha "$source_dir/persistent.pre.host")" == 975524bafd1c64169d012ae0d2d773f50feafaee771e401a2c5b527781c436d5 &&
     "$(sha "$source_dir/persistent.pre.rtl")" == 2dba2498e577b88c8225fb60891d06191761ad61c59b4032f9b4c82182112bb4 ]] ||
    die 'original 4012M source artifact SHA changed'
fi

# Full official signed seed is verified exactly ONCE by the workflow before
# the first guest tick.  Each segment rechecks byte hashes of all live media
# so a later stage cannot silently use replaced FW/FDT/SD/Linux/rootfs bytes.
mapfile -d '' candidate_lists < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ "${#candidate_lists[@]}" == 1 ]] || die 'must have exactly one official polling firmware manifest'
polling="$(dirname "${candidate_lists[0]}")"
fw="$polling/boot/fw_payload-polling.bin"
dtb="$polling/boot/product-spi-polling.dtb"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
linux="$ROOT/build/product-sd1-gpio-cs-diagnostic/linux/obj/arch/riscv/boot/Image"
rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
[[ "$(sha "$fw")" == "$FW_SHA" && "$(sha "$dtb")" == "$DTB_SHA" &&
   "$(sha "$sd")" == "$SD_SHA" && "$(sha "$linux")" == "$LINUX_SHA" &&
   "$(sha "$rootfs")" == "$(field rootfs_result_sha256 "$ready")" ]] ||
  die 'signed source FW/DTB/SD/Linux/rootfs bytes changed'
[[ "$(field old_checkpoint_compatibility "${candidate_lists[0]}")" == FORBIDDEN ]] ||
  die 'original polling firmware compatibility check absent'

out="$ROOT/build/product-sd1-migrated-autorelay"
tag="${target_m}m"
mkdir -p "$out/logs"
[[ ! -e "$out/$tag" ]] || die "refusing to overwrite sealed $tag milestone"
stage="$(mktemp -d "$out/.stage-$tag.XXXXXXXX")"
started="$(date -u +%s)"
log="$out/logs/advance-$tag.log"
timeout --signal=TERM --kill-after=12s 20m \
  "$sim" advance "$fw" "$dtb" "$sd" "$source_dir/persistent" \
    "$MODEL_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" \
    "$(field rootfs_result_sha256 "$ready")" \
    "$start" 20000000 "$stage/persistent" > "$log" 2>&1 || {
      tail -70 "$log" >&2
      die "authentic Product RTL guest advance to $tag failed; source preserved"
    }
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$start new_cycles=$target delta=20000000 " "$log" ||
  die 'real RTL advancement marker missing'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  die 'full Host/DDR/RTL/SD checkpoint missing'

python3 - "$source_dir/persistent.pre.host" "$stage/persistent.pre.host" \
  "$SD_SHA" "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect as inspect_cpu,compare as compare_cpu
src,dst=map(Path,sys.argv[1:3]); media=sys.argv[3]; elapsed=int(sys.argv[4]); report=Path(sys.argv[5])
before,after=decode(src),decode(dst)
cb,ca=card_state_from_host(src),card_state_from_host(dst)
with src.open('rb') as fh: cpu_before=inspect_cpu(fh)
with dst.open('rb') as fh: cpu_after=inspect_cpu(fh)
cpu=compare_cpu(cpu_before,cpu_after)
if after['cycles']-before['cycles']!=20000000 or after['commits']<=before['commits']:
    raise SystemExit('AUTORELAY_REJECTED: incorrect 20M cycle step or no real CPU retirement')
if before['identity']!=after['identity'] or ca['sd_media_sha256']!=media or after['failure']:
    raise SystemExit('AUTORELAY_REJECTED: guest failure or signed identity drift')
if cpu['retired_delta']!=after['commits']-before['commits']:
    raise SystemExit('AUTORELAY_REJECTED: CPU observer retirement mismatch')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if ca[key]<cb[key]:
        raise SystemExit('AUTORELAY_REJECTED: SD activity counter regressed: '+key)
login_in_uart=bool(after['login'] and cpu_after['login_seen'])
result={
    'qualification':'MIGRATED_REAL_PRODUCT_RTL_20M_NOT_DEBIAN_PASS',
    'from_cycles':before['cycles'],'to_cycles':after['cycles'],
    'retired_delta':cpu['retired_delta'],'sim_wall_seconds':elapsed,
    'sd_command_delta':ca['sd_command_count']-cb['sd_command_count'],
    'sd_read_delta':ca['sd_reads']-cb['sd_reads'],
    'sd_write_delta':ca['sd_writes']-cb['sd_writes'],
    'ext4_mounted':after['ext4_root_mounted'],
    'native_init':after['native_init_started'],
    'login_seen_in_both_host_observers':login_in_uart,
    'login_interpretation':'UART_OBSERVATION_ONLY_NOT_SERVICE_OR_DEBIAN_PASS',
    'guest_failure':after['failure'],'cpu_observer':cpu,
}
report.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
print('PRODUCT_SD1_MIGRATED_AUTORELAY_PHASE: '+json.dumps(result,sort_keys=True))
PY
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$target"
  echo "source_cycles=$start"
  echo "model_sha256=$MODEL_SHA"
  echo "original_model_sha256=$OLD_SHA"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$(field rootfs_result_sha256 "$ready")"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$source_dir/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$source_dir/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$stage/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$stage/.migration-ready"
mv "$stage" "$out/$tag"
echo "PRODUCT_SD1_MIGRATED_AUTORELAY_STAGE_SAVED target=$target complete_host_rtl_ddr_sd=1 original_media=1 source_preserved=1 not_debian_pass=1"
