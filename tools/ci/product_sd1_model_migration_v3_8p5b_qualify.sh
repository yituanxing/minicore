#!/usr/bin/env bash
# DRAFT / ISOLATED: Host-v3 successor to qualified Host-v2. NO official runs.
# Zero-write plan by default. C 8072M/8092M artifacts are not yet attested.
# The new 8.5B model qualifies on 8072M->8092M wholly below the old 8.1B cap.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
fail() { echo "PRODUCT_SD1_MODEL_MIGRATION_V3_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly SOURCE_SHA=c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70
readonly PREVIOUS_SHA=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461
readonly ORIGINAL_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e
readonly FROM=8072000000 TO=8092000000 STEP=20000000
# All six C endpoint hashes were independently downloaded and verified after
# the complete 42496075997 four-stage genuine Product RTL run succeeded.
readonly AUTHENTIC_8072_HOST_SHA=4a80a8ce142820af18e5729053443179a01dc2c2949456dc6a4397a7e8a751c3
readonly AUTHENTIC_8072_RTL_SHA=21a453ea346ee9d8aee8d25a152d7700e5f0814dd2f223e751b971607535f527
readonly AUTHENTIC_8072_CARD_SHA=9ce1067ecbcb4682f9d3a2d249672fee09810b0e65d0d0cebca3e046236bc1a6
readonly AUTHENTIC_8092_HOST_SHA=e575046e572bfe55a8bff307780c61275923f92bcb6993dc2ec397729ec02e1e
readonly AUTHENTIC_8092_RTL_SHA=cb5125d542bb438ef73ac155b962bf9890160103d43eedbd82182fffea591d72
readonly AUTHENTIC_8092_CARD_SHA=c1d901818898167198ef0c548a30a3882bd8e8e72f3d456f05f63b627ca293d5
readonly MODEL_DIR="$ROOT/build/product-sd1-model-migration-v2-draft/candidate-savable-model"
readonly SOURCE_8072="${AETHERCORE_SD1_V3_SOURCE_8072_DIR:-}"
readonly SOURCE_8092="${AETHERCORE_SD1_V3_SOURCE_8092_DIR:-}"
readonly OUT="$ROOT/build/product-sd1-model-migration-v3-8p5b-draft"

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  printf 'PRODUCT_SD1_MIGRATE_V3_8P5B_PLAN_ONLY no_guest_cycles=1 model_source_sha=%s\n' "$SOURCE_SHA"
  printf 'Required: independently authenticated C 8072M and 8092M; unchanged Verilator-generated RTL/object identity; 8072M zero-cycle migration; old-v2/new-v3 8072M->8092M 20M differential; RTL all bytes and Host except SHA bytes 24..87 equal.\n'
  printf 'No previous stage or original model is overwritten. Real execution is disabled without explicit --execute-qualified-diff and independent confirmation.\n'
  exit 0
fi
[[ $# == 1 && "$1" == --execute-qualified-diff ]] || fail 'only --plan or --execute-qualified-diff allowed'
[[ "${AETHERCORE_SD1_V3_QUALIFICATION_EXECUTE:-}" == HOST_ONLY_8072_TO_8092_REAL_RTL_REVIEWED &&
   "${AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM:-}" == FROZEN_SIGNED_SD1_MODEL_MIGRATE_V3_8P5B_8072M &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  fail 'v3 qualification requires explicit independent C reviewer approval and exact Product timing profile'
[[ -n "$SOURCE_8072" && -n "$SOURCE_8092" ]] || fail 'source stage roots must be explicit'
[[ "$SOURCE_8072" == /* && "$SOURCE_8092" == /* &&
   -d "$SOURCE_8072" && -d "$SOURCE_8092" ]] || fail 'source paths must be existing absolute stage directories'
[[ "$(realpath -e "$SOURCE_8072")" == "$SOURCE_8072" &&
   "$(realpath -e "$SOURCE_8092")" == "$SOURCE_8092" &&
   "$(basename "$SOURCE_8072")" == 8072m &&
   "$(basename "$SOURCE_8092")" == 8092m ]] || fail 'noncanonical or mistyped source paths'
[[ "$SOURCE_8072" != "$SOURCE_8092" && ! -e "$OUT" ]] || fail 'source aliases or existing candidate output forbidden'
for pin in "$AUTHENTIC_8072_HOST_SHA" "$AUTHENTIC_8072_RTL_SHA" "$AUTHENTIC_8072_CARD_SHA" \
           "$AUTHENTIC_8092_HOST_SHA" "$AUTHENTIC_8092_RTL_SHA" "$AUTHENTIC_8092_CARD_SHA"; do
  [[ "$pin" =~ ^[0-9a-f]{64}$ ]] || fail 'C source artifact SHA not independently authenticated'
done

verify_stage() {
  local dir="$1" cycle="$2" ready="$1/.migration-ready" kind file
  [[ -s "$ready" && ! -L "$ready" &&
     "$(field schema "$ready")" == AETSDCP1 &&
     "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
     "$(field boot_cycles "$ready")" == "$cycle" &&
     "$(field model_sha256 "$ready")" == "$SOURCE_SHA" &&
     "$(field previous_model_sha256 "$ready")" == "$PREVIOUS_SHA" &&
     "$(field original_model_sha256 "$ready")" == "$ORIGINAL_SHA" &&
     "$(field debian_login_claim "$ready")" == 0 &&
     "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
     "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
     "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
     "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
     "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" ]] || fail "${cycle}M source seal mismatch"
  for kind in host rtl; do
    file="$dir/persistent.pre.$kind"
    [[ -s "$file" && ! -L "$file" &&
       "$(field "snapshot_${kind}_sha256" "$ready")" == "$(sha "$file")" ]] ||
      fail "source $cycle $kind SHA/extent mismatch"
  done
  [[ -s "$dir/card-progress.json" && ! -L "$dir/card-progress.json" &&
     "$(field card_progress_sha256 "$ready")" == "$(sha "$dir/card-progress.json")" ]] ||
    fail "source $cycle SD/card evidence SHA mismatch"
}
verify_stage "$SOURCE_8072" "$FROM"
verify_stage "$SOURCE_8092" "$TO"
[[ "$(sha "$SOURCE_8072/persistent.pre.host")" == "$AUTHENTIC_8072_HOST_SHA" &&
   "$(sha "$SOURCE_8072/persistent.pre.rtl")" == "$AUTHENTIC_8072_RTL_SHA" &&
   "$(sha "$SOURCE_8072/card-progress.json")" == "$AUTHENTIC_8072_CARD_SHA" ]] ||
  fail 'official C 8072M source Host/RTL/SD differ from exact attested artifact'
[[ "$(sha "$SOURCE_8092/persistent.pre.host")" == "$AUTHENTIC_8092_HOST_SHA" &&
   "$(sha "$SOURCE_8092/persistent.pre.rtl")" == "$AUTHENTIC_8092_RTL_SHA" &&
   "$(sha "$SOURCE_8092/card-progress.json")" == "$AUTHENTIC_8092_CARD_SHA" ]] ||
  fail 'official C 8092M source Host/RTL/SD differ from exact attested artifact'
[[ "$(field source_cycles "$SOURCE_8072/.migration-ready")" == 8052000000 &&
   "$(field source_cycles "$SOURCE_8092/.migration-ready")" == "$FROM" &&
   "$(field source_host_sha256 "$SOURCE_8092/.migration-ready")" == "$(sha "$SOURCE_8072/persistent.pre.host")" &&
   "$(field source_rtl_sha256 "$SOURCE_8092/.migration-ready")" == "$(sha "$SOURCE_8072/persistent.pre.rtl")" ]] ||
  fail 'C 8052M→8072M→8092M source state continuity not authenticated'

old_sim="$MODEL_DIR/obj/VAetherCoreProductDebianSdRV64SimTop"
old_qual="$ROOT/build/product-sd1-model-migration-v2-draft/qualification-manifest.txt"
[[ -x "$old_sim" && ! -L "$old_sim" && "$(sha "$old_sim")" == "$SOURCE_SHA" &&
   "$(field candidate_model_sha256 "$old_qual")" == "$SOURCE_SHA" &&
   "$(field previous_model_sha256 "$old_qual")" == "$PREVIOUS_SHA" &&
   "$(field original_model_sha256 "$old_qual")" == "$ORIGINAL_SHA" &&
   "$(field replay_cycles "$old_qual")" == 20000000 &&
   "$(field full_rtl_identical "$old_qual")" == 1 &&
   "$(field full_host_except_model_sha_identical "$old_qual")" == 1 ]] ||
  fail 'qualified migrated-v2 source model/20M proof not authenticated'
[[ "$(uname -m)" == x86_64 &&
   "$(c++ --version | head -1 | sha256sum | cut -c1-20)" == 89e4c31da3dafc5a664e ]] ||
  fail 'Host-only rebuild must use original frozen compiler ABI'

import="$ROOT/build/product-sd1-spi-polling-import"
mapfile -d '' manifests < <(find "$import" -type f -name manifest.txt -print0)
[[ "${#manifests[@]}" == 1 ]] || fail 'must have exactly one polling media manifest'
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
  fail 'official signed firmware, DTB, SD, Linux or rootfs drift'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  fail 'official Debian SD seed verification failed'

generated_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \
      \( -name 'VAetherCoreProductDebianSdRV64SimTop*.cpp' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.h' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.mk' \) -print0 | \
    sort -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f1)
}
generated_object_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \
      \( -name 'VAetherCoreProductDebianSdRV64SimTop*.o' -o \
         -name 'VAetherCoreProductDebianSdRV64SimTop*.a' \) -print0 | \
    sort -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f1)
}
old_generated="$(generated_hash "$MODEL_DIR")"
old_objects="$(generated_object_hash "$MODEL_DIR")"
[[ "$old_generated" =~ ^[a-f0-9]{64}$ && "$old_objects" =~ ^[a-f0-9]{64}$ &&
   "$old_generated" == "$(field generated_rtl_sha256 "$old_qual")" &&
   "$old_objects" == "$(field generated_rtl_objects_sha256 "$old_qual")" ]] ||
  fail 'source generated Verilator RTL fingerprint not qualified'

mkdir -p "$OUT"
candidate="$OUT/candidate-savable-model"
cp -a "$MODEL_DIR" "$candidate"
obj="$candidate/obj"
new_sim="$obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -f "$obj/product_rv64_checkpoint_sd1_main.o" && -x "$new_sim" ]] ||
  fail 'copied Host object / executable missing'
rm -- "$obj/product_rv64_checkpoint_sd1_main.o" "$new_sim"
verilator_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.054"
[[ -x "$verilator_root/bin/verilator" &&
   -f "$verilator_root/include/verilated.mk" &&
   "$(< "$verilator_root/.aethercore-source-revision")" == bbb60a403932a9f695cb6ff001ceb73de6ad9234 ]] ||
  fail 'pinned Verilator 5.054 ABI unavailable'
export PATH="$verilator_root/bin:$PATH" VERILATOR_ROOT="$verilator_root"
timeout --signal=TERM --kill-after=20s 25m \
  make -C "$obj" -f VAetherCoreProductDebianSdRV64SimTop.mk -j "${JOBS:-4}" \
  > "$OUT/host-recompile.log" 2>&1 || fail 'Host-only compilation failed'
[[ -x "$new_sim" && "$(sha "$new_sim")" != "$SOURCE_SHA" &&
   "$(sha "$old_sim")" == "$SOURCE_SHA" &&
   "$(generated_hash "$candidate")" == "$old_generated" &&
   "$(generated_object_hash "$candidate")" == "$old_objects" ]] ||
  fail 'Host-only candidate changed generated RTL or original executable'
new_sha="$(sha "$new_sim")"

mkdir -p "$OUT/qualified"
"$new_sim" migrate-v3-8p5b "$fw" "$dtb" "$sd" "$SOURCE_8072/persistent" \
  "$SOURCE_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$FROM" 0 "$old_sim" "$OUT/qualified/8072m/persistent" \
  > "$OUT/migrate-8072.log" 2>&1 || fail 'zero-cycle 8072M migration failed'
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V3_8P5B_ZERO_STEP_PASS' "$OUT/migrate-8072.log" ||
  fail 'zero-cycle v3 model migration marker missing'
cmp -s "$SOURCE_8072/persistent.pre.rtl" \
  "$OUT/qualified/8072m/persistent.pre.rtl" || fail '8072M RTL bytes changed'
cmp -s -n 24 "$SOURCE_8072/persistent.pre.host" \
  "$OUT/qualified/8072m/persistent.pre.host" || fail '8072M Host header changed'
cmp -s -i 88:88 "$SOURCE_8072/persistent.pre.host" \
  "$OUT/qualified/8072m/persistent.pre.host" || fail '8072M Host non-model bytes changed'

mkdir -p "$OUT/qualified/8092m"
AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY_V4_MIGRATED_8P5B \
  timeout --signal=TERM --kill-after=12s 20m \
  "$new_sim" advance "$fw" "$dtb" "$sd" "$OUT/qualified/8072m/persistent" \
    "$new_sha" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
    "$FROM" "$STEP" "$OUT/qualified/8092m/persistent" \
    > "$OUT/advance-8092.log" 2>&1 || fail 'new Host 8072M→8092M 20M differential failed'
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$FROM new_cycles=$TO delta=$STEP " \
  "$OUT/advance-8092.log" || fail 'real RTL 20M advancement marker missing'
cmp -s "$SOURCE_8092/persistent.pre.rtl" \
  "$OUT/qualified/8092m/persistent.pre.rtl" || fail '20M RTL state differs from original C 8092M'
cmp -s -n 24 "$SOURCE_8092/persistent.pre.host" \
  "$OUT/qualified/8092m/persistent.pre.host" || fail '20M Host header differs'
cmp -s -i 88:88 "$SOURCE_8092/persistent.pre.host" \
  "$OUT/qualified/8092m/persistent.pre.host" || fail '20M Host non-model state differs'

# Identity-only transition is now proven on the historical 20M interval.
# The exact same source's card evidence is valid because the serialized state
# and replay trace are byte-identical; retain the signed JSON under its SHA.
cp -- "$SOURCE_8092/card-progress.json" "$OUT/qualified/8092m/card-progress.json"
ready="$OUT/qualified/8092m/.migration-ready"
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TO"
  echo "source_cycles=$FROM"
  echo "model_sha256=$new_sha"
  echo "previous_model_sha256=$SOURCE_SHA"
  echo "original_model_sha256=$ORIGINAL_SHA"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$ROOTFS_SHA"
  echo "snapshot_host_sha256=$(sha "$OUT/qualified/8092m/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$OUT/qualified/8092m/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$OUT/qualified/8072m/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$OUT/qualified/8072m/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$OUT/qualified/8092m/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$ready"
{
  echo 'PRODUCT_SD1_MODEL_MIGRATION_V3: status=QUALIFIED_HOST_ONLY_8072_TO_8092_20M_DIFF_NOT_8P1B_CROSS'
  echo "previous_model_sha256=$SOURCE_SHA"
  echo "candidate_model_sha256=$new_sha"
  echo "generated_rtl_sha256=$old_generated"
  echo "generated_rtl_objects_sha256=$old_objects"
  echo "old_8072_host_sha256=$(sha "$SOURCE_8072/persistent.pre.host")"
  echo "old_8092_host_sha256=$(sha "$SOURCE_8092/persistent.pre.host")"
  echo "candidate_8092_host_sha256=$(sha "$OUT/qualified/8092m/persistent.pre.host")"
  echo "candidate_8092_rtl_sha256=$(sha "$OUT/qualified/8092m/persistent.pre.rtl")"
  echo 'replay_cycles=20000000'
  echo 'full_rtl_identical=1'
  echo 'full_host_except_model_sha_identical=1'
  echo 'debian_login_claim=0'
  echo 'cross_8p1b_executed=0'
} > "$OUT/qualification-manifest.txt"
echo 'PRODUCT_SD1_MODEL_MIGRATION_V3_HOST_ONLY_DIFFERENTIAL_PASS_NOT_8P1B_OR_DEBIAN_PASS'
