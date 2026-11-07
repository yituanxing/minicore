#!/usr/bin/env bash
set -euo pipefail

# Separate, fail-closed successor to model_migration_v1_qualify.sh.  This may
# only consume a *previously qualified* model+3992M artifact, authenticated in
# its label-only GitHub workflow.  It never regenerates RTL or cold boots.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
die() { echo "PRODUCT_SD1_MIGRATED_CROSS4B_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
[[ "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V2_MIGRATED &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  die 'explicit migrated RTL profile required'

OLD_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
CANDIDATE_SHA=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461
SOURCE_3992_HOST_SHA=92ba1072b516dd2a737353e8cddc1f073791546ae3e4fba864f13e5aa8c0f1f3
CANDIDATE_3992_HOST_SHA=3ccb0f1845288d476fa93b558bf37baec12b0394b96d3d48b44e0055adc0e512
CANDIDATE_3992_RTL_SHA=454596490c9af3beee940c0a0ff5bee0388c6879c1dbde0db14d0353a5e1831e
FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
source_dir="$ROOT/build/product-sd1-model-migration-v1"
qual="$source_dir/qualification-manifest.txt"
sim="$source_dir/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
old="$source_dir/qualified/3992m"
out="$ROOT/build/product-sd1-migrated-cross4b"
[[ ! -e "$out" ]] || die 'refuse to overwrite cross4B checkpoint/evidence'
[[ -s "$qual" && ! -L "$qual" && -s "$sim" && ! -L "$sim" &&
   -s "$old/persistent.pre.host" && ! -L "$old/persistent.pre.host" &&
   -s "$old/persistent.pre.rtl" && ! -L "$old/persistent.pre.rtl" ]] ||
  die 'missing qualified complete candidate 3992M artifact'
grep -Fxq 'PRODUCT_SD1_MODEL_MIGRATION_V1: status=QUALIFIED_UNDER_4B_ONLY' "$qual" ||
  die 'source differential was not qualified'
[[ "$(field original_model_sha256 "$qual")" == "$OLD_SHA" &&
   "$(field candidate_model_sha256 "$qual")" == "$CANDIDATE_SHA" &&
   "$(field candidate_model_sha256 "$qual")" == "$(sha "$sim")" &&
   "$(field old_3992_host_sha256 "$qual")" == "$SOURCE_3992_HOST_SHA" &&
   "$(field candidate_3992_host_sha256 "$qual")" == "$CANDIDATE_3992_HOST_SHA" &&
   "$(sha "$old/persistent.pre.host")" == "$CANDIDATE_3992_HOST_SHA" &&
   "$(field candidate_3992_rtl_sha256 "$qual")" == "$CANDIDATE_3992_RTL_SHA" &&
   "$(sha "$old/persistent.pre.rtl")" == "$CANDIDATE_3992_RTL_SHA" &&
   "$(field replay_cycles "$qual")" == 20000000 &&
   "$(field full_rtl_identical "$qual")" == 1 &&
   "$(field full_host_except_model_sha_identical "$qual")" == 1 ]] ||
  die 'candidate differential executable/state digest is incompatible'
grep -Fxq 'debian_login_claim=0' "$qual" || die 'false source Debian claim'
grep -Fxq 'cross_4b_executed=0' "$qual" || die 'source already crossed 4B'

# ZIP download does not retain POSIX executable bits.  Only re-enable the
# digest-authenticated candidate, and only after checking ELF class/machine.
[[ "$(uname -m)" == x86_64 ]] || die 'cross-run host architecture is not x86_64'
readelf -h "$sim" | grep -Eq 'Class:[[:space:]]+ELF64' || die 'model is not ELF64'
readelf -h "$sim" | grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64' ||
  die 'model machine architecture mismatch'
chmod 755 "$sim"
[[ -x "$sim" && "$(sha "$sim")" == "$(field candidate_model_sha256 "$qual")" ]] ||
  die 'candidate executable changed after restoring mode'
if ldd "$sim" > "$RUNNER_TEMP/candidate-abi-ldd.txt" 2>&1; then
  ! grep -Fq 'not found' "$RUNNER_TEMP/candidate-abi-ldd.txt" ||
    die 'candidate dynamic library unavailable on this runner'
else
  die 'candidate executable does not resolve dynamic ABI'
fi

python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  die 'original signed Debian SD1 media verification failed'
mapfile -d '' lists < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ "${#lists[@]}" == 1 ]] || die 'exactly one original polling candidate required'
polling="$(dirname "${lists[0]}")"
fw="$polling/boot/fw_payload-polling.bin"
dtb="$polling/boot/product-spi-polling.dtb"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
linux="$ROOT/build/product-sd1-gpio-cs-diagnostic/linux/obj/arch/riscv/boot/Image"
rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
[[ "$(sha "$fw")" == "$FW_SHA" && "$(sha "$dtb")" == "$DTB_SHA" &&
   "$(sha "$sd")" == "$SD_SHA" && "$(sha "$linux")" == "$LINUX_SHA" &&
   "$(sha "$rootfs")" =~ ^[a-f0-9]{64}$ ]] || die 'official media SHA drift'
grep -Fxq 'old_checkpoint_compatibility=FORBIDDEN' "${lists[0]}" ||
  die 'IRQ polling firmware boundary absent'
rootfs_sha="$(sha "$rootfs")"
new_sha="$(field candidate_model_sha256 "$qual")"

mkdir -p "$out/.staging" "$out/4012m"
start_epoch="$(date -u +%s)"
timeout --signal=TERM --kill-after=10s 20m \
  "$sim" advance "$fw" "$dtb" "$sd" "$old/persistent" \
    "$new_sha" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$rootfs_sha" \
    3992000000 20000000 "$out/.staging/persistent" > "$out/advance-4012.log" 2>&1 || {
      tail -70 "$out/advance-4012.log" >&2
      die 'authentic Product RTL 3992M->4012M progression failed'
    }
