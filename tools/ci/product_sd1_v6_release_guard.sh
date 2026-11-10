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
# The executor, artifact verifier and signed model path must stay byte-identical
# to the independently reviewed V6 draft.
while IFS= read -r -d '' release_change; do
  [[ "$release_change" == .github/workflows/product-sd1-v6-20b-needs-14992-to-19992-INERT-DRAFT.yml ]] ||
    reject "reviewed V6 release code drift: $release_change"
done < <(git diff --name-only -z "$V6_REVIEWED_PRIOR_HEAD" HEAD)
# Changes relative to official signed source must be *only* newly added V6 CI.
# No changes to RTL, compiler, signed media, V5 helpers or V5 workflows.
while IFS= read -r -d '' changed; do
  case "$changed" in
    .github/workflows/product-sd1-v6-20b-needs-14992-to-19992-INERT-DRAFT.yml|\
    .github/actions/product-sd1-v6-20m/action.yml|\
    .github/actions/product-sd1-v6-batch/action.yml|\
    tools/ci/product_sd1_v6_chain.py|\
    tools/ci/product_sd1_v6_chain_prepare.sh|\
    tools/ci/product_sd1_v6_postcross_20m.sh|\
    tools/ci/product_sd1_v6_release_guard.sh) ;;
    *) reject "unapproved difference from V5 signed source: $changed" ;;
  esac
done < <(git diff --name-only -z "$V6_ORIGIN_HEAD" HEAD)
[[ "${V6_14992_ID:-}" =~ ^[0-9]{8,13}$ &&
   "${V6_14992_NAME:-}" =~ ^product-sd1-v5-15b-42600415630-attempt[1-9][0-9]*-v5_billion_5-14992m$ ]] ||
  reject 'real V5 terminal artifact pin absent'
for key in V6_14992_DIGEST V6_14992_HOST_SHA V6_14992_RTL_SHA V6_14992_CARD_SHA; do
  [[ "${!key:-}" =~ ^[a-f0-9]{64}$ ]] || reject "$key not pinned"
done
echo 'PRODUCT_SD1_V6_REVIEWED_CI_ONLY_EXACT_V5_SOURCE_AND_TERMINAL_PIN_AUTHENTICATED'
