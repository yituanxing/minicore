#!/usr/bin/env bash
# INERT ISOLATED Host-v5 candidate after qualified 10B Host-v4. No official launch.
# Default is no-write plan. O9972/O9992 SHA pins must be independently reviewed.
# Qualify 9972M->9992M entirely below the old v4 10B ceiling; NEVER cross 10B.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
fail() { echo "PRODUCT_SD1_MODEL_MIGRATION_V5_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly SOURCE_SHA=ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a
readonly PREVIOUS_SHA=04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6
readonly ORIGINAL_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e
readonly FROM=9972000000 TO=9992000000 STEP=20000000
# O official source artifacts are still running; no unreviewed historical hashes.
# The reviewed workflow must pin O run/head/ZIP independently before extraction.
readonly AUTHENTIC_9972_HOST_SHA="${AETHERCORE_SD1_V5_9972_HOST_SHA:-}"
readonly AUTHENTIC_9972_RTL_SHA="${AETHERCORE_SD1_V5_9972_RTL_SHA:-}"
readonly AUTHENTIC_9972_CARD_SHA="${AETHERCORE_SD1_V5_9972_CARD_SHA:-}"
readonly AUTHENTIC_9992_HOST_SHA="${AETHERCORE_SD1_V5_9992_HOST_SHA:-}"
readonly AUTHENTIC_9992_RTL_SHA="${AETHERCORE_SD1_V5_9992_RTL_SHA:-}"
readonly AUTHENTIC_9992_CARD_SHA="${AETHERCORE_SD1_V5_9992_CARD_SHA:-}"
readonly MODEL_DIR="$ROOT/build/product-sd1-model-migration-v4-10b-qualified/candidate-savable-model"
readonly SOURCE_9972="${AETHERCORE_SD1_V5_SOURCE_9972_DIR:-}"
readonly SOURCE_9992="${AETHERCORE_SD1_V5_SOURCE_9992_DIR:-}"
readonly OUT="$ROOT/build/product-sd1-model-migration-v5-15b-draft"

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  printf 'PRODUCT_SD1_MIGRATE_V5_15B_PLAN_ONLY no_guest_cycles=1 model_source_sha=%s\n' "$SOURCE_SHA"
  printf 'Required: authentic O9972/O9992 Host+RTL+card, V4 qualification, unchanged Verilator RTL/objects; zero-cycle migration 9972 and 9992; old-v4/new-v5 20M differential with every RTL byte and every non-SHA Host byte identical.\n'
  printf 'No previous stage or original model is overwritten. Real execution is disabled without explicit --execute-qualified-diff and independent confirmation.\n'
  exit 0
fi
[[ $# == 1 && "$1" == --execute-qualified-diff ]] || fail 'only --plan or --execute-qualified-diff allowed'
[[ "${AETHERCORE_SD1_V5_QUALIFICATION_EXECUTE:-}" == HOST_ONLY_9972_TO_9992_REAL_RTL_REVIEWED &&
   "${AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM:-}" == FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_DIFF_9972M &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference &&
   "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  fail 'v5 qualification requires independent O source review and exact Product timing profile'
[[ -n "$SOURCE_9972" && -n "$SOURCE_9992" ]] || fail 'source stage roots must be explicit'
[[ "$SOURCE_9972" == /* && "$SOURCE_9992" == /* &&
   -d "$SOURCE_9972" && -d "$SOURCE_9992" ]] || fail 'source paths must be existing absolute stage directories'
[[ "$(realpath -e "$SOURCE_9972")" == "$SOURCE_9972" &&
   "$(realpath -e "$SOURCE_9992")" == "$SOURCE_9992" &&
   "$(basename "$SOURCE_9972")" == 9972m &&
   "$(basename "$SOURCE_9992")" == 9992m ]] || fail 'noncanonical or mistyped source paths'
[[ "$SOURCE_9972" != "$SOURCE_9992" && ! -e "$OUT" ]] || fail 'source aliases or existing candidate output forbidden'
for pin in "$AUTHENTIC_9972_HOST_SHA" "$AUTHENTIC_9972_RTL_SHA" "$AUTHENTIC_9972_CARD_SHA" \
           "$AUTHENTIC_9992_HOST_SHA" "$AUTHENTIC_9992_RTL_SHA" "$AUTHENTIC_9992_CARD_SHA"; do
  [[ "$pin" =~ ^[0-9a-f]{64}$ ]] || fail 'O source artifact SHA not independently authenticated'
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
verify_stage "$SOURCE_9972" "$FROM"
verify_stage "$SOURCE_9992" "$TO"
[[ "$(sha "$SOURCE_9972/persistent.pre.host")" == "$AUTHENTIC_9972_HOST_SHA" &&
   "$(sha "$SOURCE_9972/persistent.pre.rtl")" == "$AUTHENTIC_9972_RTL_SHA" &&
   "$(sha "$SOURCE_9972/card-progress.json")" == "$AUTHENTIC_9972_CARD_SHA" ]] ||
  fail 'official E 9972M source Host/RTL/SD differ from exact attested artifact'
[[ "$(sha "$SOURCE_9992/persistent.pre.host")" == "$AUTHENTIC_9992_HOST_SHA" &&
   "$(sha "$SOURCE_9992/persistent.pre.rtl")" == "$AUTHENTIC_9992_RTL_SHA" &&
   "$(sha "$SOURCE_9992/card-progress.json")" == "$AUTHENTIC_9992_CARD_SHA" ]] ||
  fail 'official F 9992M source Host/RTL/SD differ from exact attested artifact'
[[ "$(field source_cycles "$SOURCE_9972/.migration-ready")" == 9952000000 &&
   "$(field source_cycles "$SOURCE_9992/.migration-ready")" == "$FROM" &&
   "$(field source_host_sha256 "$SOURCE_9992/.migration-ready")" == "$(sha "$SOURCE_9972/persistent.pre.host")" &&
   "$(field source_rtl_sha256 "$SOURCE_9992/.migration-ready")" == "$(sha "$SOURCE_9972/persistent.pre.rtl")" ]] ||
  fail 'O 9952M→9972M→9992M source state continuity not authenticated'

old_sim="$MODEL_DIR/obj/VAetherCoreProductDebianSdRV64SimTop"
old_qual="$ROOT/build/product-sd1-model-migration-v4-10b-qualified/qualification-manifest.txt"
[[ -x "$old_sim" && ! -L "$old_sim" && "$(sha "$old_sim")" == "$SOURCE_SHA" &&
   "$(field candidate_model_sha256 "$old_qual")" == "$SOURCE_SHA" &&
   "$(field previous_model_sha256 "$old_qual")" == "$PREVIOUS_SHA" &&
   "$(field replay_cycles "$old_qual")" == 20000000 &&
   "$(field full_rtl_identical "$old_qual")" == 1 &&
   "$(field full_host_except_model_sha_identical "$old_qual")" == 1 &&
   "$(field debian_login_claim "$old_qual")" == 0 &&
   "$(field cross_8p5b_executed "$old_qual")" == 0 ]] ||
  fail 'qualified migrated-v4 source model/20M proof not authenticated'
grep -Fxq 'PRODUCT_SD1_MODEL_MIGRATION_V4: status=QUALIFIED_HOST_ONLY_8272_TO_8292_20M_DIFF_NOT_8P5B_CROSS' "$old_qual" ||
  fail 'historical Host-v4 real RTL exact 20M differential marker missing'
# Both O checkpoints must seal the original signed polling model and v3 lineage.
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
"$new_sim" migrate-v5-15b "$fw" "$dtb" "$sd" "$SOURCE_9972/persistent" \
  "$SOURCE_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$FROM" 0 "$old_sim" "$OUT/qualified/9972m/persistent" \
  > "$OUT/migrate-9972.log" 2>&1 || fail 'zero-cycle 9972M migration failed'
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V5_15B_ZERO_STEP_PASS' "$OUT/migrate-9972.log" ||
  fail 'zero-cycle v5 model migration marker missing'
cmp -s "$SOURCE_9972/persistent.pre.rtl" \
  "$OUT/qualified/9972m/persistent.pre.rtl" || fail '9972M RTL bytes changed'
cmp -s -n 24 "$SOURCE_9972/persistent.pre.host" \
  "$OUT/qualified/9972m/persistent.pre.host" || fail '9972M Host header changed'
cmp -s -i 88:88 "$SOURCE_9972/persistent.pre.host" \
  "$OUT/qualified/9972m/persistent.pre.host" || fail '9972M Host non-model bytes changed'

mkdir -p "$OUT/qualified-differential/9992m"
AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY_V6_MIGRATED_15B \
  timeout --signal=TERM --kill-after=12s 20m \
  "$new_sim" advance "$fw" "$dtb" "$sd" "$OUT/qualified/9972m/persistent" \
    "$new_sha" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
    "$FROM" "$STEP" "$OUT/qualified-differential/9992m/persistent" \
    > "$OUT/advance-9992.log" 2>&1 || fail 'new Host 9972M→9992M 20M differential failed'
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$FROM new_cycles=$TO delta=$STEP " \
  "$OUT/advance-9992.log" || fail 'real RTL 20M advancement marker missing'
cmp -s "$SOURCE_9992/persistent.pre.rtl" \
  "$OUT/qualified-differential/9992m/persistent.pre.rtl" || fail '20M RTL state differs from authentic O9992'
cmp -s -n 24 "$SOURCE_9992/persistent.pre.host" \
  "$OUT/qualified-differential/9992m/persistent.pre.host" || fail '20M Host header differs'
cmp -s -i 88:88 "$SOURCE_9992/persistent.pre.host" \
  "$OUT/qualified-differential/9992m/persistent.pre.host" || fail '20M Host non-model state differs'

# The handoff itself starts strictly from the independently authenticated O9992
# source. Never claim that the differential output replaces this zero-clock step.
AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM=FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_9992M \
  "$new_sim" migrate-v5-15b "$fw" "$dtb" "$sd" "$SOURCE_9992/persistent" \
    "$SOURCE_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
    "$TO" 0 "$old_sim" "$OUT/qualified/9992m/persistent" \
    > "$OUT/migrate-9992.log" 2>&1 || fail 'authentic O9992 zero-cycle migration failed'
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V5_15B_ZERO_STEP_PASS' "$OUT/migrate-9992.log" ||
  fail 'zero-cycle v5 O9992 terminal marker missing'
cmp -s "$SOURCE_9992/persistent.pre.rtl" \
  "$OUT/qualified/9992m/persistent.pre.rtl" || fail 'terminal zero-cycle RTL state changed'
cmp -s -n 24 "$SOURCE_9992/persistent.pre.host" \
  "$OUT/qualified/9992m/persistent.pre.host" || fail 'terminal Host header changed'
cmp -s -i 88:88 "$SOURCE_9992/persistent.pre.host" \
  "$OUT/qualified/9992m/persistent.pre.host" || fail 'terminal Host bytes beyond model SHA changed'
for kind in host rtl; do
  cmp -s "$OUT/qualified-differential/9992m/persistent.pre.$kind" \
    "$OUT/qualified/9992m/persistent.pre.$kind" ||
    fail 'differential 9992M and authentic zero-cycle terminal differ'
done

# AETSDCP1 carries the entire 256MiB DDR and SD COW sectors in Host, while
# Verilator saves the exact Product RTL separately. Byte comparison above
# establishes the complete Host state excluding only its 64-byte model ID.
# Decode and SHA-verify the embedded ACSDSP01 SD card envelope independently
# on BOTH destination paths, including its COW sector count and full body hash.
python3 - "$SOURCE_9992/persistent.pre.host" \
  "$OUT/qualified-differential/9992m/persistent.pre.host" \
  "$OUT/qualified/9992m/persistent.pre.host" "$SD_SHA" <<'PY' ||
  fail 'full SD COW/card state differs or fails its embedded body digest'
import sys
from pathlib import Path
sys.path.insert(0,"tools/ci")
from product_sd1_card_snapshot import card_state_from_host
expected_media = sys.argv[4]
states = [card_state_from_host(Path(path)) for path in sys.argv[1:4]]
if any(state["sd_media_sha256"] != expected_media for state in states):
    raise SystemExit("V5_CARD_REJECTED: signed media identity differs")
if any(state != states[0] for state in states[1:]):
    raise SystemExit("V5_CARD_REJECTED: SD protocol/COW body differs")
print("PRODUCT_SD1_V5_DIFF_CARD_COW_BYTES_EQUIVALENT "
      f"sd_card_body_sha256={states[0]['sd_card_body_sha256']} "
      f"cow_sectors={states[0]['sd_cow_count']}")
PY

# The official O9992 JSON describes the identical saved Host/RTL/card bytes
# above; reusing it is safe only AFTER the independent COW body verification.
cp -- "$SOURCE_9992/card-progress.json" \
  "$OUT/qualified-differential/9992m/card-progress.json"
[[ "$(sha "$OUT/qualified-differential/9992m/card-progress.json")" == \
   "$AUTHENTIC_9992_CARD_SHA" ]] ||
  fail 'differential 9992M card-progress SHA differs from independently authenticated O9992'
cmp -s "$SOURCE_9992/card-progress.json" \
  "$OUT/qualified-differential/9992m/card-progress.json" ||
  fail 'differential 9992M card-progress JSON differs byte-for-byte from official O9992'

# Identity-only transition is now proven on the historical 20M interval.
# The exact same source's card evidence is valid because the serialized state
# and replay trace are byte-identical; retain the signed JSON under its SHA.
cp -- "$SOURCE_9992/card-progress.json" "$OUT/qualified/9992m/card-progress.json"
ready="$OUT/qualified/9992m/.migration-ready"
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
  echo "snapshot_host_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.rtl")"
  echo "source_host_sha256=$(sha "$OUT/qualified/9972m/persistent.pre.host")"
  echo "source_rtl_sha256=$(sha "$OUT/qualified/9972m/persistent.pre.rtl")"
  echo "card_progress_sha256=$(sha "$OUT/qualified/9992m/card-progress.json")"
  echo 'debian_login_claim=0'
} > "$ready"
{
  echo 'PRODUCT_SD1_MODEL_MIGRATION_V5: status=QUALIFIED_HOST_ONLY_9972_TO_9992_20M_DIFF_NOT_10B_CROSS'
  echo "previous_model_sha256=$SOURCE_SHA"
  echo "candidate_model_sha256=$new_sha"
  echo "generated_rtl_sha256=$old_generated"
  echo "generated_rtl_objects_sha256=$old_objects"
  echo "old_9972_host_sha256=$(sha "$SOURCE_9972/persistent.pre.host")"
  echo "old_9992_host_sha256=$(sha "$SOURCE_9992/persistent.pre.host")"
  echo "candidate_9992_host_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.host")"
  echo "candidate_9992_rtl_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.rtl")"
  echo "qualified_differential_card_progress_sha256=$(sha "$OUT/qualified-differential/9992m/card-progress.json")"
  echo "zero_cycle_terminal_9992_host_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.host")"
  echo "zero_cycle_terminal_9992_rtl_sha256=$(sha "$OUT/qualified/9992m/persistent.pre.rtl")"
  echo 'replay_cycles=20000000'
  echo 'full_rtl_identical=1'
  echo 'full_host_except_model_sha_identical=1'
  echo 'two_path_9992_host_rtl_identical=1'
  echo 'two_path_9992_sd_cow_body_identical=1'
  echo 'differential_card_progress_official_bytes_identical=1'
  echo 'terminal_migration_guest_cycles=0'
  echo 'debian_login_claim=0'
  echo 'cross_10b_executed=0'
} > "$OUT/qualification-manifest.txt"
echo 'PRODUCT_SD1_MODEL_MIGRATION_V5_HOST_ONLY_TWO_PATH_9992_PASS_NOT_10B_CROSS_OR_DEBIAN_PASS'