grep -Fq 'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=3992000000 new_cycles=4012000000 delta=20000000 ' \
  "$out/advance-4012.log" || die 'cross-4B RTL execution marker missing'
[[ -s "$out/.staging/persistent.pre.host" && -s "$out/.staging/persistent.pre.rtl" ]] ||
  die 'incomplete 4012M full Host/DDR/RTL state'

python3 - "$old/persistent.pre.host" "$out/.staging/persistent.pre.host" \
  "$SD_SHA" "$(( $(date -u +%s) - start_epoch ))" "$out/.staging/card-progress.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_relay_state import decode
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect as inspect_cpu,compare as compare_cpu
source,dest=map(Path,sys.argv[1:3]); media=sys.argv[3]; elapsed=int(sys.argv[4]); report=Path(sys.argv[5])
before,after=decode(source),decode(dest)
card_before,card_after=card_state_from_host(source),card_state_from_host(dest)
with source.open('rb') as fh: cpu_before=inspect_cpu(fh)
with dest.open('rb') as fh: cpu_after=inspect_cpu(fh)
cpu=compare_cpu(cpu_before,cpu_after)
if before['cycles']!=3992000000 or after['cycles']!=4012000000:
    raise SystemExit('MIGRATED_CROSS4B_REJECTED: cycle boundary incorrect')
if before['identity']!=after['identity'] or card_after['sd_media_sha256']!=media:
    raise SystemExit('MIGRATED_CROSS4B_REJECTED: model or signed media identity drift')
if after['failure'] or after['commits']<=before['commits']:
    raise SystemExit('MIGRATED_CROSS4B_REJECTED: guest failure or no retired instructions')
if cpu['retired_delta']!=after['commits']-before['commits']:
    raise SystemExit('MIGRATED_CROSS4B_REJECTED: CPU observer mismatch')
for key in ('sd_command_count','sd_reads','sd_writes'):
    if card_after[key]<card_before[key]:
        raise SystemExit('MIGRATED_CROSS4B_REJECTED: SD counter regressed: '+key)
result={
    'qualification':'MIGRATED_RTL_CROSS_4B_ONLY_NOT_DEBIAN_PASS',
    'from_cycles':3992000000,'to_cycles':4012000000,
    'retired_delta':cpu['retired_delta'],'sim_wall_seconds':elapsed,
    'sd_command_delta':card_after['sd_command_count']-card_before['sd_command_count'],
    'sd_read_delta':card_after['sd_reads']-card_before['sd_reads'],
    'sd_write_delta':card_after['sd_writes']-card_before['sd_writes'],
    'ext4_mounted':after['ext4_root_mounted'],'native_init':after['native_init_started'],
    'login_seen':after['login'],'guest_failure':after['failure'],
    'cpu_observer':cpu,
}
report.write_text(json.dumps(result,sort_keys=True,indent=2)+'\n')
print('PRODUCT_SD1_MIGRATED_CROSS_4B_REAL_PHASE: '+json.dumps(result,sort_keys=True))
PY
mv "$out/.staging/persistent.pre.host" "$out/4012m/persistent.pre.host"
mv "$out/.staging/persistent.pre.rtl" "$out/4012m/persistent.pre.rtl"
mv "$out/.staging/card-progress.json" "$out/4012m/card-progress.json"
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_REAL_RTL_CROSS_4B_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo 'boot_cycles=4012000000'
  echo 'source_cycles=3992000000'
  echo "model_sha256=$new_sha"
  echo "original_model_sha256=$OLD_SHA"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$rootfs_sha"
  echo "snapshot_host_sha256=$(sha "$out/4012m/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$out/4012m/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$old/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$old/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$out/4012m/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$out/4012m/.migration-ready"
cat "$out/4012m/.migration-ready"
echo 'PRODUCT_SD1_MIGRATED_CROSS4B_SAVED authentic_rtl=1 full_host_ddr_sd=1 source_untouched=1 not_debian_pass=1'
