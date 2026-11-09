#!/usr/bin/env bash
# INERT until an explicitly reviewed workflow selects C, D, E, F, G, H, I, J, K, or L. One common
# executor preserves the same model/SD/RTL identity across 20M continuations.
# Never modify the imported source or any older checkpoint artifact.
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
readonly D_FIRST="$ROOT/build/product-sd1-v4-d-source/8612m"
readonly E_FIRST="$ROOT/build/product-sd1-v4-e-source/8712m"
readonly F_FIRST="$ROOT/build/product-sd1-v4-f-source/8812m"
readonly G_FIRST="$ROOT/build/product-sd1-v4-g-source/8912m"
readonly H_FIRST="$ROOT/build/product-sd1-v4-h-source/9012m"
readonly I_FIRST="$ROOT/build/product-sd1-v4-i-source/9112m"
readonly J_FIRST="$ROOT/build/product-sd1-v4-j-source/9212m"
readonly K_FIRST="$ROOT/build/product-sd1-v4-k-source/9312m"
readonly L_FIRST="$ROOT/build/product-sd1-v4-l-source/9412m"
readonly PROFILE="${AETHERCORE_SD1_V4_POSTCROSS_PROFILE:-C}"
case "$PROFILE" in
  C)
    readonly FIRST="$C_FIRST" FIRST_M=8512
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-c"
    readonly STAGES='8532,8552,8572,8592,8612' INPUT='B-8512M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_C_8512_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_C_8512_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_C_8512_CARD_SHA:-}"
    ;;
  D)
    readonly FIRST="$D_FIRST" FIRST_M=8612
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-d"
    readonly STAGES='8632,8652,8672,8692,8712' INPUT='C-8612M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_D_8612_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_D_8612_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_D_8612_CARD_SHA:-}"
    ;;
  E)
    readonly FIRST="$E_FIRST" FIRST_M=8712
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-e"
    readonly STAGES='8732,8752,8772,8792,8812' INPUT='D-8712M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_E_8712_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_E_8712_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_E_8712_CARD_SHA:-}"
    ;;
  F)
    readonly FIRST="$F_FIRST" FIRST_M=8812
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-f"
    readonly STAGES='8832,8852,8872,8892,8912' INPUT='E-8812M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_F_8812_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_F_8812_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_F_8812_CARD_SHA:-}"
    ;;
  G)
    readonly FIRST="$G_FIRST" FIRST_M=8912
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-g"
    readonly STAGES='8932,8952,8972,8992,9012' INPUT='F-8912M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_G_8912_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_G_8912_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_G_8912_CARD_SHA:-}"
    ;;
  H)
    readonly FIRST="$H_FIRST" FIRST_M=9012
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-h"
    readonly STAGES='9032,9052,9072,9092,9112' INPUT='G-9012M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_H_9012_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_H_9012_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_H_9012_CARD_SHA:-}"
    ;;
  I)
    readonly FIRST="$I_FIRST" FIRST_M=9112
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-i"
    readonly STAGES='9132,9152,9172,9192,9212' INPUT='H-9112M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_I_9112_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_I_9112_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_I_9112_CARD_SHA:-}"
    ;;
  J)
    readonly FIRST="$J_FIRST" FIRST_M=9212
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-j"
    readonly STAGES='9232,9252,9272,9292,9312' INPUT='I-9212M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_J_9212_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_J_9212_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_J_9212_CARD_SHA:-}"
    ;;
  K)
    readonly FIRST="$K_FIRST" FIRST_M=9312
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-k"
    readonly STAGES='9332,9352,9372,9392,9412' INPUT='J-9312M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_K_9312_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_K_9312_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_K_9312_CARD_SHA:-}"
    ;;
  L)
    readonly FIRST="$L_FIRST" FIRST_M=9412
    readonly OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-l"
    readonly STAGES='9432,9452,9472,9492,9512' INPUT='K-9412M'
    readonly SOURCE_HOST_SHA="${AETHERCORE_SD1_V4_L_9412_HOST_SHA:-}"
    readonly SOURCE_RTL_SHA="${AETHERCORE_SD1_V4_L_9412_RTL_SHA:-}"
    readonly SOURCE_CARD_SHA="${AETHERCORE_SD1_V4_L_9412_CARD_SHA:-}"
    ;;
  *) reject 'only reviewed C, D, E, F, G, H, I, J, K, or L postcross profile is permitted' ;;
