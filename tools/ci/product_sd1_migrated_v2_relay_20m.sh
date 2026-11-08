#!/usr/bin/env bash
# ISOLATED DRAFT ONLY. New Host 7912M -> 8012M five bounded 20M stages.
# This script is NOT called by any enabled workflow; --plan is zero-write.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
reject() { echo "PRODUCT_SD1_MIGRATED_V2_RELAY_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly QUAL_DIR="$ROOT/build/product-sd1-model-migration-v2-draft"
readonly MODEL="$QUAL_DIR/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
readonly QUAL="$QUAL_DIR/qualification-manifest.txt"
readonly OUT="$ROOT/build/product-sd1-migrated-v2-8b-relay"
readonly OLD_MODEL=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461
readonly ORIGINAL_MODEL=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  echo 'PRODUCT_SD1_MIGRATED_V2_RELAY_PLAN_ONLY no_guest_cycles=1 stages=7932,7952,7972,7992,8012 source=qualified-7912m'
  echo 'Requires separate reviewer-approved Host-only 7892M->7912M real Product RTL 20M byte-differential and exact reviewed v2 executable SHA.'
  echo 'Old 8000M production workflow/driver, original checkpoint and current official run are not touched.'
  exit 0
fi
[[ $# == 3 && "$1" == --execute-reviewed-20m ]] ||
  reject 'usage: --plan | --execute-reviewed-20m SOURCE_STAGE_DIR TARGET_MILLIONS'
[[ "${AETHERCORE_SD1_V2_RELAY_EXECUTE:-}" == REVIEWED_7892_TO_7912_FULL_20M_DIFF &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  reject 'requires separate reviewed v2 relay authorization and exact RTL timing profile'
[[ "$2" == /* && -d "$2" && "$(realpath -e "$2")" == "$2" ]] ||
  reject 'source must be an existing canonical absolute stage path'
readonly SOURCE_DIR="$2"
[[ "$3" =~ ^(7932|7952|7972|7992|8012)$ ]] || reject 'unsupported milestone'
readonly TARGET_M="$3"
readonly SOURCE_M=$((TARGET_M-20))
readonly START=$((SOURCE_M*1000000))
readonly TARGET=$((TARGET_M*1000000))
[[ "$TARGET" -le 8100000000 && "$TARGET-$START" -eq 20000000 ]] ||
  reject 'step must be exactly 20M with a bounded 8.1B maximum'
if [[ "$SOURCE_M" == 7912 ]]; then
  [[ "$SOURCE_DIR" == "$QUAL_DIR/qualified/7912m" ]] ||
    reject 'first stage must use the independently differential-qualified 7912M'
else
  [[ "$SOURCE_DIR" == "$OUT/${SOURCE_M}m" ]] ||
    reject 'every further stage must restore exactly previous v2 20M state'
fi
[[ "$SOURCE_DIR" != "$OUT/${TARGET_M}m" && ! -e "$OUT/${TARGET_M}m" ]] ||
  reject 'cannot overwrite any published Product RTL stage'

[[ -s "$QUAL" && ! -L "$QUAL" && -x "$MODEL" && ! -L "$MODEL" ]] ||
  reject 'reviewed Host-only qualification and executable absent'
grep -Fxq 'PRODUCT_SD1_MODEL_MIGRATION_V2: status=QUALIFIED_HOST_ONLY_7892_TO_7912_20M_DIFF_NOT_8B_CROSS' "$QUAL" ||
  reject '7892M->7912M real RTL differential did not qualify'
for expected in 'replay_cycles=20000000' 'full_rtl_identical=1' \
                'full_host_except_model_sha_identical=1' 'cross_8b_executed=0' \
                'debian_login_claim=0'; do
  grep -Fxq "$expected" "$QUAL" || reject "missing qualification clause: $expected"
done
[[ "$(field previous_model_sha256 "$QUAL")" == "$OLD_MODEL" ]] ||
  reject 'source model lineage changed'
readonly V2_SHA="$(field candidate_model_sha256 "$QUAL")"
[[ "$V2_SHA" =~ ^[a-f0-9]{64}$ && "$V2_SHA" != "$OLD_MODEL" &&
   "$(sha "$MODEL")" == "$V2_SHA" &&
   "${AETHERCORE_SD1_V2_REVIEWED_MODEL_SHA:-}" == "$V2_SHA" ]] ||
  reject 'candidate Host executable SHA does not match separately reviewed pin'

ready="$SOURCE_DIR/.migration-ready"
[[ -s "$ready" && ! -L "$ready" ]] || reject 'sealed source missing'
[[ "$(field schema "$ready")" == AETSDCP1 &&
   "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
   "$(field boot_cycles "$ready")" == "$START" &&
   "$(field model_sha256 "$ready")" == "$V2_SHA" &&
   "$(field original_model_sha256 "$ready")" == "$ORIGINAL_MODEL" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" ]] ||
  reject 'source complete signed media/model/clock seal changed'
if [[ "$SOURCE_M" == 7912 ]]; then
  [[ "$(field snapshot_host_sha256 "$ready")" == "$(field candidate_7912_host_sha256 "$QUAL")" &&
     "$(field snapshot_rtl_sha256 "$ready")" == "$(field candidate_7912_rtl_sha256 "$QUAL")" &&
     "$(field previous_model_sha256 "$ready")" == "$OLD_MODEL" ]] ||
    reject 'first 7912M restored Host/RTL differs from exact qualified replay'
fi
for kind in host rtl; do
  file="$SOURCE_DIR/persistent.pre.$kind"
  [[ -s "$file" && ! -L "$file" &&
     "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
    reject "source $kind bytes differ from authentic stage seal"
done
[[ -s "$SOURCE_DIR/card-progress.json" && ! -L "$SOURCE_DIR/card-progress.json" &&
   "$(field card_progress_sha256 "$ready")" == "$(sha "$SOURCE_DIR/card-progress.json")" ]] ||
  reject 'source card evidence changed'

mapfile -d '' manifests < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ "${#manifests[@]}" == 1 ]] || reject 'official signed polling media manifest missing/ambiguous'
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
  reject 'signed source media or compatibility drift'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  reject 'official signed source verification failed'

# The first write occurs only after all sealed state, model, qualification,
# media and exact-stage checks. The source is never modified.
mkdir -p "$OUT/logs"
stage="$(mktemp -d "$OUT/.stage-${TARGET_M}m.XXXXXXXX")"
log="$OUT/logs/advance-${TARGET_M}m.log"
started="$(date -u +%s)"
timeout --signal=TERM --kill-after=12s 20m \
  "$MODEL" advance "$fw" "$dtb" "$sd" "$SOURCE_DIR/persistent" \
  "$V2_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$START" 20000000 "$stage/persistent" > "$log" 2>&1 ||
  reject "authentic v2 Product RTL 20M advancement failed, inspect $log"
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$START new_cycles=$TARGET delta=20000000 " "$log" ||
  reject 'real Product RTL 20M stage marker missing'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  reject 'complete post-stage Host/RTL snapshot missing'

python3 - "$SOURCE_DIR/persistent.pre.host" "$stage/persistent.pre.host" \
  "$SD_SHA" "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode,read_exact,read_u64,read_string,MAGIC,VERSION
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect as inspect_cpu,compare as compare_cpu
src,dst=map(Path,sys.argv[1:3]); media=sys.argv[3]; elapsed=int(sys.argv[4]); report=Path(sys.argv[5])
before,after=decode(src),decode(dst)
cb,ca=card_state_from_host(src),card_state_from_host(dst)
def context_time(path):
    with path.open('rb') as stream:
        if read_exact(stream,8)!=MAGIC or read_u64(stream)!=VERSION:
            raise SystemExit('V2_RELAY_REJECTED: Host context version drift')
        for _ in range(6): read_string(stream,128)
        read_string(stream,128); read_string(stream,4096); read_u64(stream)
        return read_u64(stream)
if context_time(src)!=before['cycles'] or context_time(dst)!=after['cycles']:
    raise SystemExit('V2_RELAY_REJECTED: 64-bit Verilator/Observer cycle mismatch')
with src.open('rb') as fh: cpu_before=inspect_cpu(fh)
with dst.open('rb') as fh: cpu_after=inspect_cpu(fh)
cpu=compare_cpu(cpu_before,cpu_after)
if after['cycles']-before['cycles']!=20000000 or after['commits']<=before['commits']:
    raise SystemExit('V2_RELAY_REJECTED: incorrect 20M cycle step or no retirement')
if before['identity']!=after['identity'] or ca['sd_media_sha256']!=media or after['failure']:
    raise SystemExit('V2_RELAY_REJECTED: guest failed or exact signed source identity drift')
if cpu['retired_delta']!=after['commits']-before['commits']:
    raise SystemExit('V2_RELAY_REJECTED: CPU observer retirement mismatch')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if ca[key]<cb[key]:
        raise SystemExit('V2_RELAY_REJECTED: SD activity counter regressed: '+key)
report.write_text(json.dumps({
    'qualification':'MIGRATED_V2_REAL_PRODUCT_RTL_20M_NOT_DEBIAN_PASS',
    'from_cycles':before['cycles'], 'to_cycles':after['cycles'],
    'retired_delta':cpu['retired_delta'], 'sim_wall_seconds':elapsed,
    'verilator_context_time_before':context_time(src),
    'verilator_context_time_after':context_time(dst),
    'sd_command_delta':ca['sd_command_count']-cb['sd_command_count'],
    'sd_read_delta':ca['sd_reads']-cb['sd_reads'],
    'sd_write_delta':ca['sd_writes']-cb['sd_writes'],
    'ext4_mounted':after['ext4_root_mounted'],
    'native_init':after['native_init_started'],
    'login_seen_in_both_host_observers':bool(after['login'] and cpu_after['login_seen']),
    'login_interpretation':'UART_OBSERVATION_ONLY_NOT_SERVICE_OR_DEBIAN_PASS',
    'guest_failure':after['failure'], 'cpu_observer':cpu,
},indent=2,sort_keys=True)+'\n')
PY
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TARGET"
  echo "source_cycles=$START"
  echo "model_sha256=$V2_SHA"
  echo "previous_model_sha256=$OLD_MODEL"
  echo "original_model_sha256=$ORIGINAL_MODEL"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$ROOTFS_SHA"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$SOURCE_DIR/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$SOURCE_DIR/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$stage/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$stage/.migration-ready"
[[ ! -e "$OUT/${TARGET_M}m" ]] || reject 'target appeared during stage; no overwrite allowed'
mv -- "$stage" "$OUT/${TARGET_M}m"
echo "PRODUCT_SD1_MIGRATED_V2_REAL_RTL_STAGE_SAVED target=$TARGET full_host_rtl_ddr_sd=1 not_debian_pass=1 source_preserved=1"
