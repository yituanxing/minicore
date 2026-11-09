#!/usr/bin/env bash
# INERT until separately qualified V5 model and pinned full 9992M checkpoint exist.
# One runner per GitHub Actions `needs` job; never cold-replay older guest cycles.
set -euo pipefail
fail() { echo "PRODUCT_SD1_V5_CHAIN_PREPARE_REJECTED: $*" >&2; exit 16; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() { sed -n "s/^$1=//p" "$2" | head -1; }
hex64='^[a-f0-9]{64}$'
[[ "$GITHUB_REPOSITORY" == yituanxing/minicore &&
   "$GITHUB_EVENT_NAME" == pull_request &&
   "$V5_SOURCE_SHA" =~ ^[a-f0-9]{40}$ &&
   "$(git rev-parse HEAD)" == "$V5_SOURCE_SHA" ]] || fail 'reviewed PR source identity changed'
[[ "$V5_BATCH_START_M" =~ ^[0-9]+$ && "$V5_BATCH_END_M" =~ ^[0-9]+$ &&
   "$V5_BATCH_START_M" -ge 9992 && "$V5_BATCH_END_M" -le 14992 &&
   "$((V5_BATCH_END_M-V5_BATCH_START_M))" -eq 1000 &&
   "$((V5_BATCH_START_M-9992))" -ge 0 &&
   "$(((V5_BATCH_START_M-9992)%1000))" -eq 0 ]] || fail 'outside signed five 1B batches'
[[ "$V5_SOURCE_MODE" == qualified || "$V5_SOURCE_MODE" == intra_run ]] || fail 'source mode not reviewed'
for var in V5_QUAL_DIGEST V5_QUAL_MANIFEST_SHA V5_MODEL_SHA V5_SOURCE_HOST_SHA V5_SOURCE_RTL_SHA V5_SOURCE_CARD_SHA; do
  [[ "${!var}" =~ $hex64 ]] || fail "$var not qualified SHA"
done
for var in V5_QUAL_ID V5_QUAL_RUN; do
  [[ "${!var}" =~ ^[0-9]{8,13}$ ]] || fail "$var not pinned"
done
[[ "$V5_QUAL_HEAD" =~ ^[a-f0-9]{40}$ && -n "$V5_QUAL_NAME" ]] || fail 'qualification provenance absent'
if [[ "$V5_SOURCE_MODE" == intra_run ]]; then
  [[ "$V5_SOURCE_ID" =~ ^[0-9]+$ && "$V5_SOURCE_DIGEST" =~ $hex64 &&
     "$V5_SOURCE_NAME" =~ ^product-sd1-v5-15b- ]] || fail 'previous job artifact not pinned'
fi

mkdir -p "$RUNNER_TEMP/v5-source-archives"
download_verified() {
  local id="$1" name="$2" run="$3" head="$4" expected="$5" dest="$6" label="$7"
  local meta="$RUNNER_TEMP/v5-source-archives/$label.json"
  local zipped="$RUNNER_TEMP/v5-source-archives/$label.zip"
  [[ ! -e "$dest" ]] || fail "$label destination exists"
  gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id" > "$meta"
  jq -e --argjson id "$id" --argjson run "$run" --arg name "$name" \
    --arg digest "sha256:$expected" --arg head "$head" '
    .id==$id and .name==$name and .workflow_run.id==$run and
    .workflow_run.head_sha==$head and .digest==$digest and
    .expired==false and .size_in_bytes>1024' "$meta" >/dev/null || fail "$label remote digest/provenance drift"
  curl -fsSL --retry 2 -H "Authorization: Bearer $GH_TOKEN" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" -o "$zipped"
  python3 tools/ci/product_sd1_v5_chain.py verify-zip "$zipped" "$expected" >/dev/null
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
      raise SystemExit('V5 source ZIP path/symlink rejected')
PY
  mkdir -p "$dest"
  unzip -qq "$zipped" -d "$dest"
}

QUAL_DIR="$GITHUB_WORKSPACE/build/product-sd1-model-migration-v5-15b-qualified"
download_verified "$V5_QUAL_ID" "$V5_QUAL_NAME" "$V5_QUAL_RUN" "$V5_QUAL_HEAD" \
  "$V5_QUAL_DIGEST" "$QUAL_DIR" qualified
qual="$QUAL_DIR/qualification-manifest.txt"
[[ -s "$qual" && "$(sha "$qual")" == "$V5_QUAL_MANIFEST_SHA" ]] || fail 'V5 qualification manifest drift'
for clause in 'full_rtl_identical=1' 'full_host_except_model_sha_identical=1' \
 'two_path_9992_host_rtl_identical=1' 'terminal_migration_guest_cycles=0' \
 'two_path_9992_sd_cow_body_identical=1' \
 'differential_card_progress_official_bytes_identical=1' \
 'cross_10b_executed=0' 'debian_login_claim=0' \
 "candidate_model_sha256=$V5_MODEL_SHA" \
 'previous_model_sha256=ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a'; do
  grep -Fxq "$clause" "$qual" || fail "V5 qualification missing $clause"
done
model="$QUAL_DIR/candidate-savable-model/obj/VAetherCoreProductDebianSdRV64SimTop"
[[ -s "$model" && "$(sha "$model")" == "$V5_MODEL_SHA" ]] || fail 'V5 executable SHA drift'
chmod 755 "$model"
stage_root="$GITHUB_WORKSPACE/build/product-sd1-v5-chain-${GITHUB_JOB}"
mkdir -p "$stage_root"
if [[ "$V5_SOURCE_MODE" == qualified ]]; then
  [[ "$V5_BATCH_START_M" == 9992 ]] || fail 'only 9992M may use qualified source'
  first="$QUAL_DIR/qualified/9992m"
else
  first="$GITHUB_WORKSPACE/build/product-sd1-v5-source/${V5_BATCH_START_M}m"
  mkdir -p "$(dirname "$first")"
  download_verified "$V5_SOURCE_ID" "$V5_SOURCE_NAME" "$GITHUB_RUN_ID" \
    "$V5_SOURCE_SHA" "$V5_SOURCE_DIGEST" "$first" predecessor
fi
python3 - "$first" "$V5_BATCH_START_M" "$V5_SOURCE_HOST_SHA" \
  "$V5_SOURCE_RTL_SHA" "$V5_SOURCE_CARD_SHA" "$V5_MODEL_SHA" \
  "$RUNNER_TEMP/v5-source-login.flag" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0,'tools/ci')
