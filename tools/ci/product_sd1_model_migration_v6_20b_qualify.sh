#!/usr/bin/env bash
# Isolated V6 Host-only model qualification. The original signed V5 checkpoints
# and the V5-qualified executable are immutable inputs, never output targets.
# Real 20M differential is 14952M->14972M, entirely BELOW the old 15B cap.
# The genuine 14992M terminal state is then migrated at ZERO guest clocks.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
fail() { echo "PRODUCT_SD1_V6_MODEL_QUAL_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d ' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }

readonly SOURCE_SHA=4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be
readonly PREVIOUS_SHA=ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a
readonly ORIGINAL_SHA=5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc
readonly FW_SHA=4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be
readonly DTB_SHA=72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c
readonly SD_SHA=6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7
readonly LINUX_SHA=522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393
readonly ROOTFS_SHA=f2f8c6bf118c0be578b633dfd5486e0d1e1ef7becdaf093bbc4f16f62d0e474e
readonly FROM=14952000000 TO=14972000000 TERMINAL=14992000000 STEP=20000000
readonly SOURCE_14952="${AETHERCORE_SD1_V6_SOURCE_14952_DIR:-}"
readonly SOURCE_14972="${AETHERCORE_SD1_V6_SOURCE_14972_DIR:-}"
readonly SOURCE_14992="${AETHERCORE_SD1_V6_SOURCE_14992_DIR:-}"
readonly SOURCE_14952_HOST=aa9f1a371d736279a77026b6f4e3ac41e59b2987700b009e9023efcaa1ef3b8b
readonly SOURCE_14952_RTL=a9bad1f9d0a5eb6dfab455ff28a61a93da4965a1bfb326754cfe497cbc525474
readonly SOURCE_14952_CARD=94c46c5334f432113c7bf13af0ed3920e6550df10be96cdef16267f17cd167aa
readonly SOURCE_14972_HOST=2443bd6cfcd615608b52ff6c8bb0b9c1c0eb547f43c4b51363c71a251c64b4a4
readonly SOURCE_14972_RTL=51c8123f99a45c9b2071871fde8feb04876b6fdd110821f43daeae2c31c3f0b2
readonly SOURCE_14972_CARD=33ad5691453265560cf7c05858923d206be6387e5cc05569f304b947067c779d
readonly SOURCE_14992_HOST=ba7e2161c5917f522a6854eb5457e9cff703492bb0ef1c5485cb13f6500a01cb
readonly SOURCE_14992_RTL=948ab3d2a7554566ca9cb0070c51eafbecf111eee465ec11a7ecbcb354c57ed9
readonly SOURCE_14992_CARD=3ef8a3c6281ca52fd2c734f7244634de55908d276208b2ef26736b355ff1c4a3
readonly OLD_ROOT="$ROOT/build/product-sd1-model-migration-v5-15b-qualified"
readonly OLD_MODEL_DIR="$OLD_ROOT/candidate-savable-model"
readonly OUT="$ROOT/build/product-sd1-model-migration-v6-20b-draft"

if [[ "${1:---plan}" == --plan && $# -le 1 ]]; then
  echo 'PRODUCT_SD1_V6_20B_MODEL_PLAN_ONLY no_guest_cycles=1'
  echo 'Requires authenticated V5 14952M/14972M/14992M, old V5-qualified model, pinned generated Verilator objects and toolchain.'
  echo 'Qualification performs one real 20M under 15B plus two zero-clock SHA-only migrations; NEVER crosses 15B or overwrites V5.'
  exit 0
fi
[[ $# == 1 && "$1" == --execute-qualified-diff ]] || fail 'only --plan or --execute-qualified-diff allowed'
[[ "${AETHERCORE_SD1_V6_MODEL_QUALIFICATION_EXECUTE:-}" == HOST_ONLY_14952_TO_14972_REAL_RTL_REVIEWED &&
   "${AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM:-}" == FROZEN_SIGNED_SD1_MODEL_MIGRATE_V6_20B_DIFF_14952M &&
   "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
   "${SIM_SETTLE_MODE:-}" == reference && "${VERILATOR_THREADS:-}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] ||
  fail 'independently reviewed V6 Host-only differential permission absent'
[[ "$(uname -m)" == x86_64 &&
   "$(c++ --version | head -1 | sha256sum | cut -c1-20)" == 89e4c31da3dafc5a664e ]] ||
  fail 'frozen C++ compiler ABI differs'
[[ ! -e "$OUT" ]] || fail 'candidate output already exists'

verify_source() {
  local dir="$1" label="$2" cycles="$3" prev="$4" host="$5" rtl="$6" card="$7" ready
  [[ "$dir" == /* && -d "$dir" && "$(realpath -e "$dir")" == "$dir" &&
     "$(basename "$dir")" == "${label}m" ]] || fail "noncanonical ${label}M source"
  ready="$dir/.migration-ready"
  [[ -s "$ready" && ! -L "$ready" &&
     "$(field schema "$ready")" == AETSDCP1 &&
     "$(field qualification "$ready")" == PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS &&
     "$(field boot_cycles "$ready")" == "$cycles" &&
     "$(field source_cycles "$ready")" == "$prev" &&
     "$(field model_sha256 "$ready")" == "$SOURCE_SHA" &&
     "$(field previous_model_sha256 "$ready")" == "$PREVIOUS_SHA" &&
     "$(field original_model_sha256 "$ready")" == "$ORIGINAL_SHA" &&
     "$(field firmware_sha256 "$ready")" == "$FW_SHA" &&
     "$(field dtb_sha256 "$ready")" == "$DTB_SHA" &&
     "$(field sd_image_sha256 "$ready")" == "$SD_SHA" &&
     "$(field linux_image_sha256 "$ready")" == "$LINUX_SHA" &&
     "$(field rootfs_result_sha256 "$ready")" == "$ROOTFS_SHA" &&
     "$(field debian_login_claim "$ready")" == 0 ]] || fail "${label}M source seal differs"
  for kind in host rtl; do
    local expected
    if [[ "$kind" == host ]]; then expected="$host"; else expected="$rtl"; fi
    [[ -s "$dir/persistent.pre.$kind" && ! -L "$dir/persistent.pre.$kind" &&
       "$(sha "$dir/persistent.pre.$kind")" == "$expected" &&
       "$(field "snapshot_${kind}_sha256" "$ready")" == "$expected" ]] ||
      fail "${label}M source $kind snapshot SHA differs"
  done
  [[ -s "$dir/card-progress.json" && ! -L "$dir/card-progress.json" &&
     "$(sha "$dir/card-progress.json")" == "$card" &&
     "$(field card_progress_sha256 "$ready")" == "$card" ]] ||
    fail "${label}M source card SHA differs"
}
verify_source "$SOURCE_14952" 14952 "$FROM" 14932000000 "$SOURCE_14952_HOST" "$SOURCE_14952_RTL" "$SOURCE_14952_CARD"
verify_source "$SOURCE_14972" 14972 "$TO" "$FROM" "$SOURCE_14972_HOST" "$SOURCE_14972_RTL" "$SOURCE_14972_CARD"
verify_source "$SOURCE_14992" 14992 "$TERMINAL" "$TO" "$SOURCE_14992_HOST" "$SOURCE_14992_RTL" "$SOURCE_14992_CARD"
[[ "$(field source_host_sha256 "$SOURCE_14972/.migration-ready")" == "$SOURCE_14952_HOST" &&
   "$(field source_rtl_sha256 "$SOURCE_14972/.migration-ready")" == "$SOURCE_14952_RTL" ]] ||
  fail '14952->14972 official V5 state continuity absent'

old_sim="$OLD_MODEL_DIR/obj/VAetherCoreProductDebianSdRV64SimTop"
old_qual="$OLD_ROOT/qualification-manifest.txt"
[[ -x "$old_sim" && ! -L "$old_sim" && "$(sha "$old_sim")" == "$SOURCE_SHA" &&
   "$(field candidate_model_sha256 "$old_qual")" == "$SOURCE_SHA" &&
   "$(field previous_model_sha256 "$old_qual")" == "$PREVIOUS_SHA" &&
   "$(field replay_cycles "$old_qual")" == 20000000 &&
   "$(field full_rtl_identical "$old_qual")" == 1 &&
   "$(field full_host_except_model_sha_identical "$old_qual")" == 1 &&
   "$(field two_path_9992_host_rtl_identical "$old_qual")" == 1 &&
   "$(field two_path_9992_sd_cow_body_identical "$old_qual")" == 1 &&
   "$(field terminal_migration_guest_cycles "$old_qual")" == 0 &&
   "$(field cross_10b_executed "$old_qual")" == 0 &&
   "$(field debian_login_claim "$old_qual")" == 0 ]] ||
  fail 'old V5 9972->9992 qualification absent'

mapfile -d '' manifests < <(find "$ROOT/build/product-sd1-spi-polling-import" -type f -name manifest.txt -print0)
[[ ${#manifests[@]} == 1 ]] || fail 'polling manifest ambiguous'
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
  fail 'signed firmware/DTB/SD/Linux/rootfs differs'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null || fail 'official media seed invalid'

generated_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \( -name 'VAetherCoreProductDebianSdRV64SimTop*.cpp' -o -name 'VAetherCoreProductDebianSdRV64SimTop*.h' -o -name 'VAetherCoreProductDebianSdRV64SimTop*.mk' \) -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f1)
}
generated_object_hash() {
  (cd "$1/obj" && find . -maxdepth 1 -type f \( -name 'VAetherCoreProductDebianSdRV64SimTop*.o' -o -name 'VAetherCoreProductDebianSdRV64SimTop*.a' \) -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f1)
}
old_generated="$(generated_hash "$OLD_MODEL_DIR")"
old_objects="$(generated_object_hash "$OLD_MODEL_DIR")"
[[ "$old_generated" == "$(field generated_rtl_sha256 "$old_qual")" &&
   "$old_objects" == "$(field generated_rtl_objects_sha256 "$old_qual")" ]] ||
  fail 'old RTL/generated object hashes not authenticated'

mkdir -p "$OUT"
candidate="$OUT/candidate-savable-model"
cp -a "$OLD_MODEL_DIR" "$candidate"
obj="$candidate/obj"
new_sim="$obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -f "$obj/product_rv64_checkpoint_sd1_main.o" && -x "$new_sim" ]] ||
  fail 'old Host-only object not found'
rm -- "$obj/product_rv64_checkpoint_sd1_main.o" "$new_sim"
verilator_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/verilator-5.054"
[[ -x "$verilator_root/bin/verilator" && -f "$verilator_root/include/verilated.mk" &&
   "$(< "$verilator_root/.aethercore-source-revision")" == bbb60a403932a9f695cb6ff001ceb73de6ad9234 ]] ||
  fail 'pinned Verilator 5.054 ABI absent'
export PATH="$verilator_root/bin:$PATH" VERILATOR_ROOT="$verilator_root"
timeout --signal=TERM --kill-after=20s 25m \
  make -C "$obj" -f VAetherCoreProductDebianSdRV64SimTop.mk -j "${JOBS:-4}" \
  > "$OUT/host-recompile.log" 2>&1 || fail 'Host-only candidate compilation failed'
[[ -x "$new_sim" && "$(sha "$new_sim")" != "$SOURCE_SHA" &&
   "$(sha "$old_sim")" == "$SOURCE_SHA" &&
   "$(generated_hash "$candidate")" == "$old_generated" &&
   "$(generated_object_hash "$candidate")" == "$old_objects" ]] ||
  fail 'V6 candidate altered RTL/generated objects or source model'
new_sha="$(sha "$new_sim")"

mkdir -p "$OUT/qualified"
"$new_sim" migrate-v6-20b "$fw" "$dtb" "$sd" "$SOURCE_14952/persistent" \
  "$SOURCE_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
  "$FROM" 0 "$old_sim" "$OUT/qualified/14952m/persistent" \
  > "$OUT/migrate-14952.log" 2>&1 || fail 'zero-cycle 14952M model migration failed'
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V6_20B_ZERO_STEP_PASS' "$OUT/migrate-14952.log" ||
  fail '14952M migration marker absent'
cmp -s "$SOURCE_14952/persistent.pre.rtl" "$OUT/qualified/14952m/persistent.pre.rtl" ||
  fail '14952M RTL bytes changed by model migration'
cmp -s -n 24 "$SOURCE_14952/persistent.pre.host" "$OUT/qualified/14952m/persistent.pre.host" ||
  fail '14952M Host header changed'
cmp -s -i 88:88 "$SOURCE_14952/persistent.pre.host" "$OUT/qualified/14952m/persistent.pre.host" ||
  fail '14952M Host non-model bytes changed'

mkdir -p "$OUT/qualified-differential/14972m"
AETHERCORE_SD1_RELAY_CONFIRM=SD1_SIGNED_V6_14992_TO_15012_TECH_TRIAL_ONLY \
  timeout --signal=TERM --kill-after=12s 20m \
  "$new_sim" advance "$fw" "$dtb" "$sd" "$OUT/qualified/14952m/persistent" \
    "$new_sha" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
    "$FROM" "$STEP" "$OUT/qualified-differential/14972m/persistent" \
  > "$OUT/advance-14972.log" 2>&1 || fail 'new V6 Host 14952->14972 real 20M failed'
grep -Fq "PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=$FROM new_cycles=$TO delta=$STEP " \
  "$OUT/advance-14972.log" || fail 'real RTL differential completion absent'
cmp -s "$SOURCE_14972/persistent.pre.rtl" "$OUT/qualified-differential/14972m/persistent.pre.rtl" ||
  fail '14952->14972 RTL bytes differ from authentic V5'
cmp -s -n 24 "$SOURCE_14972/persistent.pre.host" "$OUT/qualified-differential/14972m/persistent.pre.host" ||
  fail '14972M Host header changed'
cmp -s -i 88:88 "$SOURCE_14972/persistent.pre.host" "$OUT/qualified-differential/14972m/persistent.pre.host" ||
  fail '14952->14972 Host non-model state differs'

AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM=FROZEN_SIGNED_SD1_MODEL_MIGRATE_V6_20B_14992M \
  "$new_sim" migrate-v6-20b "$fw" "$dtb" "$sd" "$SOURCE_14992/persistent" \
    "$SOURCE_SHA" "$FW_SHA" "$DTB_SHA" "$SD_SHA" "$LINUX_SHA" "$ROOTFS_SHA" \
    "$TERMINAL" 0 "$old_sim" "$OUT/qualified/14992m/persistent" \
    > "$OUT/migrate-14992.log" 2>&1 || fail 'authentic 14992M zero-clock migration failed'
grep -Fq 'PRODUCT_SD1_MODEL_MIGRATE_V6_20B_ZERO_STEP_PASS' "$OUT/migrate-14992.log" ||
  fail '14992M zero-clock migration marker absent'
cmp -s "$SOURCE_14992/persistent.pre.rtl" "$OUT/qualified/14992m/persistent.pre.rtl" ||
  fail '14992M terminal RTL bytes changed'
cmp -s -n 24 "$SOURCE_14992/persistent.pre.host" "$OUT/qualified/14992m/persistent.pre.host" ||
  fail '14992M Host header changed'
cmp -s -i 88:88 "$SOURCE_14992/persistent.pre.host" "$OUT/qualified/14992m/persistent.pre.host" ||
  fail '14992M Host non-model bytes changed'

python3 - "$SOURCE_14952/persistent.pre.host" "$SOURCE_14972/persistent.pre.host" \
  "$OUT/qualified-differential/14972m/persistent.pre.host" \
  "$SOURCE_14992/persistent.pre.host" "$OUT/qualified/14992m/persistent.pre.host" "$SD_SHA" <<'PY' ||
  fail 'SD COW/card bodies or media identities differ'
import sys
from pathlib import Path
sys.path.insert(0, 'tools/ci')
from product_sd1_card_snapshot import card_state_from_host
a,b,c,d,e=(card_state_from_host(Path(p)) for p in sys.argv[1:6])
if any(x['sd_media_sha256'] != sys.argv[6] for x in (a,b,c,d,e)):
    raise SystemExit('V6_CARD_REJECTED: SD media identity drift')
if b != c or d != e:
    raise SystemExit('V6_CARD_REJECTED: 20M or 14992M migration changed SD state')
print('PRODUCT_SD1_V6_SD_COW_FULL_BODY_EQUIVALENT')
PY
cp -- "$SOURCE_14972/card-progress.json" "$OUT/qualified-differential/14972m/card-progress.json"
cp -- "$SOURCE_14992/card-progress.json" "$OUT/qualified/14992m/card-progress.json"
ready="$OUT/qualified/14992m/.migration-ready"
{
  echo 'qualification=PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS'
  echo 'schema=AETSDCP1'
  echo "boot_cycles=$TERMINAL"
  echo "source_cycles=$TO"
  echo "model_sha256=$new_sha"
  echo "previous_model_sha256=$SOURCE_SHA"
  echo "original_model_sha256=$ORIGINAL_SHA"
  echo "firmware_sha256=$FW_SHA"
  echo "dtb_sha256=$DTB_SHA"
  echo "sd_image_sha256=$SD_SHA"
  echo "linux_image_sha256=$LINUX_SHA"
  echo "rootfs_result_sha256=$ROOTFS_SHA"
  echo "snapshot_host_sha256=$(sha "$OUT/qualified/14992m/persistent.pre.host")"
  echo "snapshot_rtl_sha256=$(sha "$OUT/qualified/14992m/persistent.pre.rtl")"
  echo "source_host_sha256=$SOURCE_14992_HOST"
  echo "source_rtl_sha256=$SOURCE_14992_RTL"
  echo "card_progress_sha256=$SOURCE_14992_CARD"
  echo 'debian_login_claim=0'
} > "$ready"
{
  echo 'PRODUCT_SD1_MODEL_MIGRATION_V6: status=QUALIFIED_HOST_ONLY_14952_TO_14972_20M_DIFF_NOT_15B_CROSS'
  echo "previous_model_sha256=$SOURCE_SHA"
  echo "candidate_model_sha256=$new_sha"
  echo "generated_rtl_sha256=$old_generated"
  echo "generated_rtl_objects_sha256=$old_objects"
  echo "old_14952_host_sha256=$SOURCE_14952_HOST"
  echo "old_14972_host_sha256=$SOURCE_14972_HOST"
  echo "old_14992_host_sha256=$SOURCE_14992_HOST"
  echo "candidate_14972_host_sha256=$(sha "$OUT/qualified-differential/14972m/persistent.pre.host")"
  echo "candidate_14972_rtl_sha256=$(sha "$OUT/qualified-differential/14972m/persistent.pre.rtl")"
  echo "candidate_14992_host_sha256=$(sha "$OUT/qualified/14992m/persistent.pre.host")"
  echo "candidate_14992_rtl_sha256=$(sha "$OUT/qualified/14992m/persistent.pre.rtl")"
  echo "candidate_14992_card_sha256=$SOURCE_14992_CARD"
  echo 'replay_cycles=20000000'
  echo 'full_rtl_identical=1'
  echo 'full_host_except_model_sha_identical=1'
  echo 'two_path_14972_host_rtl_identical=1'
  echo 'two_path_14972_sd_cow_body_identical=1'
  echo 'terminal_migration_guest_cycles=0'
  echo 'debian_login_claim=0'
  echo 'cross_15b_executed=0'
} > "$OUT/qualification-manifest.txt"
echo 'PRODUCT_SD1_MODEL_MIGRATION_V6_HOST_ONLY_14952_TO_14972_PASS_NOT_15B_CROSS_OR_DEBIAN_PASS'
