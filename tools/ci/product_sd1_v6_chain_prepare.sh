#!/usr/bin/env bash
# INERT until V5 14992M artifact is independently pinned and V6 CI source approved.
# One runner per GitHub Actions `needs` job; never cold-replay older guest cycles.
set -euo pipefail
fail() { echo "PRODUCT_SD1_V6_CHAIN_PREPARE_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
hex64='^[a-f0-9]{64}$'
[[ "$GITHUB_REPOSITORY" == yituanxing/minicore &&
   "$GITHUB_EVENT_NAME" == pull_request &&
   "$V6_SOURCE_SHA" =~ ^[a-f0-9]{40}$ &&
   "$(git rev-parse HEAD)" == "$V6_SOURCE_SHA" ]] || fail 'reviewed PR source identity changed'
[[ "$V6_BATCH_START_M" =~ ^[0-9]+$ && "$V6_BATCH_END_M" =~ ^[0-9]+$ &&
   "$V6_BATCH_START_M" -ge 14992 && "$V6_BATCH_END_M" -le 19992 &&
   "$((V6_BATCH_END_M-V6_BATCH_START_M))" -eq 1000 &&
   "$((V6_BATCH_START_M-14992))" -ge 0 &&
   "$(((V6_BATCH_START_M-14992)%1000))" -eq 0 ]] || fail 'outside separately reviewed V6 five 1B batches'
[[ "$V6_SOURCE_MODE" == cross_run || "$V6_SOURCE_MODE" == intra_run ]] || fail 'source mode not reviewed'
for var in V6_QUAL_DIGEST V6_QUAL_MANIFEST_SHA V6_MODEL_SHA V6_SOURCE_HOST_SHA V6_SOURCE_RTL_SHA V6_SOURCE_CARD_SHA; do
  [[ "${!var}" =~ $hex64 ]] || fail "$var not qualified SHA"
done
for var in V6_QUAL_ID V6_QUAL_RUN; do
  [[ "${!var}" =~ ^[0-9]{8,13}$ ]] || fail "$var not pinned"
done
[[ "$V6_QUAL_HEAD" =~ ^[a-f0-9]{40}$ && -n "$V6_QUAL_NAME" ]] || fail 'qualification provenance absent'
[[ "$V6_SOURCE_ID" =~ ^[0-9]+$ && "$V6_SOURCE_DIGEST" =~ $hex64 ]] ||
  fail 'previous stage artifact id/digest missing'
if [[ "$V6_SOURCE_MODE" == cross_run ]]; then
  [[ "$V6_BATCH_START_M" == 14992 &&
     "$V6_ORIGIN_RUN" == 42600415630 &&
     "$V6_ORIGIN_HEAD" == 8a40835c470d059448a4871bafead04cc555bee5 &&
     "$V6_SOURCE_NAME" =~ ^product-sd1-v5-15b-42600415630-attempt[1-9][0-9]*-v5_billion_5-14992m$ ]] ||
       fail 'V5 terminal 14992M cross-run source is not explicitly authenticated'
  source_run="$V6_ORIGIN_RUN"
  source_head="$V6_ORIGIN_HEAD"
else
  [[ "$V6_SOURCE_NAME" =~ ^product-sd1-v6-20b- && "$V6_BATCH_START_M" -gt 14992 ]] ||
    fail 'intra-run predecessor not pinned'
  source_run="$GITHUB_RUN_ID"
  source_head="$V6_SOURCE_SHA"
fi

mkdir -p "$RUNNER_TEMP/v6-source-archives"
download_verified() {
  local id="$1" name="$2" run="$3" head="$4" expected="$5" dest="$6" label="$7"
  local meta="$RUNNER_TEMP/v6-source-archives/$label.json"
  local zipped="$RUNNER_TEMP/v6-source-archives/$label.zip"
  [[ ! -e "$dest" ]] || fail "$label destination exists"
  local visible=0 delay
  for delay in 0 2 3 5 8 13 21 30; do
    if (( delay > 0 )); then sleep "$delay"; fi
    if gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id" > "$meta"; then
      visible=1
      break
    fi
  done
  [[ "$visible" == 1 ]] || fail "$label remote artifact metadata unavailable"
  jq -e --argjson id "$id" --argjson run "$run" --arg name "$name" \
    --arg digest "sha256:$expected" --arg head "$head" '
    .id==$id and .name==$name and .workflow_run.id==$run and
    .workflow_run.head_sha==$head and .digest==$digest and
    .expired==false and .size_in_bytes>1024' "$meta" >/dev/null || fail "$label remote digest/provenance drift"
  local fetched=0
  for delay in 0 2 3 5 8 13 21 30; do
    if (( delay > 0 )); then sleep "$delay"; fi
    if curl -fsSL --retry 2 -H "Authorization: Bearer $GH_TOKEN" \
      "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" -o "$zipped"; then
      fetched=1
      break
    fi
  done
  [[ "$fetched" == 1 ]] || fail "$label remote artifact ZIP unavailable"
  python3 tools/ci/product_sd1_v6_chain.py verify-zip "$zipped" "$expected" >/dev/null
  # No blind unzip of remote content. Reject absolute/traversal names and symlinks.
  python3 - "$zipped" <<'PY'
import stat,sys,zipfile
from pathlib import PurePosixPath
with zipfile.ZipFile(sys.argv[1]) as z:
  for info in z.infolist():
    p=PurePosixPath(info.filename)
    mode=info.external_attr >> 16
    if (p.is_absolute() or '..' in p.parts or not p.parts or
        any(part in ('', '.') for part in p.parts) or stat.S_ISLNK(mode)):
      raise SystemExit('V6 source ZIP path/symlink rejected')
PY
  mkdir -p "$dest"
  unzip -qq "$zipped" -d "$dest"
}

QUAL_DIR="$GITHUB_WORKSPACE/build/product-sd1-model-migration-v6-20b-qualified"
download_verified "$V6_QUAL_ID" "$V6_QUAL_NAME" "$V6_QUAL_RUN" "$V6_QUAL_HEAD" \
  "$V6_QUAL_DIGEST" "$QUAL_DIR" qualified
qual="$QUAL_DIR/qualification-manifest.txt"
[[ -s "$qual" && "$(sha "$qual")" == "$V6_QUAL_MANIFEST_SHA" ]] || fail 'V6 qualification manifest drift'
for clause in 'full_rtl_identical=1' 'full_host_except_model_sha_identical=1' \
 'two_path_14972_host_rtl_identical=1' 'terminal_migration_guest_cycles=0' \
 'two_path_14972_sd_cow_body_identical=1' \
 'cross_15b_executed=0' 'debian_login_claim=0' \
 "candidate_model_sha256=$V6_MODEL_SHA" \
 'previous_model_sha256=4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be'; do
  grep -Fxq "$clause" "$qual" || fail "V6 qualification missing $clause"
done
model="$QUAL_DIR/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -s "$model" && "$(sha "$model")" == "$V6_MODEL_SHA" ]] || fail 'V6 executable SHA drift'
chmod 755 "$model"
stage_root="$GITHUB_WORKSPACE/build/product-sd1-v6-chain-${GITHUB_JOB}"
mkdir -p "$stage_root"
first="$GITHUB_WORKSPACE/build/product-sd1-v6-source/${V6_BATCH_START_M}m"
mkdir -p "$(dirname "$first")"
download_verified "$V6_SOURCE_ID" "$V6_SOURCE_NAME" "$source_run" \
  "$source_head" "$V6_SOURCE_DIGEST" "$first" predecessor
source_dir="$first"
source_host="$V6_SOURCE_HOST_SHA"
source_rtl="$V6_SOURCE_RTL_SHA"
source_card="$V6_SOURCE_CARD_SHA"
if [[ "$V6_SOURCE_MODE" == cross_run ]]; then
  # The original 14992M ZIP is signed V5 state, NOT a V6 state. Authenticate
  # it first, then use the separately qualified zero-clock V5->V6 migration.
  [[ "$(field old_14992_host_sha256 "$qual")" == "$V6_SOURCE_HOST_SHA" &&
     "$(field candidate_14992_card_sha256 "$qual")" == "$V6_SOURCE_CARD_SHA" ]] ||
    fail 'V6 qualification did not bind exact official V5 14992M source'
  python3 - "$first" "$V6_SOURCE_HOST_SHA" "$V6_SOURCE_RTL_SHA" \
    "$V6_SOURCE_CARD_SHA" <<'PY'
import sys
sys.path.insert(0, 'tools/ci')
from product_sd1_v6_chain import verify_checkpoint,manifest
folder,host,rtl,card=sys.argv[1:]
verify_checkpoint(folder,14992000000,{'host':host,'rtl':rtl,'card':card},proof_file=True)
if manifest(folder+'/.migration-ready').get('model_sha256') != '4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be':
    raise SystemExit('official V5 14992M model SHA drift')
PY
  source_dir="$QUAL_DIR/qualified/14992m"
  source_host="$(field candidate_14992_host_sha256 "$qual")"
  source_rtl="$(field candidate_14992_rtl_sha256 "$qual")"
  source_card="$(field candidate_14992_card_sha256 "$qual")"
  [[ "$source_host" =~ $hex64 && "$source_rtl" =~ $hex64 && "$source_card" == "$V6_SOURCE_CARD_SHA" &&
     -s "$source_dir/.migration-ready" &&
     "$(field previous_model_sha256 "$source_dir/.migration-ready")" == 4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be &&
     "$(field migration_origin_host_sha256 "$source_dir/.migration-ready")" == "$V6_SOURCE_HOST_SHA" &&
     "$(field migration_origin_rtl_sha256 "$source_dir/.migration-ready")" == "$V6_SOURCE_RTL_SHA" &&
     "$(field model_sha256 "$source_dir/.migration-ready")" == "$V6_MODEL_SHA" ]] ||
    fail 'qualified 14992M V6 model/old-V5 lineage mismatch'
  cmp -s "$first/persistent.pre.rtl" "$source_dir/persistent.pre.rtl" ||
    fail '14992M migration changed Product RTL bytes'
  cmp -s -n 24 "$first/persistent.pre.host" "$source_dir/persistent.pre.host" ||
    fail '14992M migration changed Host header'
  cmp -s -i 88:88 "$first/persistent.pre.host" "$source_dir/persistent.pre.host" ||
    fail '14992M migration changed Host bytes other than model SHA'
fi
python3 - "$source_dir" "$V6_BATCH_START_M" "$source_host" \
  "$source_rtl" "$source_card" "$V6_MODEL_SHA" \
  "$RUNNER_TEMP/v6-source-login.flag" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_v6_chain import verify_checkpoint
folder,start,host,rtl,card,model,flag=sys.argv[1:]
e=verify_checkpoint(folder,int(start)*1000000,
    {'host':host,'rtl':rtl,'card':card},proof_file=True)
from product_sd1_v6_chain import manifest
if manifest(folder+'/.migration-ready').get('model_sha256')!=model:
  raise SystemExit('V6 source model identity mismatch')
Path(flag).write_text('1' if e['proof']['ordered_service_login_verified'] else '0')
PY
[[ -f "build/rv64-debian-rootfs/result.txt" &&
   -f "build/rv64-debian-sd1-sd/sd-rootfs.img" &&
   -f "build/product-sd1-gpio-cs-diagnostic/linux/obj/arch/riscv/boot/Image" &&
   -d "build/product-sd1-spi-polling-import" ]] || fail 'signed media caches or polling firmware missing'
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null || fail 'signed Debian seed invalid'

# Re-runs of a failed GitHub job retain artifacts from earlier run attempts.
# Authenticate EVERY contiguous prior-stage archive, including ZIP digest,
# full Host/RTL/card SHA and predecessor lineage, before skipping guest cycles.
python3 tools/ci/product_sd1_v6_chain.py recover "$stage_root" \
  "$V6_BATCH_START_M" "$V6_BATCH_END_M" "$source_dir" \
  "$source_host" "$source_rtl" "$source_card" \
  "$V6_MODEL_SHA" "$GITHUB_RUN_ID" "$V6_SOURCE_SHA" "$GITHUB_JOB" \
  > "$RUNNER_TEMP/v6-recovery.json" || fail 'failed-run checkpoint recovery invalid'
if [[ "$(cat "$RUNNER_TEMP/v6-source-login.flag")" == 1 &&
      "$(jq -r '.recovered_stages' "$RUNNER_TEMP/v6-recovery.json")" != 0 ]]; then
  fail 'guest login was already observed at source; later guest cycles prohibited'
fi
current_m="$(jq -r '.current_m' "$RUNNER_TEMP/v6-recovery.json")"
current_dir="$(jq -r '.source_dir' "$RUNNER_TEMP/v6-recovery.json")"
current_host="$(jq -r '.last_sha.host' "$RUNNER_TEMP/v6-recovery.json")"
current_rtl="$(jq -r '.last_sha.rtl' "$RUNNER_TEMP/v6-recovery.json")"
current_card="$(jq -r '.last_sha.card' "$RUNNER_TEMP/v6-recovery.json")"
recovered_id="$(jq -r '.last_artifact[0] // empty' "$RUNNER_TEMP/v6-recovery.json")"
recovered_digest="$(jq -r '.last_artifact[1] // empty' "$RUNNER_TEMP/v6-recovery.json")"
recovered_name="$(jq -r '.last_artifact[2] // empty' "$RUNNER_TEMP/v6-recovery.json")"
if [[ -n "$recovered_id" ]]; then
  terminal_id="$recovered_id" terminal_digest="$recovered_digest" terminal_name="$recovered_name"
else
  terminal_id="$V6_SOURCE_ID" terminal_digest="$V6_SOURCE_DIGEST" terminal_name="$V6_SOURCE_NAME"
fi
if [[ "$(jq -r '.stopped' "$RUNNER_TEMP/v6-recovery.json")" == true ]]; then
  stopped=1
else
  stopped="$(cat "$RUNNER_TEMP/v6-source-login.flag")"
fi

{
  echo "V6_SOURCE_DIR=$current_dir"
  echo "V6_STAGE_ROOT=$stage_root"
  echo "V6_CURRENT_M=$current_m"
  echo "V6_LAST_HOST_SHA=$current_host"
  echo "V6_LAST_RTL_SHA=$current_rtl"
  echo "V6_LAST_CARD_SHA=$current_card"
  echo "V6_LAST_ARTIFACT_ID=$terminal_id"
  echo "V6_LAST_ARTIFACT_DIGEST=$terminal_digest"
  echo "V6_LAST_ARTIFACT_NAME=$terminal_name"
  echo "V6_STOP=$stopped"
} >> "$GITHUB_ENV"
echo "PRODUCT_SD1_V6_20B_SOURCE_VERIFIED_RECOVERED_STAGES=$(jq -r '.recovered_stages' "$RUNNER_TEMP/v6-recovery.json")"
