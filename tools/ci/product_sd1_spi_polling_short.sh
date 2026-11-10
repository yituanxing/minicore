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
out="$ROOT/build/product-sd1-spi-polling-rtl-short"
[[ ! -e "$out" ]] || die 'polling guest checkpoint output must not exist'
mkdir -p "$out"
stage="$(mktemp -d "$out/boot-12m.XXXXXXXX")"
prefix="$stage/persistent"
linux_sha="$(sha "$linux")"
fw_sha="$(sha "$fw")"
dtb_sha="$(sha "$dtb")"
sd_sha="$(sha "$sd")"
rootfs_sha="$(sha "$rootfs")"
timeout --signal=TERM --kill-after=10s 360s \
  "$sim" save "$fw" "$dtb" "$sd" "$prefix" \
  "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
  12000000 500000 > "$stage/save.log" 2>&1 || {
    tail -60 "$stage/save.log" >&2; die 'new polling FW 12M cold Product RTL save failed';
  }
timeout --signal=TERM --kill-after=10s 240s \
  "$sim" restore "$fw" "$dtb" "$sd" "$prefix" \
  "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
  12000000 500000 > "$stage/restore.log" 2>&1 || {
    tail -60 "$stage/restore.log" >&2; die '12M polling FW separate-process restore failed';
  }
grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$stage/save.log" > "$stage/continuous.state"
grep '^PRODUCT_SD1_PERSISTENT_CONTINUATION ' "$stage/restore.log" > "$stage/restored.state"
cmp -s "$stage/continuous.state" "$stage/restored.state" &&
cmp -s "$stage/persistent.continuous.rtl" "$stage/persistent.restored.rtl" &&
cmp -s "$stage/persistent.continuous.host" "$stage/persistent.restored.host" ||
  die '12M polling FW RTL/host replay mismatch'
grep -Fq 'PRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles=12000000 ' "$stage/save.log" ||
  die 'genuine Product RTL checkpoint completion missing'
[[ -s "$stage/persistent.pre.rtl" && -s "$stage/persistent.pre.host" ]] ||
  die 'complete Product RTL+256MiB RAM+SD state missing'
{
  echo 'qualification=PRODUCT_SD1_SPI_POLLING_12M_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo 'boot_cycles=12000000'
  echo "model_sha256=$model_sha"
  echo "firmware_sha256=$fw_sha"
  echo "dtb_sha256=$dtb_sha"
  echo "sd_image_sha256=$sd_sha"
  echo "linux_image_sha256=$linux_sha"
  echo "rootfs_result_sha256=$rootfs_sha"
  echo "snapshot_rtl_sha256=$(sha "$stage/persistent.pre.rtl")"
  echo "snapshot_host_sha256=$(sha "$stage/persistent.pre.host")"
  echo 'no_prior_checkpoint_reuse=1'
} > "$stage/.polling-ready"
echo 'PRODUCT_SD1_SPI_POLLING_RTL_SHORT: status=12M_SAVE_REPLAY_PASS_NOT_DEBIAN'
cat "$stage/.polling-ready"
