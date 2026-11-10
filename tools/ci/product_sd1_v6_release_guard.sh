#!/usr/bin/env bash
# INERT V6 proposal. Deliberate release requires independent PR/source review.
# This guard never permits a different Product simulator, model, or signed media.
set -euo pipefail
reject() { echo "PRODUCT_SD1_V6_RELEASE_GATE_REJECTED: $*" >&2; exit 16; }
[[ "${GITHUB_REPOSITORY:-}" == yituanxing/minicore &&
   "${GITHUB_EVENT_NAME:-}" == pull_request &&
   "${V6_SOURCE_SHA:-}" =~ ^[a-f0-9]{40}$ &&
   "${V6_ORIGIN_HEAD:-}" == 8a40835c470d059448a4871bafead04cc555bee5 &&
   "${V6_ORIGIN_RUN:-}" == 42600415630 &&
   "${AETHERCORE_SD1_V6_20B_APPROVED:-}" == QUALIFIED_V6_20B_SIGNED_14992_CROSS_RUN &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V6_CHECKPOINT_20B ]] ||
  reject 'exact V5 provenance and independent V6 approval absent'
[[ "$(git rev-parse HEAD)" == "$V6_SOURCE_SHA" ]] || reject 'PR HEAD changed'
git merge-base --is-ancestor "$V6_ORIGIN_HEAD" HEAD ||
  reject 'V6 source does not descend from official V5 identity'
# Independent V6 qualification must fix the reviewed draft as the release
# commit's *immediate* parent. This prevents rearming after later CI edits.
[[ "${V6_REVIEWED_PRIOR_HEAD:-}" =~ ^[a-f0-9]{40}$ &&
   "$(git rev-parse HEAD^)" == "$V6_REVIEWED_PRIOR_HEAD" ]] ||
  reject 'missing V6 reviewed prior HEAD or more than one release commit'
# The single release commit may ONLY fill V6 pins/approval in the workflow.
# The executable Host-only capacity/migration change, its new qualification
# script and all artifact verifiers must stay byte-identical to the separately
# reviewed V6 draft. The release commit can only fill workflow pins.
while IFS= read -r -d '' release_change; do
  [[ "$release_change" == .github/workflows/product-sd1-v6-20b-needs-14992-to-19992-INERT-DRAFT.yml ]] ||
    reject "reviewed V6 release code drift: $release_change"
done < <(git diff --name-only -z "$V6_REVIEWED_PRIOR_HEAD" HEAD)
# Changes relative to official signed source must be *only* newly added V6 CI.
# No changes to generated RTL, compiler, signed media, V5 helpers or V5 jobs.
# Only the reviewed V6 Host-side model migration source is permitted here;
# its new executable SHA must be independently attested by the 20M differential.
while IFS= read -r -d '' changed; do
  case "$changed" in
    .github/workflows/product-sd1-v6-20b-needs-14992-to-19992-INERT-DRAFT.yml|\
    .github/actions/product-sd1-v6-20m/action.yml|\
    .github/actions/product-sd1-v6-batch/action.yml|\
    tools/ci/product_sd1_v6_chain.py|\
    tools/ci/product_sd1_v6_chain_prepare.sh|\
    tools/ci/product_sd1_v6_postcross_20m.sh|\
    tools/ci/product_sd1_v6_release_guard.sh|\
    tools/ci/product_sd1_model_migration_v6_20b_qualify.sh|\
    tests_py/test_product_sd1_model_migration_v6_20b_static.py|\
    .github/workflows/product-sd1-v6-20m-qualification-PR379-CACHE-BRIDGE-INERT-DRAFT.yml|\
    sim/product_rv64_checkpoint_sd1_main.cpp) ;;
    *) reject "unapproved difference from V5 signed source: $changed" ;;
  esac
done < <(git diff --name-only -z "$V6_ORIGIN_HEAD" HEAD)
[[ "${V6_14992_ID:-}" =~ ^[0-9]{8,13}$ &&
   "${V6_14992_NAME:-}" =~ ^product-sd1-v5-15b-42600415630-attempt[1-9][0-9]*-v5_billion_5-14992m$ ]] ||
  reject 'real V5 terminal artifact pin absent'
for key in V6_14992_DIGEST V6_14992_HOST_SHA V6_14992_RTL_SHA V6_14992_CARD_SHA; do
  [[ "${!key:-}" =~ ^[a-f0-9]{64}$ ]] || reject "$key not pinned"
done
[[ "${V6_MODEL_SHA:-}" =~ ^[a-f0-9]{64}$ &&
   "${V6_MODEL_SHA:-}" != 4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be &&
   "${AETHERCORE_SD1_V6_REVIEWED_MODEL_SHA:-}" == "${V6_MODEL_SHA:-}" ]] ||
  reject 'new V6 20B Host-only model digest not independently approved'