from product_sd1_v5_chain import verify_checkpoint
folder,start,host,rtl,card,model,flag=sys.argv[1:]
e=verify_checkpoint(folder,int(start)*1000000,
    {'host':host,'rtl':rtl,'card':card},proof_file=True)
from product_sd1_v5_chain import manifest
if manifest(folder+'/.migration-ready').get('model_sha256')!=model:
  raise SystemExit('V5 source model identity mismatch')
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
python3 tools/ci/product_sd1_v5_chain.py recover "$stage_root" \
  "$V5_BATCH_START_M" "$V5_BATCH_END_M" "$first" \
  "$V5_SOURCE_HOST_SHA" "$V5_SOURCE_RTL_SHA" "$V5_SOURCE_CARD_SHA" \
  "$V5_MODEL_SHA" "$GITHUB_RUN_ID" "$V5_SOURCE_SHA" "$GITHUB_JOB" \
  > "$RUNNER_TEMP/v5-recovery.json" || fail 'failed-run checkpoint recovery invalid'
if [[ "$(cat "$RUNNER_TEMP/v5-source-login.flag")" == 1 &&
      "$(jq -r '.recovered_stages' "$RUNNER_TEMP/v5-recovery.json")" != 0 ]]; then
  fail 'guest login was already observed at source; later guest cycles prohibited'
fi
current_m="$(jq -r '.current_m' "$RUNNER_TEMP/v5-recovery.json")"
current_dir="$(jq -r '.source_dir' "$RUNNER_TEMP/v5-recovery.json")"
current_host="$(jq -r '.last_sha.host' "$RUNNER_TEMP/v5-recovery.json")"
current_rtl="$(jq -r '.last_sha.rtl' "$RUNNER_TEMP/v5-recovery.json")"
current_card="$(jq -r '.last_sha.card' "$RUNNER_TEMP/v5-recovery.json")"
recovered_id="$(jq -r '.last_artifact[0] // empty' "$RUNNER_TEMP/v5-recovery.json")"
recovered_digest="$(jq -r '.last_artifact[1] // empty' "$RUNNER_TEMP/v5-recovery.json")"
recovered_name="$(jq -r '.last_artifact[2] // empty' "$RUNNER_TEMP/v5-recovery.json")"
if [[ -n "$recovered_id" ]]; then
  terminal_id="$recovered_id" terminal_digest="$recovered_digest" terminal_name="$recovered_name"
elif [[ "$V5_SOURCE_MODE" == qualified ]]; then
  terminal_id="$V5_QUAL_ID" terminal_digest="$V5_QUAL_DIGEST" terminal_name="$V5_QUAL_NAME"
else
  terminal_id="$V5_SOURCE_ID" terminal_digest="$V5_SOURCE_DIGEST" terminal_name="$V5_SOURCE_NAME"
fi
if [[ "$(jq -r '.stopped' "$RUNNER_TEMP/v5-recovery.json")" == true ]]; then
  stopped=1
else
  stopped="$(cat "$RUNNER_TEMP/v5-source-login.flag")"
fi

{
  echo "V5_SOURCE_DIR=$current_dir"
  echo "V5_STAGE_ROOT=$stage_root"
  echo "V5_CURRENT_M=$current_m"
  echo "V5_LAST_HOST_SHA=$current_host"
  echo "V5_LAST_RTL_SHA=$current_rtl"
  echo "V5_LAST_CARD_SHA=$current_card"
  echo "V5_LAST_ARTIFACT_ID=$terminal_id"
  echo "V5_LAST_ARTIFACT_DIGEST=$terminal_digest"
  echo "V5_LAST_ARTIFACT_NAME=$terminal_name"
  echo "V5_STOP=$stopped"
} >> "$GITHUB_ENV"
echo "PRODUCT_SD1_V5_15B_SOURCE_VERIFIED_RECOVERED_STAGES=$(jq -r '.recovered_stages' "$RUNNER_TEMP/v5-recovery.json")"
