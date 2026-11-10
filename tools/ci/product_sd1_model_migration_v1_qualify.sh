#!/usr/bin/env bash
set -euo pipefail

# One-off, no cold boot: authenticate two frozen polling checkpoints, compile
# ONLY a new Host runner from the original cached --savable generated RTL,
# migrate the earlier state without a guest tick, then differential-replay the
# exact already-qualified 3972M -> 3992M interval. Never changes old sources.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
die() { echo "PRODUCT_SD1_MODEL_MIGRATION_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

OLD_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
[[ "${AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM:-}" == FROZEN_SIGNED_SD1_MODEL_MIGRATE_V1 &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  die 'explicit signed SD1 migration, Verilator 5.054 and exact RTL timing profile required'

source_a="$ROOT/build/product-sd1-migration-source-3972m"
source_b="$ROOT/build/product-sd1-migration-source-3992m"
old_model="$ROOT/build/product-sd1-persistent-checkpoint/savable-model"
old_sim="$old_model/obj/VAetherCoreProductDebianSdRV64SimTop"
out="$ROOT/build/product-sd1-model-migration-v1"
candidate="$out/candidate-savable-model"
new_sim="$candidate/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ ! -e "$out" ]] || die 'refuse to overwrite migration evidence/candidate model'
[[ -x "$old_sim" && "$(sha "$old_sim")" == "$OLD_SHA" ]] ||
  die 'original frozen executable was not restored by exact cache key'

for spec in "3972 $source_a" "3992 $source_b"; do
  read -r m root <<< "$spec"
  ready="$root/.polling-ready"
  [[ -s "$ready" && ! -L "$ready" ]] || die "missing $m source seal"
  [[ "$(field schema "$ready")" == AETSDCP1 &&
     "$(field qualification "$ready")" == PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS &&
     "$(field boot_cycles "$ready")" == "$((m*1000000))" ]] ||
    die "$m source seal/schema incorrect"
  for key in model firmware dtb sd_image linux_image; do
    case "$key" in
      model) expected="$OLD_SHA";; firmware) expected="$FW_SHA";;
      dtb) expected="$DTB_SHA";; sd_image) expected="$SD_SHA";;
      linux_image) expected="$LINUX_SHA";;
    esac
    [[ "$(field "${key}_sha256" "$ready")" == "$expected" ]] ||
      die "$m source identity changed: $key"
  done
  [[ "$(field rootfs_result_sha256 "$ready")" =~ ^[a-f0-9]{64}$ ]] ||
    die "$m rootfs identity missing"
  for kind in host rtl; do
    file="$root/persistent.pre.$kind"
    [[ -s "$file" && ! -L "$file" &&
       "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
      die "$m unsealed $kind state"
  done
  [[ "$(field card_progress_sha256 "$ready")" == "$(sha "$root/card-progress.json")" ]] ||
    die "$m corrupted SD/guest activity evidence"
done
[[ "$(field rootfs_result_sha256 "$source_a/.polling-ready")" == \
   "$(field rootfs_result_sha256 "$source_b/.polling-ready")" ]] ||
  die 'rootfs identity changed between checkpoints'
[[ "$(field source_host_sha256 "$source_b/.polling-ready")" == \
   "$(sha "$source_a/persistent.pre.host")" ]] ||
  die '3972M->3992M source Host continuity missing'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  die 'original signed official Debian SD image failed verification'

import="$ROOT/build/product-sd1-spi-polling-import"
mapfile -d '' manifests < <(find "$import" -type f -name manifest.txt -print0)
[[ "${#manifests[@]}" == 1 ]] || die 'need exactly one sealed polling firmware manifest'
polling="$(dirname "${manifests[0]}")"
fw="$polling/boot/fw_payload-polling.bin"
dtb="$polling/boot/product-spi-polling.dtb"
sd="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
linux="$ROOT/build/product-sd1-gpio-cs-diagnostic/linux/obj/arch/riscv/boot/Image"
rootfs="$ROOT/build/rv64-debian-rootfs/result.txt"
[[ "$(sha "$fw")" == "$FW_SHA" && "$(sha "$dtb")" == "$DTB_SHA" &&
   "$(sha "$sd")" == "$SD_SHA" && "$(sha "$linux")" == "$LINUX_SHA" &&
   "$(sha "$rootfs")" == "$(field rootfs_result_sha256 "$source_a/.polling-ready")" ]] ||
  die 'candidate firmware, Linux, rootfs or original signed SD media changed'
[[ "$(field old_checkpoint_compatibility "${manifests[0]}")" == FORBIDDEN ]] ||
  die 'polling firmware must not accept old IRQ state'

# The prebuilt Verilated RTL sources are copied, not regenerated. The only
# source that changed in this candidate is the Host checkpoint C++ program.
mkdir -p "$out"
cp -a "$old_model" "$candidate"
[[ "$(sha "$old_sim")" == "$OLD_SHA" ]] || die 'old binary changed during copy'
obj="$candidate/obj"
[[ -s "$obj/VAetherCoreProductDebianSdRV64SimTop.mk" ]] ||
  die 'original cached generated Verilator Makefile missing'
generated_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \
      \( -name 'VAetherCoreProductDebianSdRV64SimTop*.cpp' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.h' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.mk' \) -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}
generated_object_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \
      \( -name 'VAetherCoreProductDebianSdRV64SimTop*.o' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.a' \) -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}
old_generated="$(generated_hash "$old_model")"
old_rtl_objects="$(generated_object_hash "$old_model")"
[[ "$old_generated" =~ ^[a-f0-9]{64}$ ]] || die 'generated RTL fingerprint absent'
old_compiler="$(c++ --version | head -1 | sha256sum | cut -c1-20)"
[[ "$(uname -m)-$old_compiler" == x86_64-89e4c31da3dafc5a664e ]] ||
  die 'source Host compiler ABI is different from frozen portable model'

# Rebuild the newer Host object in the COPY ONLY. Removing these two exact
# copied outputs forces Host recompile and relink without touching old cache.
[[ -f "$obj/product_rv64_checkpoint_sd1_main.o" && -x "$new_sim" ]] ||
  die 'copied original Host object/executable missing'
rm -- "$obj/product_rv64_checkpoint_sd1_main.o" "$new_sim"
verilator_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.054"
verilator_bin="$verilator_root/bin/verilator"
[[ -x "$verilator_bin" ]] || die 'pinned Verilator compiler was not restored'
export PATH="$(dirname "$verilator_bin"):$PATH"
# The pinned install is created by ensure_verilator_5_024.sh at this exact
# prefix.  `verilator -V` describes its *build-time* search location, which is
# not a durable identifier of the restored GitHub runner's install directory.
[[ -f "$verilator_root/include/verilated.mk" &&
   -f "$verilator_root/include/verilated.h" &&
   "$(< "$verilator_root/.aethercore-source-revision")" == bbb60a403932a9f695cb6ff001ceb73de6ad9234 ]] ||
  die 'pinned Verilator 5.054 source identity/runtime include files missing'
export VERILATOR_ROOT="$verilator_root"
timeout --signal=TERM --kill-after=20s 25m \
  make -C "$obj" -f VAetherCoreProductDebianSdRV64SimTop.mk \
    -j "${JOBS:-4}" > "$out/host-recompile.log" 2>&1 || {
      tail -70 "$out/host-recompile.log" >&2
      die 'candidate Host-only recompile failed'
    }
[[ -x "$new_sim" && "$(sha "$new_sim")" != "$OLD_SHA" ]] ||
  die 'candidate executable did not change Host implementation'
[[ "$(generated_hash "$candidate")" == "$old_generated" &&
   "$(generated_object_hash "$candidate")" == "$old_rtl_objects" &&
   "$(sha "$old_sim")" == "$OLD_SHA" ]] ||
  die 'generated Verilated RTL sources/objects or original binary were modified'
new_sha="$(sha "$new_sim")"
rootfs_sha="$(field rootfs_result_sha256 "$source_a/.polling-ready")"

mkdir -p "$out/qualified"
"$new_sim" migrate-v1 "$fw" "$dtb" "$sd" "$source_a/persistent" \
  "$OLD_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$rootfs_sha" \
  3972000000 0 "$old_sim" "$out/qualified/3972m/persistent" \
  > "$out/migrate-3972.log" 2>&1 || {
    tail -70 "$out/migrate-3972.log" >&2
    die 'zero-cycle model migration failed'
  }
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V1_ZERO_STEP_PASS' "$out/migrate-3972.log" ||
  die 'zero-cycle identity and full byte comparison did not PASS'
test "$(sha "$source_a/persistent.pre.rtl")" = \
     "$(sha "$out/qualified/3972m/persistent.pre.rtl")" ||
  die 'migrated RTL bytes changed'

mkdir -p "$out/qualified/3992m"
AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY_V2_MIGRATED \
  timeout --signal=TERM --kill-after=10s 9m \
  "$new_sim" advance "$fw" "$dtb" "$sd" "$out/qualified/3972m/persistent" \
    "$new_sha" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$rootfs_sha" \
    3972000000 20000000 "$out/qualified/3992m/persistent" \
    > "$out/advance-3992.log" 2>&1 || {
      tail -70 "$out/advance-3992.log" >&2
      die 'candidate 3972M->3992M real RTL advancement failed'
    }
grep -Fq 'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=3972000000 new_cycles=3992000000 ' \
  "$out/advance-3992.log" || die 'candidate real RTL stage marker missing'

# Same RTL and same complete 256 MiB DDR, card COW, observer, UART and
# transport bytes as the existing original model's 3992M result.
new_state="$out/qualified/3992m/persistent"
old_state="$source_b/persistent"
[[ -s "$new_state.pre.host" && -s "$new_state.pre.rtl" ]] ||
  die 'candidate differential state missing'
[[ "$(stat -c%s "$old_state.pre.host")" == "$(stat -c%s "$new_state.pre.host")" ]] ||
  die 'differential Host extents differ'
cmp -s "$old_state.pre.rtl" "$new_state.pre.rtl" ||
  die 'differential RTL states differ'
cmp -s -n 24 "$old_state.pre.host" "$new_state.pre.host" ||
  die 'differential Host header changed'
cmp -s -i 88:88 "$old_state.pre.host" "$new_state.pre.host" ||
  die 'differential 256MiB DDR, SD, UART or counters differ'
{
  echo 'PRODUCT_SD1_MODEL_MIGRATION_V1: status=QUALIFIED_UNDER_4B_ONLY'
  echo "original_model_sha256=$OLD_SHA"
  echo "candidate_model_sha256=$new_sha"
  echo "generated_rtl_sha256=$old_generated"
  echo "generated_rtl_objects_sha256=$old_rtl_objects"
  echo "old_3972_host_sha256=$(sha "$source_a/persistent.pre.host")"
  echo "old_3992_host_sha256=$(sha "$source_b/persistent.pre.host")"
  echo "candidate_3992_host_sha256=$(sha "$new_state.pre.host")"
  echo "candidate_3992_rtl_sha256=$(sha "$new_state.pre.rtl")"
  echo 'replay_cycles=20000000'
  echo 'full_rtl_identical=1'
  echo 'full_host_except_model_sha_identical=1'
  echo 'debian_login_claim=0'
  echo 'cross_4b_executed=0'
} > "$out/qualification-manifest.txt"
cat "$out/qualification-manifest.txt"