# Formal 5B release MUST consume the separately authenticated one-stage V6
# 14992M->15012M technical qualification. A human approval marker alone cannot
# substitute for a real report archive/identity and its sealed source lineage.
[[ "${V6_TECH_QUAL_RUN:-}" =~ ^[0-9]{9,13}$ &&
   "${V6_TECH_QUAL_ID:-}" =~ ^[0-9]{8,13}$ &&
   "${V6_TECH_QUAL_NAME:-}" =~ ^product-sd1-v6-qual-14992-15012-[0-9]+-attempt[1-9][0-9]*$ &&
   "${V6_TECH_QUAL_DIGEST:-}" =~ ^[a-f0-9]{64}$ &&
   "${V6_TECH_QUAL_PR379_HEAD:-}" =~ ^[a-f0-9]{40}$ &&
   "${V6_TECH_QUAL_REVIEWED_HEAD:-}" == "$V6_REVIEWED_PRIOR_HEAD" &&
   "${V6_TECH_QUAL_PR379_HEAD:-}" != "$V6_ORIGIN_HEAD" &&
   "${V6_TECH_QUAL_NAME:-}" == "product-sd1-v6-qual-14992-15012-${V6_TECH_QUAL_RUN}-attempt${V6_TECH_QUAL_ATTEMPT:-}" &&
   "${V6_TECH_QUAL_ATTEMPT:-}" =~ ^[1-9][0-9]*$ ]] ||
  reject 'independent reviewed V6 20M technical report pins absent'
command -v gh >/dev/null && command -v jq >/dev/null && command -v unzip >/dev/null ||
  reject 'qualification artifact verifier tools unavailable'
qual_meta="$(mktemp)" qual_zip="$(mktemp)"
trap 'rm -f -- "$qual_meta" "$qual_zip"' EXIT
visible=0
for delay in 0 2 3 5 8 13 21 30; do
  if (( delay > 0 )); then sleep "$delay"; fi
  if gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$V6_TECH_QUAL_ID" > "$qual_meta"; then
    visible=1
    break
  fi
done
[[ "$visible" == 1 ]] || reject 'V6 technical qualification metadata unavailable'
jq -e --argjson id "$V6_TECH_QUAL_ID" --argjson run "$V6_TECH_QUAL_RUN" \
  --arg name "$V6_TECH_QUAL_NAME" --arg digest "sha256:$V6_TECH_QUAL_DIGEST" \
  --arg head "$V6_TECH_QUAL_PR379_HEAD" '
  .id==$id and .name==$name and .digest==$digest and .workflow_run.id==$run and
  .workflow_run.head_sha==$head and .expired==false and .size_in_bytes>1024
  ' "$qual_meta" >/dev/null || reject 'V6 technical report provenance mismatch'
zip_ok=0
for delay in 0 2 3 5 8 13 21 30; do
  if (( delay > 0 )); then sleep "$delay"; fi
  if gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$V6_TECH_QUAL_ID/zip" > "$qual_zip"; then
    zip_ok=1
    break
  fi
done
[[ "$zip_ok" == 1 && "$(sha256sum "$qual_zip" | cut -d' ' -f1)" == "$V6_TECH_QUAL_DIGEST" ]] ||
  reject 'V6 technical report archive digest mismatch'
qual_manifest="$(unzip -p "$qual_zip" qualification-manifest.txt)" ||
  reject 'V6 technical report manifest absent'
[[ "$(printf '%s\n' "$qual_manifest" | sed -n 's/^schema=//p')" == SD1_V6_14992_15012_TECHNICAL_QUALIFICATION_V1 &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^reviewed_v6_head=//p')" == "$V6_REVIEWED_PRIOR_HEAD" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^event_pr379_head=//p')" == "$V6_TECH_QUAL_PR379_HEAD" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_run=//p')" == "$V6_ORIGIN_RUN" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_head=//p')" == "$V6_ORIGIN_HEAD" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_id=//p')" == "$V6_14992_ID" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_name=//p')" == "$V6_14992_NAME" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_zip_digest=//p')" == "$V6_14992_DIGEST" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_host_sha=//p')" == "$V6_14992_HOST_SHA" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_rtl_sha=//p')" == "$V6_14992_RTL_SHA" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^origin_card_sha=//p')" == "$V6_14992_CARD_SHA" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^model_sha=//p')" == "$V6_MODEL_SHA" &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^signed_no_cold_boot=//p')" == 1 &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^debian_login_claim=//p')" == 0 &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^stage_artifact_id=//p')" =~ ^[0-9]+$ &&
   "$(printf '%s\n' "$qual_manifest" | sed -n 's/^stage_artifact_zip_digest=//p')" =~ ^[a-f0-9]{64}$ ]] ||
  reject 'V6 technical qualification manifest lineage invalid'
[[ -n "${GITHUB_ENV:-}" ]] || reject 'no authenticated runner environment'
echo 'V6_TECH_QUAL_REPORT_VERIFIED=1' >> "$GITHUB_ENV"
echo 'PRODUCT_SD1_V6_REVIEWED_CI_ONLY_EXACT_V5_SOURCE_AND_TERMINAL_PIN_AUTHENTICATED'

