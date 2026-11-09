#!/usr/bin/env bash
# INERT. Reviewed v4 Product RTL post-8.5B continuation, five real 20M stages: 8512M->8612M.
# First input must be an externally sealed B 8512M checkpoint; never modify B, F, G or prior artifacts.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
reject() { echo "PRODUCT_SD1_V4_POSTCROSS_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly MODEL_ROOT="$ROOT/build/product-sd1-model-migration-v4-10b-qualified"
readonly MODEL="$MODEL_ROOT/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
readonly QUAL="$MODEL_ROOT/qualification-manifest.txt"
readonly C_FIRST="$ROOT/build/product-sd1-v4-c-source/8512m"
readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-c"
readonly V4_MODEL="${AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA:-}"
readonly EXPECTED_V4_MODEL=ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a
readonly V3_MODEL=04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6
readonly V2_MODEL=c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70
readonly ORIGINAL_MODEL=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly AUTH_E8272_HOST=74ab6999381fd71963c55e4ceff9ab233fa1428481e4670eb5d29500f4d42983
readonly AUTH_F8292_HOST=a7adbadb114bd3d183d8cd2059d5f28bf46017fe4be6befad340eb2c1495aed6
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  printf '%s\n' 'PRODUCT_SD1_V4_POSTCROSS_PLAN_ONLY no_guest_cycles=1 stages=8532,8552,8572,8592,8612; input=B-8512M'
  printf '%s\n' 'All five stages remain prohibited until the full B 8512M real RTL upload and exact Host/RTL/card SHA are independently reviewed.'
  exit 0
fi
[[ $# == 3 && "$1" == --execute-reviewed-postcross-20m ]] ||
  reject 'usage: --plan | --execute-reviewed-postcross-20m ABS_STAGE_DIR TARGET_M'
readonly MODE="$1"
[[ "${AETHERCORE_SD1_V4_CATCHUP_EXECUTE:-}" == REVIEWED_V4_8292M_20M_EXACT_DIFF_AND_SOURCE_SHA_AUDITED &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B &&
   "$V4_MODEL" == "$EXPECTED_V4_MODEL" && "$V4_MODEL" != "$V3_MODEL" &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  reject 'unreviewed v4 model or timing configuration'
[[ "$2" == /* && -d "$2" && "$(realpath -e "$2")" == "$2" ]] ||
  reject 'absent/noncanonical stage directory'
readonly SOURCE="$2"
readonly TARGET_M="$3"
[[ "$TARGET_M" =~ ^(8532|8552|8572|8592|8612)$ ]] ||
  reject 'only five exact 20M post-8512 real RTL stages are reviewed'
readonly SOURCE_M=$((TARGET_M-20))
readonly START=$((SOURCE_M*1000000))
readonly TARGET=$((TARGET_M*1000000))
[[ "$START" -ge 8512000000 && "$TARGET" -le 10000000000 &&
   $((TARGET-START)) -eq 20000000 && ! -e "$OUT/${TARGET_M}m" ]] ||
  reject 'unsupported postcross time segment or existing destination'
if [[ "$SOURCE_M" == 8512 ]]; then
  [[ "$SOURCE" == "$C_FIRST" ]] || reject 'C must start from full authenticated B-8512M artifact'
else
  [[ "$SOURCE" == "$OUT/${SOURCE_M}m" ]] || reject 'must follow immediately prior true RTL C checkpoint'
fi
if [[ "$SOURCE_M" == 8512 ]]; then
  for expected in "${AETHERCORE_SD1_V4_C_8512_HOST_SHA:-}" \
                  "${AETHERCORE_SD1_V4_C_8512_RTL_SHA:-}" \
                  "${AETHERCORE_SD1_V4_C_8512_CARD_SHA:-}"; do
    [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || reject 'unknown B-8512M source SHA'
  done
  [[ "$(sha "$SOURCE/persistent.pre.host")" == "$AETHERCORE_SD1_V4_C_8512_HOST_SHA" &&
     "$(sha "$SOURCE/persistent.pre.rtl")" == "$AETHERCORE_SD1_V4_C_8512_RTL_SHA" &&
     "$(sha "$SOURCE/card-progress.json")" == "$AETHERCORE_SD1_V4_C_8512_CARD_SHA" ]] ||
    reject 'C cannot resume without full independently audited B 8512M Host/RTL/card SHA'
fi
[[ -x "$MODEL" && ! -L "$MODEL" && -s "$QUAL" && ! -L "$QUAL" &&
   "$(sha "$MODEL")" == "$V4_MODEL" &&
   "$(field candidate_model_sha256 "$QUAL")" == "$V4_MODEL" &&
   "$(field previous_model_sha256 "$QUAL")" == "$V3_MODEL" &&
   "$(field old_8272_host_sha256 "$QUAL")" == "$AUTH_E8272_HOST" &&
   "$(field old_8292_host_sha256 "$QUAL")" == "$AUTH_F8292_HOST" &&
   "$(field replay_cycles "$QUAL")" == 20000000 &&
   "$(field full_rtl_identical "$QUAL")" == 1 &&
   "$(field full_host_except_model_sha_identical "$QUAL")" == 1 &&
   "$(field cross_8p5b_executed "$QUAL")" == 0 &&
   "$(field debian_login_claim "$QUAL")" == 0 ]] ||
  reject 'v4 model/qualified Host-v3-v4 differential identity absent'
grep -Fxq 'PRODUCT_SD1_MODEL_MIGRATION_V4: status=QUALIFIED_HOST_ONLY_8272_TO_8292_20M_DIFF_NOT_8P5B_CROSS' "$QUAL" ||
  reject 'historical v4 20M differential marker absent'
ready="$SOURCE/.migration-ready"
[[ -s "$ready" && ! -L "$ready" &&
   "$(field schema "$ready")" == AETSDCP1 &&
   "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
   "$(field boot_cycles "$ready")" == "$START" &&
   "$(field source_cycles "$ready")" == $((START-20000000)) &&
   "$(field model_sha256 "$ready")" == "$V4_MODEL" &&
   "$(field previous_model_sha256 "$ready")" == "$V3_MODEL" &&
   "$(field original_model_sha256 "$ready")" == "$ORIGINAL_MODEL" &&
   "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
   "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
   "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
   "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
   "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" &&
   "$(field debian_login_claim "$ready")" == 0 ]] ||
  reject 'source Host/RTL/media/boot-time lineage failure'
for kind in host rtl; do
  file="$SOURCE/persistent.pre.$kind"
  [[ -s "$file" && ! -L "$file" &&
     "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
    reject "missing/invalid $kind snapshot"
done
[[ -s "$SOURCE/card-progress.json" && ! -L "$SOURCE/card-progress.json" &&
   "$(field card_progress_sha256 "$ready")" == "$(sha "$SOURCE/card-progress.json")" ]] ||
  reject 'missing/invalid SD progress record'
mapfile -d '' manifests < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ ${#manifests[@]} == 1 ]] || reject 'polling media manifest not unique'
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
  reject 'official signed firmware/DTB/SD/Linux/rootfs drift'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  reject 'signed Debian seed verification rejected'

mkdir -p "$OUT/logs"
stage="$(mktemp -d "$OUT/.stage-${TARGET_M}m.XXXXXXXX")"
log="$OUT/logs/advance-${TARGET_M}m.log"
started="$(date -u +%s)"
timeout --signal=TERM --kill-after=12s 20m \
  "$MODEL" advance "$fw" "$dtb" "$sd" "$SOURCE/persistent" \
  "$V4_MODEL" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$START" 20000000 "$stage/persistent" > "$log" 2>&1 ||
  reject 'genuine v4 Product RTL 20M stage failed'
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$START new_cycles=$TARGET delta=20000000 " "$log" ||
  reject 'genuine 20M RTL stage marker absent'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  reject 'full savable Host+RTL states absent'
python3 - "$SOURCE/persistent.pre.host" "$stage/persistent.pre.host" "$SD_SHA" \
  "$(( $(date -u +%s) - started ))" "$stage/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode,read_exact,read_u64,read_string,MAGIC,VERSION
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect,compare
before,after = map(Path,sys.argv[1:3])
source,done=decode(before),decode(after)
card_before,card_after=card_state_from_host(before),card_state_from_host(after)
def context_time(path):
    with path.open('rb') as fh:
        if read_exact(fh,8)!=MAGIC or read_u64(fh)!=VERSION:
            raise SystemExit('V4_POSTCROSS_REJECTED: Host context schema drift')
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
    raise SystemExit('V4_POSTCROSS_REJECTED: cycle/context/commit counter drift')
if done['identity']!=source['identity'] or done['failure'] or card_after['sd_media_sha256']!=sys.argv[3]:
    raise SystemExit('V4_POSTCROSS_REJECTED: signed identities or guest runtime failed')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if card_after[key]<card_before[key]:
        raise SystemExit('V4_POSTCROSS_REJECTED: SD counter regression')
Path(sys.argv[5]).write_text(json.dumps({
    'qualification':'MIGRATED_V4_REAL_PRODUCT_RTL_NOT_DEBIAN_PASS',
    'from_cycles':source['cycles'],'to_cycles':done['cycles'],
    'retired_delta':cpu['retired_delta'],'sim_wall_seconds':int(sys.argv[4]),
    'verilator_time_after':context_time(after),
    'sd_command_delta':card_after['sd_command_count']-card_before['sd_command_count'],
    'native_init':done['native_init_started'],'ext4_root':done['ext4_root_mounted'],
    'host_login':done['login'],'cpu_observer_login':cpu_after['login_seen'],
    'guest_uart_login_observed':bool(done['login'] and cpu_after['login_seen']),
    'guest_uart_service_verified':False,'service_login_gate_pass':False,
    'service_login_gate_interpretation':'NO_INDEPENDENT_SERVICE_PROOF_NOT_DEBIAN_PASS',
    'debian_login_claim':False,'cpu_observer':cpu,
},sort_keys=True,indent=2)+'\n')
PY
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TARGET"
  echo "source_cycles=$START"
  echo "model_sha256=$V4_MODEL"
  echo "previous_model_sha256=$V3_MODEL"
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
[[ ! -e "$OUT/${TARGET_M}m" ]] || reject 'target appeared while running; refuse overwrite'
mv -T -- "$stage" "$OUT/${TARGET_M}m"
echo "PRODUCT_SD1_V4_REAL_RTL_STAGE_SAVED source=$START target=$TARGET complete_host_rtl_sd=1 no_debian_login_claim=1"
