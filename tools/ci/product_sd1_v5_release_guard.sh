#!/usr/bin/env bash
# Exact one-release-commit GitHub Actions gate. Never embed the final event
# HEAD SHA in its own workflow file: that is an impossible git hash fixed point.
set -euo pipefail
fail() { echo "PRODUCT_SD1_V5_RELEASE_GATE_REJECTED: $*" >&2; exit 16; }
[[ "${GITHUB_REPOSITORY:-}" == yituanxing/minicore &&
   "${GITHUB_EVENT_NAME:-}" == pull_request &&
   "${V5_SOURCE_SHA:-}" =~ ^[a-f0-9]{40}$ &&
   "${V5_REVIEWED_PRIOR_HEAD:-}" =~ ^[a-f0-9]{40}$ &&
   "${V5_QUAL_PRIOR_HEAD:-}" =~ ^[a-f0-9]{40}$ ]] || fail 'unreviewed PR identity/qualification HEAD'
[[ "$(git rev-parse HEAD)" == "$V5_SOURCE_SHA" &&
   "$(git rev-parse HEAD^)" == "$V5_REVIEWED_PRIOR_HEAD" ]] ||
  fail 'PR HEAD must be the exact single release commit on reviewed B'
[[ "$(git rev-list --parents -n 1 HEAD | wc -w)" -eq 2 ]] ||
  fail 'release commit must have exactly one parent'

changed=0
while IFS= read -r -d '' path; do
  case "$path" in
    .github/workflows/product-sd1-v5-15b-needs-9992-to-14992-INERT-DRAFT.yml|\
    .github/workflows/product-sd1-v4-o-9712-to-9992.yml|\
    .github/workflows/product-sd1-v5-9972-to-9992-INERT-DRAFT.yml)
      changed=$((changed+1)) ;;
    *) fail "release changed executable or unrelated file: $path" ;;
  esac
done < <(git diff --name-only -z "$V5_REVIEWED_PRIOR_HEAD" HEAD)
[[ "$changed" -ge 1 ]] || fail 'empty release commit'

old_o='.github/workflows/product-sd1-v4-o-9712-to-9992.yml'
old_qual='.github/workflows/product-sd1-v5-9972-to-9992-INERT-DRAFT.yml'
[[ -f "$old_o" && -f "$old_qual" ]] || fail 'old O and qualification workflows absent'
grep -Fq "github.event.pull_request.head.sha == 'ea42929063907b8c9a5a5c27c972f16bbdb28ff1'" "$old_o" ||
  fail 'historical O enhancement trigger not disarmed'
grep -Fq "github.event.pull_request.head.sha == '$V5_QUAL_PRIOR_HEAD'" "$old_qual" ||
  fail 'V5 qualification enhancement trigger not disarmed'
[[ "$V5_SOURCE_SHA" != ea42929063907b8c9a5a5c27c972f16bbdb28ff1 &&
   "$V5_SOURCE_SHA" != "$V5_QUAL_PRIOR_HEAD" ]] ||
  fail 'release event overlaps prior O or qualification source event'
echo 'PRODUCT_SD1_V5_SINGLE_RELEASE_COMMIT_AND_OLD_TRIGGERS_AUTHENTICATED'
