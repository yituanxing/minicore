#!/usr/bin/env bash
# REVIEW DRAFT: independently qualified 8012M -> 8092M four-stage follow-on.
# Never use the old 8B runner or mutate the B source checkpoint.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
reject() { echo "PRODUCT_SD1_V2_C_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly MODEL_ROOT="$ROOT/build/product-sd1-model-migration-v2-draft"
readonly MODEL="$MODEL_ROOT/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
readonly QUAL="$MODEL_ROOT/qualification-manifest.txt"
readonly B_SOURCE="$ROOT/build/product-sd1-migrated-v2-c-source/8012m"
readonly OUT="$ROOT/build/product-sd1-migrated-v2-8p1b-followon"
readonly V2_MODEL=c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70
readonly OLD_MODEL=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461
readonly ORIGINAL_MODEL=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  printf '%s\n' 'PRODUCT_SD1_V2_C_PLAN_ONLY no_guest_cycles=1 source=sealed-B-8012m stages=8032,8052,8072,8092'
  printf '%s\n' 'B terminal 8012M artifact ID, ZIP, Host, RTL, card SHA and full five-stage PASS must be independently attested.'
  exit 0
fi
[[ $# == 3 && "$1" == --execute-reviewed-20m ]] ||
  reject 'usage: --plan | --execute-reviewed-20m ABS_SOURCE_STAGE_DIR TARGET_MILLIONS'
[[ "${AETHERCORE_SD1_V2_C_RELAY_EXECUTE:-}" == REVIEWED_B_8012M_REAL_RTL_FIVE_STAGE_PASS &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B &&
   "${AETHERCORE_SD1_V2_REVIEWED_MODEL_SHA:-}" == "$V2_MODEL" &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  reject 'separate B terminal approval and exact Product RTL timing profile required'
[[ "$2" == /* && -d "$2" && "$(realpath -e "$2")" == "$2" ]] ||
  reject 'noncanonical or absent source stage'
readonly SOURCE="$2"
[[ "$3" =~ ^(8032|8052|8072|8092)$ ]] || reject 'only four exact follow-on milestones allowed'
readonly TARGET_M="$3"
readonly SOURCE_M=$((TARGET_M-20))
readonly START=$((SOURCE_M*1000000))
readonly TARGET=$((TARGET_M*1000000))
[[ "$TARGET" -le 8100000000 && "$TARGET" -gt "$START" &&
   $((TARGET-START)) -eq 20000000 ]] || reject 'step exceeds 8.1B Host ceiling or not 20M'
if [[ "$SOURCE_M" == 8012 ]]; then
  [[ "$SOURCE" == "$B_SOURCE" ]] || reject 'C must start at exact authenticated B 8012M'
else
  [[ "$SOURCE" == "$OUT/${SOURCE_M}m" ]] || reject 'C must resume exact prior 20M stage'
fi
[[ ! -e "$OUT/${TARGET_M}m" && "$SOURCE" != "$OUT/${TARGET_M}m" ]] ||
  reject 'published checkpoint target exists; never overwrite'
[[ -s "$QUAL" && ! -L "$QUAL" && -x "$MODEL" && ! -L "$MODEL" &&
   "$(sha "$MODEL")" == "$V2_MODEL" &&
   "$(field candidate_model_sha256 "$QUAL")" == "$V2_MODEL" &&
   "$(field previous_model_sha256 "$QUAL")" == "$OLD_MODEL" ]] ||
  reject 'original Host-only A qualification/model unavailable or changed'
for clause in \
  'PRODUCT_SD1_MODEL_MIGRATION_V2: status=QUALIFIED_HOST_ONLY_7892_TO_7912_20M_DIFF_NOT_8B_CROSS' \
  replay_cycles=20000000 full_rtl_identical=1 full_host_except_model_sha_identical=1 \
  cross_8b_executed=0 debian_login_claim=0; do
  grep -Fxq "$clause" "$QUAL" || reject "model qualification clause absent: $clause"
done

ready="$SOURCE/.migration-ready"
[[ -s "$ready" && ! -L "$ready" &&
   "$(field schema "$ready")" == AETSDCP1 &&
   "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
   "$(field boot_cycles "$ready")" == "$START" &&
   "$(field model_sha256 "$ready")" == "$V2_MODEL" &&
   "$(field original_model_sha256 "$ready")" == "$ORIGINAL_MODEL" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" &&
   "$(field debian_login_claim "$ready")" == 0 ]] ||
  reject 'source Host/RTL/media/clock identity seal mismatch'
if [[ "$SOURCE_M" == 8012 ]]; then
  [[ "$(field source_cycles "$ready")" == 7992000000 &&
     "$(sha "$SOURCE/persistent.pre.host")" == "${AETHERCORE_SD1_V2_C_8012_HOST_SHA:-}" &&
     "$(sha "$SOURCE/persistent.pre.rtl")" == "${AETHERCORE_SD1_V2_C_8012_RTL_SHA:-}" &&
     "$(sha "$SOURCE/card-progress.json")" == "${AETHERCORE_SD1_V2_C_8012_CARD_SHA:-}" ]] ||
    reject 'first stage B terminal 8012M independent SHA pins missing or wrong'
else
  [[ "$(field source_cycles "$ready")" == $((START-20000000)) ]] ||
    reject 'prior C milestone source time discontinuity'
fi
for kind in host rtl; do
  file="$SOURCE/persistent.pre.$kind"
  [[ -s "$file" && ! -L "$file" &&
     "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
    reject "source snapshot $kind digest wrong"
done
[[ -s "$SOURCE/card-progress.json" && ! -L "$SOURCE/card-progress.json" &&
   "$(field card_progress_sha256 "$ready")" == "$(sha "$SOURCE/card-progress.json")" ]] ||
  reject 'source SD progress digest wrong'

mapfile -d '' manifests < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ ${#manifests[@]} == 1 ]] || reject 'original signed polling media ambiguous'
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
  reject 'signed media SHA or compatibility drift'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  reject 'original signed Debian media validation failed'

mkdir -p "$OUT/logs"
stage="$(mktemp -d "$OUT/.stage-${TARGET_M}m.XXXXXXXX")"
log="$OUT/logs/advance-${TARGET_M}m.log"
started="$(date -u +%s)"
timeout --signal=TERM --kill-after=12s 20m \
  "$MODEL" advance "$fw" "$dtb" "$sd" "$SOURCE/persistent" \
  "$V2_MODEL" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$START" 20000000 "$stage/persistent" > "$log" 2>&1 ||
  reject "genuine Product RTL 20M failed; inspect $log"
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$START new_cycles=$TARGET delta=20000000 " "$log" ||
  reject 'real RTL stage marker missing'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  reject 'full Host and RTL savable state absent'

python3 - "$SOURCE/persistent.pre.host" "$stage/persistent.pre.host" \
  "$SD_SHA" "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode,read_exact,read_u64,read_string,MAGIC,VERSION
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect,compare
before,after = map(Path,sys.argv[1:3])
source,done = decode(before),decode(after)
card_before,card_after=card_state_from_host(before),card_state_from_host(after)
def context_time(path):
    with path.open('rb') as fh:
        if read_exact(fh,8)!=MAGIC or read_u64(fh)!=VERSION:
            raise SystemExit('C_RELAY_REJECTED: Host context schema drift')
        for _ in range(6): read_string(fh,128)
        read_string(fh,128);read_string(fh,4096);read_u64(fh)
        return read_u64(fh)
with before.open('rb') as fh: cpu_before=inspect(fh)
with after.open('rb') as fh: cpu_after=inspect(fh)
cpu=compare(cpu_before,cpu_after)
if (done['cycles']-source['cycles']!=20000000 or
    context_time(before)!=source['cycles'] or context_time(after)!=done['cycles'] or
    done['commits']<=source['commits'] or
    cpu['retired_delta']!=done['commits']-source['commits']):
    raise SystemExit('C_RELAY_REJECTED: 20M cycle/context/CPU retirement mismatch')
if (done['identity']!=source['identity'] or done['failure'] or
    card_after['sd_media_sha256']!=sys.argv[3]):
    raise SystemExit('C_RELAY_REJECTED: signed source identity or guest failed')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if card_after[key]<card_before[key]:
        raise SystemExit('C_RELAY_REJECTED: SD counters regressed')
Path(sys.argv[5]).write_text(json.dumps({
    'qualification':'MIGRATED_V2_C_REAL_PRODUCT_RTL_NOT_DEBIAN_PASS',
    'from_cycles':source['cycles'],'to_cycles':done['cycles'],
    'retired_delta':cpu['retired_delta'],'sim_wall_seconds':int(sys.argv[4]),
    'verilator_time_after':context_time(after),
    'sd_command_delta':card_after['sd_command_count']-card_before['sd_command_count'],
    'native_init':done['native_init_started'],'ext4_root':done['ext4_root_mounted'],
    'host_login':done['login'],'cpu_observer_login':cpu_after['login_seen'],
    'guest_uart_login_observed':bool(done['login'] and cpu_after['login_seen']),
    'guest_uart_service_verified':False,
    'service_login_gate_pass':False,
    'service_login_gate_interpretation':'NO_INDEPENDENT_SERVICE_PROOF_NOT_DEBIAN_PASS',
    'debian_login_claim':False,'cpu_observer':cpu,
},sort_keys=True,indent=2)+'\n')
PY
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TARGET"
  echo "source_cycles=$START"
  echo "model_sha256=$V2_MODEL"
  echo "previous_model_sha256=$OLD_MODEL"
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
[[ ! -e "$OUT/${TARGET_M}m" ]] || reject 'publication target appeared; abort rather than overwrite'
mv -T -- "$stage" "$OUT/${TARGET_M}m"
echo "PRODUCT_SD1_V2_C_REAL_RTL_STAGE_SAVED source=$START target=$TARGET full_host_rtl_sd=1 no_debian_login_claim=1"