esac
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
  printf 'PRODUCT_SD1_V4_POSTCROSS_PLAN_ONLY no_guest_cycles=1 stages=%s; input=%s\n' "$STAGES" "$INPUT"
  printf 'All five stages remain prohibited until the full %s real RTL upload and exact Host/RTL/card SHA are independently reviewed.\n' "$INPUT"
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
case "$PROFILE" in
  C) [[ "$TARGET_M" =~ ^(8532|8552|8572|8592|8612)$ ]] ||
       reject 'only five exact 20M C stages are reviewed' ;;
  D) [[ "$TARGET_M" =~ ^(8632|8652|8672|8692|8712)$ ]] ||
       reject 'only five exact 20M D stages are reviewed' ;;
  E) [[ "$TARGET_M" =~ ^(8732|8752|8772|8792|8812)$ ]] ||
       reject 'only five exact 20M E stages are reviewed' ;;
  F) [[ "$TARGET_M" =~ ^(8832|8852|8872|8892|8912)$ ]] ||
       reject 'only five exact 20M F stages are reviewed' ;;
  G) [[ "$TARGET_M" =~ ^(8932|8952|8972|8992|9012)$ ]] ||
       reject 'only five exact 20M G stages are reviewed' ;;
  H) [[ "$TARGET_M" =~ ^(9032|9052|9072|9092|9112)$ ]] ||
       reject 'only five exact 20M H stages are reviewed' ;;
  I) [[ "$TARGET_M" =~ ^(9132|9152|9172|9192|9212)$ ]] ||
       reject 'only five exact 20M I stages are reviewed' ;;
  J) [[ "$TARGET_M" =~ ^(9232|9252|9272|9292|9312)$ ]] ||
       reject 'only five exact 20M J stages are reviewed' ;;
  K) [[ "$TARGET_M" =~ ^(9332|9352|9372|9392|9412)$ ]] ||
       reject 'only five exact 20M K stages are reviewed' ;;
  L) [[ "$TARGET_M" =~ ^(9432|9452|9472|9492|9512)$ ]] ||
       reject 'only five exact 20M L stages are reviewed' ;;
esac
readonly SOURCE_M=$((TARGET_M-20))
readonly START=$((SOURCE_M*1000000))
readonly TARGET=$((TARGET_M*1000000))
[[ "$START" -ge $((FIRST_M*1000000)) && "$TARGET" -le 10000000000 &&
   $((TARGET-START)) -eq 20000000 && ! -e "$OUT/${TARGET_M}m" ]] ||
  reject 'unsupported postcross time segment or existing destination'
if [[ "$SOURCE_M" -eq "$FIRST_M" ]]; then
  [[ "$SOURCE" == "$FIRST" ]] || reject "${PROFILE} must start from full authenticated ${INPUT} artifact"
else
  [[ "$SOURCE" == "$OUT/${SOURCE_M}m" ]] || reject 'must follow immediately prior true RTL checkpoint'
fi
if [[ "$SOURCE_M" -eq "$FIRST_M" ]]; then
  for expected in "$SOURCE_HOST_SHA" "$SOURCE_RTL_SHA" "$SOURCE_CARD_SHA"; do
    [[ "$expected" =~ ^[a-f0-9]{64}$ ]] || reject "unknown ${INPUT} source SHA"
  done
  [[ "$(sha "$SOURCE/persistent.pre.host")" == "$SOURCE_HOST_SHA" &&
     "$(sha "$SOURCE/persistent.pre.rtl")" == "$SOURCE_RTL_SHA" &&
     "$(sha "$SOURCE/card-progress.json")" == "$SOURCE_CARD_SHA" ]] ||
    reject "${PROFILE} cannot resume without full independently audited ${INPUT} Host/RTL/card SHA"
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
