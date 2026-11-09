#!/usr/bin/env bash
# INERT H DRAFT: authenticated G8472M -> H8500M two-stage true RTL follow-on.
# Never use Host-v2 beyond its 8.1B cap or mutate the C source checkpoint.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
reject() { echo "PRODUCT_SD1_V3_G_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly MODEL_ROOT="$ROOT/build/product-sd1-model-migration-v3-8p5b-draft"
readonly MODEL="$MODEL_ROOT/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
readonly QUAL="$MODEL_ROOT/qualification-manifest.txt"
readonly G_SOURCE="$ROOT/build/product-sd1-v3-h-source/8472m"
readonly OUT="$ROOT/build/product-sd1-migrated-v3-8p5b-h-followon"
readonly V3_MODEL="${AETHERCORE_SD1_V3_REVIEWED_MODEL_SHA:-}"
readonly V2_MODEL=c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70
readonly V2_GENERATED_RTL_SHA=9d576f92977040a55ea1978b73ddf8ee6704b320a6ea9016df26ca36b1f51822
readonly V2_GENERATED_OBJECTS_SHA=b34f440a5dc40063b872ab4d30b0135efd184ba1088c278d5c2e7a269f5f4821
readonly C_8072_HOST_SHA=4a80a8ce142820af18e5729053443179a01dc2c2949456dc6a4397a7e8a751c3
readonly C_8092_HOST_SHA=e575046e572bfe55a8bff307780c61275923f92bcb6993dc2ec397729ec02e1e
readonly ORIGINAL_MODEL=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  printf '%s\n' 'PRODUCT_SD1_V3_H_PLAN_ONLY no_guest_cycles=1 source=qualified-v3-G-8472m stages=8492,8500'
  printf '%s\n' 'G 8472M official success/five checkpoint ZIPs, Host/RTL/card SHA, v3 qualification and signed media must be independently attested.'
  exit 0
fi
[[ $# == 3 && "$1" == --execute-reviewed-relay ]] ||
  reject 'usage: --plan | --execute-reviewed-relay ABS_SOURCE_STAGE_DIR TARGET_MILLIONS'
[[ "${AETHERCORE_SD1_V3_H_RELAY_EXECUTE:-}" == REVIEWED_G_8472M_SUCCESS_SHA_AUDITED_AND_V3_QUALIFIED_28M_PASS &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V4_MIGRATED_8P5B &&
   "$V3_MODEL" =~ ^[a-f0-9]{64}$ &&
   "$V3_MODEL" != "$V2_MODEL" &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  reject 'independent Host-v3 qualification and exact Product RTL timing profile required'
[[ "$2" == /* && -d "$2" && "$(realpath -e "$2")" == "$2" ]] ||
  reject 'noncanonical or absent source stage'
readonly SOURCE="$2"
[[ "$3" =~ ^(8492|8500)$ ]] || reject 'only H8492/H8500 milestones allowed'
readonly TARGET_M="$3"
case "$TARGET_M" in
  8492) SOURCE_M=8472; DELTA=20000000 ;;
  8500) SOURCE_M=8492; DELTA=8000000 ;;
esac
readonly SOURCE_M DELTA
readonly START=$((SOURCE_M*1000000))
readonly TARGET=$((TARGET_M*1000000))
[[ "$TARGET" -le 8500000000 && "$TARGET" -gt "$START" &&
   $((TARGET-START)) -eq DELTA ]] || reject 'step exceeds 8.5B Host-v3 ceiling or wrong delta'
if [[ "$SOURCE_M" == 8472 ]]; then
  [[ "$SOURCE" == "$G_SOURCE" ]] || reject 'H must start at exact G8472M checkpoint'
else
  [[ "$SOURCE" == "$OUT/${SOURCE_M}m" ]] || reject 'H must resume exact prior H8492M checkpoint'
fi
[[ ! -e "$OUT/${TARGET_M}m" && "$SOURCE" != "$OUT/${TARGET_M}m" ]] ||
  reject 'published checkpoint target exists; never overwrite'
[[ -s "$QUAL" && ! -L "$QUAL" && -x "$MODEL" && ! -L "$MODEL" &&
   "$(sha "$MODEL")" == "$V3_MODEL" &&
   "$(field candidate_model_sha256 "$QUAL")" == "$V3_MODEL" &&
   "$(field previous_model_sha256 "$QUAL")" == "$V2_MODEL" &&
   "$(field old_8072_host_sha256 "$QUAL")" == "$C_8072_HOST_SHA" &&
   "$(field old_8092_host_sha256 "$QUAL")" == "$C_8092_HOST_SHA" &&
   "$(field generated_rtl_sha256 "$QUAL")" == "$V2_GENERATED_RTL_SHA" &&
   "$(field generated_rtl_objects_sha256 "$QUAL")" == "$V2_GENERATED_OBJECTS_SHA" ]] ||
  reject 'reviewed Host-v3 qualification/model unavailable or changed'
for clause in \
  'PRODUCT_SD1_MODEL_MIGRATION_V3: status=QUALIFIED_HOST_ONLY_8072_TO_8092_20M_DIFF_NOT_8P1B_CROSS' \
  replay_cycles=20000000 full_rtl_identical=1 full_host_except_model_sha_identical=1 \
  cross_8p1b_executed=0 debian_login_claim=0; do
  grep -Fxq "$clause" "$QUAL" || reject "model qualification clause absent: $clause"
done

ready="$SOURCE/.migration-ready"
[[ -s "$ready" && ! -L "$ready" &&
   "$(field schema "$ready")" == AETSDCP1 &&
   "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
   "$(field boot_cycles "$ready")" == "$START" &&
   "$(field model_sha256 "$ready")" == "$V3_MODEL" &&
   "$(field previous_model_sha256 "$ready")" == "$V2_MODEL" &&
   "$(field original_model_sha256 "$ready")" == "$ORIGINAL_MODEL" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" &&
   "$(field debian_login_claim "$ready")" == 0 ]] ||
  reject 'source Host/RTL/media/clock identity seal mismatch'
if [[ "$SOURCE_M" == 8472 ]]; then
  [[ "$(field source_cycles "$ready")" == 8452000000 &&
     "$(sha "$SOURCE/persistent.pre.host")" == "${AETHERCORE_SD1_V3_H_8472_HOST_SHA:-}" &&
     "$(sha "$SOURCE/persistent.pre.rtl")" == "${AETHERCORE_SD1_V3_H_8472_RTL_SHA:-}" &&
     "$(sha "$SOURCE/card-progress.json")" == "${AETHERCORE_SD1_V3_H_8472_CARD_SHA:-}" ]] ||
    reject 'first H stage signed G8472 SHA pins missing or wrong'
else
  [[ "$(field source_cycles "$ready")" == 8472000000 ]] ||
    reject 'H8492 source time discontinuity'
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
  "$V3_MODEL" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$START" "$DELTA" "$stage/persistent" > "$log" 2>&1 ||
  reject "genuine Product RTL ${DELTA} failed; inspect $log"
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$START new_cycles=$TARGET delta=$DELTA " "$log" ||
  reject 'real RTL stage marker missing'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  reject 'full Host and RTL savable state absent'

python3 - "$SOURCE/persistent.pre.host" "$stage/persistent.pre.host" \
  "$SD_SHA" "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" "$DELTA" <<'PY'
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
            raise SystemExit('H_RELAY_REJECTED: Host context schema drift')
        for _ in range(6): read_string(fh,128)
        read_string(fh,128);read_string(fh,4096);read_u64(fh)
        return read_u64(fh)
with before.open('rb') as fh: cpu_before=inspect(fh)
with after.open('rb') as fh: cpu_after=inspect(fh)
cpu=compare(cpu_before,cpu_after)
if (done['cycles']-source['cycles']!=int(sys.argv[6]) or
    context_time(before)!=source['cycles'] or context_time(after)!=done['cycles'] or
    done['commits']<=source['commits'] or
    cpu['retired_delta']!=done['commits']-source['commits']):
    raise SystemExit('H_RELAY_REJECTED: 20M cycle/context/CPU retirement mismatch')
if (done['identity']!=source['identity'] or done['failure'] or
    card_after['sd_media_sha256']!=sys.argv[3]):
    raise SystemExit('H_RELAY_REJECTED: signed source identity or guest failed')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if card_after[key]<card_before[key]:
        raise SystemExit('H_RELAY_REJECTED: SD counters regressed')
Path(sys.argv[5]).write_text(json.dumps({
    'qualification':'MIGRATED_V3_H_REAL_PRODUCT_RTL_NOT_DEBIAN_PASS',
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
  echo "model_sha256=$V3_MODEL"
  echo "previous_model_sha256=$V2_MODEL"
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
echo "PRODUCT_SD1_V3_H_REAL_RTL_STAGE_SAVED source=$START target=$TARGET full_host_rtl_sd=1 no_debian_login_claim=1"
