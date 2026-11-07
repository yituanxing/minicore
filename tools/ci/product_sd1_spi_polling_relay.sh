#!/usr/bin/env bash
set -euo pipefail
# Real Product RTL short proof for a DIFFERENT SPI-polling DTB+FW identity.
# It does not run Debian to root, validate CMD0, or reuse IRQ-firmware memory.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source software/rv64/linux_early.env
die() { echo "PRODUCT_SD1_SPI_POLLING_SHORT_REJECTED: $*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
[[ "${AETHERCORE_SD1_SPI_POLLING_SHORT:-0}" == 1 &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY ]] ||
  die 'explicit pinned RTL short-test profile required'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  die 'signed original official Debian media unavailable'
diag="$ROOT/build/product-sd1-gpio-cs-diagnostic"
linux="$diag/linux/obj/arch/riscv/boot/Image"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
[[ -s "$linux" && -s "$sd" && -s "$rootfs" ]] || die 'original patched Linux+SD media missing'
[[ "$(sha "$linux")" == 522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393 &&
   "$(sha "$sd")" == 6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7 ]] ||
  die 'wrong Linux Image or signed SD1 media SHA'
import="$ROOT/build/product-sd1-spi-polling-import"
mapfile -d '' lists < <(find "$import" -type f -name manifest.txt -print0)
[[ "${#lists[@]}" == 1 ]] || die 'expected exactly one distinct polling candidate manifest'
from="$(dirname "${lists[0]}")"
fw="$from/boot/fw_payload-polling.bin"
dtb="$from/boot/product-spi-polling.dtb"
[[ -s "$fw" && -s "$dtb" && ! -L "$fw" && ! -L "$dtb" ]] ||
  die 'qualified polling firmware/DTB missing'
grep -Fxq 'PRODUCT_SD1_SPI_POLLING_CANDIDATE: status=BUILT_NOT_RTL_QUALIFIED' "${lists[0]}" ||
  die 'polling candidate build qualification missing'
[[ "$(field candidate_firmware_sha256 "${lists[0]}")" == "$(sha "$fw")" &&
   "$(field candidate_dtb_sha256 "${lists[0]}")" == "$(sha "$dtb")" &&
   "$(field image_sha256 "${lists[0]}")" == "$(sha "$linux")" &&
   "$(field media_sha256 "${lists[0]}")" == "$(sha "$sd")" &&
   "$(field old_checkpoint_compatibility "${lists[0]}")" == FORBIDDEN &&
   "$(sha "$fw")" == 4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be &&
   "$(sha "$dtb")" == 72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c ]] ||
  die 'unverified or wrong firmware identity'
simroot="$ROOT/build/product-sd1-persistent-checkpoint/savable-model"
sim="$simroot/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -x "$sim" && -s "$simroot/.savable-source-key" &&
   -s "$simroot/.product-model-identity" ]] || die 'model cache unavailable'
model_sha="$(sha "$sim")"
[[ "$model_sha" == 5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc ]] ||
  die 'model differs from frozen portable Product binary'
grep -Fxq "simulator_sha256=$model_sha" "$simroot/.product-model-identity" ||
  die 'model SHA seal invalid'
# Do not use foreign IRQ-based host DDR state; full identities are pinned.
[[ $# == 2 ]] || die 'usage: POLLING_SOURCE_DIR TARGET_ABSOLUTE_CYCLES'
old="$(realpath "$1")"
target="$2"
[[ "$old" == "$ROOT/build/"* && -f "$old/.polling-ready" &&
   ! -L "$old/.polling-ready" && "$target" =~ ^[0-9]+$ ]] ||
   die 'unqualified polling source directory'
start="$(field boot_cycles "$old/.polling-ready")"
[[ "$start" =~ ^[0-9]+$ && "$target" -gt "$start" &&
  "$((target-start))" -le 20000000 && "$((target%1000000))" -eq 0 &&
  "$target" -le 4000000000 ]] ||
  die 'warm advance must be 1..20M and target at most 4B cycles'
qualification="$(field qualification "$old/.polling-ready")"
[[ "$qualification" == PRODUCT_SD1_SPI_POLLING_12M_NOT_DEBIAN_PASS ||
   "$qualification" == PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS ]] ||
  die 'old IRQ/foreign checkpoint must not be restored'
[[ "$(field schema "$old/.polling-ready")" == AETSDCP1 ]] ||
  die 'wrong full-host state schema'
linux_sha="$(sha "$linux")"
fw_sha="$(sha "$fw")"
dtb_sha="$(sha "$dtb")"
sd_sha="$(sha "$sd")"
rootfs_sha="$(sha "$rootfs")"
for key in model firmware dtb sd_image linux_image rootfs_result; do
  case "$key" in
    model) expected="$model_sha";; firmware) expected="$fw_sha";; dtb) expected="$dtb_sha";;
    sd_image) expected="$sd_sha";; linux_image) expected="$linux_sha";; rootfs_result) expected="$rootfs_sha";;
  esac
  [[ "$(field "${key}_sha256" "$old/.polling-ready")" == "$expected" ]] ||
    die "source checkpoint identity differs: $key"
done
for kind in host rtl; do
  original="$old/persistent.pre.$kind"
  [[ -s "$original" && ! -L "$original" &&
    "$(field "snapshot_${kind}_sha256" "$old/.polling-ready")" == "$(sha "$original")" ]] ||
    die "source checkpoint $kind SHA fails"
done
[[ "$(field old_checkpoint_compatibility "${lists[0]}")" == FORBIDDEN ]] ||
  die 'distinct polling firmware guard absent'
out="$ROOT/build/product-sd1-spi-polling-relay"
tag="$((target/1000000))m"
mkdir -p "$out"
[[ ! -e "$out/$tag" ]] || die 'refuse to overwrite completed checkpoint'
stage="$(mktemp -d "$out/.advance-$tag.XXXXXXXX")"
prefix="$stage/persistent"
started="$(date -u +%s)"
timeout --signal=TERM --kill-after=10s 330s \
  "$sim" advance "$fw" "$dtb" "$sd" "$old/persistent" \
    "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
    "$start" "$((target-start))" "$prefix" > "$stage/save.log" 2>&1 || {
    tail -60 "$stage/save.log" >&2
    die 'full new-polling RTL warm advance failed; source untouched'
  }
elapsed="$(($(date -u +%s)-started))"
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$start new_cycles=$target " "$stage/save.log" ||
  die 'real Product RTL cycle advance marker absent'
[[ -s "$stage/persistent.pre.host" && -s "$stage/persistent.pre.rtl" ]] ||
  die 'new full RTL, 256MiB DDR and SD state missing'
python3 - "$old" "$stage" "$start" "$target" "$sd_sha" "$elapsed" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,"tools/ci")
from product_sd1_relay_state import decode
from product_sd1_card_snapshot import card_state_from_host
from product_sd1_cpu_observer_audit import inspect as inspect_cpu, compare as compare_cpu
old,new=Path(sys.argv[1]),Path(sys.argv[2])
start,end=int(sys.argv[3]),int(sys.argv[4])
media=sys.argv[5]
elapsed=int(sys.argv[6])
before=decode(old/"persistent.pre.host")
after=decode(new/"persistent.pre.host")
cb=card_state_from_host(old/"persistent.pre.host")
ca=card_state_from_host(new/"persistent.pre.host")
with (old/"persistent.pre.host").open("rb") as file:
    cpu_before=inspect_cpu(file)
with (new/"persistent.pre.host").open("rb") as file:
    cpu_after=inspect_cpu(file)
cpu=compare_cpu(cpu_before,cpu_after)
if cpu["retired_delta"]!=after["commits"]-before["commits"]:
    raise SystemExit("POLLING_RELAY_REJECTED: CPU observer/retirement mismatch")
if before["cycles"]!=start or after["cycles"]!=end or after["commits"]<=before["commits"]:
    raise SystemExit("POLLING_RELAY_REJECTED: no valid RTL instruction progression")
if after["failure"] or before["identity"]!=after["identity"] or ca["sd_media_sha256"]!=media:
    raise SystemExit("POLLING_RELAY_REJECTED: fatal guest log or media/firmware identity drift")
for name in ("sd_command_count","sd_reads","sd_writes"):
    if ca[name]<cb[name]:
        raise SystemExit("POLLING_RELAY_REJECTED: SPI SD counter regressed: "+name)
stats={
 "qualification":"POLLING_REAL_PRODUCT_RTL_NOT_DEBIAN_PASS",
 "from_cycles":start,"to_cycles":end,
 "retired":after["commits"],"retired_delta":after["commits"]-before["commits"],
 "sim_wall_seconds":elapsed,
 "sd_command_count":ca["sd_command_count"],
 "sd_command_delta":ca["sd_command_count"]-cb["sd_command_count"],
 "sd_reads":ca["sd_reads"],"sd_read_delta":ca["sd_reads"]-cb["sd_reads"],
 "sd_writes":ca["sd_writes"],"sd_write_delta":ca["sd_writes"]-cb["sd_writes"],
 "sd_74_clocks_seen":ca["sd_74_clocks_seen"],
 "sd_init_complete":ca["sd_init_complete"],
 "ext4_mounted":after["ext4_root_mounted"],
 "native_init":after["native_init_started"],"login_seen":after["login"],
 "guest_failure":after["failure"],"cmd0_r1_proven":False,
 "cpu_observer":{
     "retired_delta":cpu["retired_delta"],
     "exceptions_delta":cpu["exceptions_delta"],
     "interrupts_delta":cpu["interrupts_delta"],
     "timer_interrupts_delta":cpu["timer_interrupts_delta"],
     "uart_bytes_delta":cpu["uart_bytes_delta"],
     "last_exception_cause":cpu["last_exception_cause"],
     "last_exception_pc":cpu["last_exception_pc"],
     "last_exception_is_user_ecall":cpu["last_exception_is_user_ecall"],
     "last_uart_cycle":cpu["last_uart_cycle"],
     "last_uart_age_cycles":cpu["last_uart_age_cycles"],
     "last_16_retired_pc":cpu["last_16_retired_pc"],
     "retirement_during_uart_silence":cpu["retirement_during_uart_silence"],
     "qualification":"CPU_SNAPSHOT_ONLY_NOT_PID1_OR_LOGIN_PASS",
 }
}
(new/"card-progress.json").write_text(json.dumps(stats,sort_keys=True,indent=2)+"\n")
print("PRODUCT_SD1_SPI_POLLING_REAL_PHASE: "+json.dumps(stats,sort_keys=True))
PY
{
  echo 'qualification=PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$target"
  echo "model_sha256=$model_sha"
  echo "firmware_sha256=$fw_sha"
  echo "dtb_sha256=$dtb_sha"
  echo "sd_image_sha256=$sd_sha"
  echo "linux_image_sha256=$linux_sha"
  echo "rootfs_result_sha256=$rootfs_sha"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo "card_progress_sha256=$(sha "$stage/card-progress.json")"
  echo "source_host_sha256=$(sha "$old/persistent.pre.host")"
} > "$stage/.polling-ready"
mv "$stage" "$out/$tag"
echo "PRODUCT_SD1_SPI_POLLING_RELAY_SAVED cycle=$target full_rtl_ddr_sd=1 source_untouched=1"
