#!/usr/bin/env bash
# PR379-only, fail-closed formal release guard. This file is reviewed separately
# from PR379 and imported by immutable git blob, never taken from a PR payload.
set -euo pipefail
reject() { echo "PRODUCT_SD1_V6_FORMAL_PR379_REJECTED: $*" >&2; exit 16; }
field() { sed -n "s/^$1=//p" | head -1; }
hex64='^[a-f0-9]{64}$'
[[ "${GITHUB_REPOSITORY:-}" == yituanxing/minicore &&
   "${GITHUB_EVENT_NAME:-}" == pull_request &&
   "${V6_SOURCE_SHA:-}" =~ ^[a-f0-9]{40}$ &&
   "${V6_FORMAL_PARENT_HEAD:-}" == d1a13ac76b09e00b0298e69bb95217e5197d235f &&
   "${V6_ORIGIN_HEAD:-}" == 8a40835c470d059448a4871bafead04cc555bee5 &&
   "${V6_ORIGIN_RUN:-}" == 42600415630 &&
   "${V6_TECH_QUAL_EXECUTOR_HEAD:-}" == ffa59df56fa88611eaf727bbd96909fae748ebd2 &&
   "${V6_TECH_QUAL_PR379_HEAD:-}" == d1a13ac76b09e00b0298e69bb95217e5197d235f &&
   "${V6_FORMAL_EXECUTOR_HEAD:-}" =~ ^[a-f0-9]{40}$ &&
   "${V6_FORMAL_EXECUTOR_HEAD:-}" != "$V6_TECH_QUAL_EXECUTOR_HEAD" &&
   "${V6_MODEL_SHA:-}" == 3b22f54ee7017b605c7fafe86ca3e21fcdb7d3945e93e329b10c62fa39806101 &&
   "${AETHERCORE_SD1_V6_REVIEWED_MODEL_SHA:-}" == "$V6_MODEL_SHA" &&
   "${AETHERCORE_SD1_V6_20B_APPROVED:-}" == QUALIFIED_V6_20B_SIGNED_14992_CROSS_RUN &&
   "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY_V6_CHECKPOINT_20B ]] ||
  reject 'separate executor/model/formal approval pins missing'
[[ "$(git rev-parse HEAD)" == "$V6_SOURCE_SHA" &&
   "$(git rev-parse HEAD^)" == "$V6_FORMAL_PARENT_HEAD" ]] ||
  reject 'PR379 formal release is not exactly one commit beyond signed technical qualification'
[[ -f "${GITHUB_EVENT_PATH:-/dev/null}" ]] || reject 'PR event absent'
jq -e --arg sha "$V6_SOURCE_SHA" '
   .action=="labeled" and .number==379 and
   .label.name=="sd1-v6-20b-formal-approved" and
   .pull_request.head.sha==$sha and
   .pull_request.head.repo.full_name=="yituanxing/minicore" and
   .pull_request.head.ref=="design/rv64gc-product-architecture-20261103"
  ' "$GITHUB_EVENT_PATH" >/dev/null || reject 'wrong PR379 label or source branch'
[[ "$(git diff --name-status "$V6_FORMAL_PARENT_HEAD" HEAD)" == $'A\t.github/workflows/product-sd1-v6-20b-needs-15012-to-19992-PR379-INERT-DRAFT.yml' ]] ||
  reject 'formal release commit changed more than a single new workflow'
mapfile -t signed_diff < <(git diff --name-only "$V6_ORIGIN_HEAD" HEAD)
[[ "${#signed_diff[@]}" == 2 &&
   "${signed_diff[0]}" == .github/workflows/product-sd1-v6-20b-needs-15012-to-19992-PR379-INERT-DRAFT.yml &&
   "${signed_diff[1]}" == .github/workflows/product-sd1-v6-20m-qualification-PR379-CACHE-BRIDGE-INERT-DRAFT.yml ]] ||
  reject 'signed V5 product/source changed after V5 completion'
[[ "${V6_QUAL_RUN:-}" == 42772805117 &&
   "${V6_QUAL_ID:-}" == 13742896253 &&
   "${V6_QUAL_HEAD:-}" == "$V6_TECH_QUAL_PR379_HEAD" &&
   "${V6_QUAL_NAME:-}" == product-sd1-v6-20b-host-qual-42772805117-attempt1 &&
   "${V6_QUAL_DIGEST:-}" == d5cc239861ed9176ae0a8a0d0a0650f75ebea384ba29e18a72315edb6c841981 &&
   "${V6_QUAL_MANIFEST_SHA:-}" == feba839c75ec62fd652f3a203310bbb44af414b3438ec220a04fe6f8b09fdb97 &&
   "${V6_TECH_QUAL_RUN:-}" == 42772805117 &&
   "${V6_TECH_QUAL_ATTEMPT:-}" == 1 &&
   "${V6_TECH_QUAL_ID:-}" == 13742299835 &&
   "${V6_TECH_QUAL_NAME:-}" == product-sd1-v6-qual-14992-15012-42772805117-attempt1 &&
   "${V6_TECH_QUAL_DIGEST:-}" == c908e3924657b275482c68c67fba5cf90b2d5fbdc8f0bc3440fcea9598267c25 &&
   "${V6_TECH_TERMINAL_ID:-}" == 13742384494 &&
   "${V6_TECH_TERMINAL_NAME:-}" == product-sd1-v6-qual-15012-checkpoint-42772805117-attempt1 &&
   "${V6_TECH_TERMINAL_DIGEST:-}" == 2e0c038afd89c693c68c3442cbcba3db5d36eacf4b31fc721dc0a96dfbe9d969 ]] ||
  reject 'independent V6 qualification/model/15012 terminal archive pins absent'
[[ "${V6_14992_ID:-}" == 13738787578 &&
   "${V6_14992_HOST_SHA:-}" == ba7e2161c5917f522a6854eb5457e9cff703492bb0ef1c5485cb13f6500a01cb &&
   "${V6_14992_RTL_SHA:-}" == 948ab3d2a7554566ca9cb0070c51eafbecf111eee465ec11a7ecbcb354c57ed9 &&
   "${V6_14992_CARD_SHA:-}" == 3ef8a3c6281ca52fd2c734f7244634de55908d276208b2ef26736b355ff1c4a3 ]] ||
  reject 'official signed V5 predecessor origin drift'
if [[ "${1:-}" == --plan ]]; then
  echo 'PRODUCT_SD1_V6_FORMAL_PR379_PLAN_ONLY 15012_to_19992=249x20M no_guest_cycles=1'
  exit 0
fi
[[ -n "${GH_TOKEN:-}" && -n "${GITHUB_ENV:-}" ]] || reject 'artifact verifier credentials/context missing'
tmp="$(mktemp -d)"
trap 'rm -f -- "$tmp/report.json" "$tmp/report.zip" "$tmp/model.json" "$tmp/terminal.json"; rmdir "$tmp"' EXIT
verify_meta() {
  local id="$1" name="$2" digest="$3" dest="$4"
  gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id" > "$dest" || reject "artifact $id metadata unavailable"
  jq -e --argjson id "$id" --arg name "$name" --arg digest "sha256:$digest" \
     --argjson run "$V6_TECH_QUAL_RUN" --arg head "$V6_TECH_QUAL_PR379_HEAD" '
      .id==$id and .name==$name and .digest==$digest and
      .workflow_run.id==$run and .workflow_run.head_sha==$head and
      .expired==false and .size_in_bytes>1024
    ' "$dest" >/dev/null || reject "artifact $id provenance mismatch"
}
verify_meta "$V6_TECH_QUAL_ID" "$V6_TECH_QUAL_NAME" "$V6_TECH_QUAL_DIGEST" "$tmp/report.json"
verify_meta "$V6_QUAL_ID" "$V6_QUAL_NAME" "$V6_QUAL_DIGEST" "$tmp/model.json"
verify_meta "$V6_TECH_TERMINAL_ID" "$V6_TECH_TERMINAL_NAME" "$V6_TECH_TERMINAL_DIGEST" "$tmp/terminal.json"
curl -fsSL --retry 2 -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/$GITHUB_REPOSITORY/actions/artifacts/$V6_TECH_QUAL_ID/zip" -o "$tmp/report.zip" ||
  reject 'qualification ZIP unavailable'
[[ "$(sha256sum "$tmp/report.zip" | cut -d' ' -f1)" == "$V6_TECH_QUAL_DIGEST" ]] ||
  reject 'qualification ZIP bytes differ from exact audited run'
manifest="$(unzip -p "$tmp/report.zip" qualification-manifest.txt)" || reject 'qualification manifest absent'
expected() {
  local key="$1" value="$2"
  [[ "$(printf '%s\n' "$manifest" | field "$key")" == "$value" ]] ||
    reject "qualification report $key mismatch"
}
expected schema SD1_V6_14992_15012_TECHNICAL_QUALIFICATION_V1
expected reviewed_v6_head "$V6_TECH_QUAL_EXECUTOR_HEAD"
expected event_pr379_head "$V6_TECH_QUAL_PR379_HEAD"
expected origin_run "$V6_ORIGIN_RUN"
expected origin_head "$V6_ORIGIN_HEAD"
expected origin_id "$V6_14992_ID"
expected origin_host_sha "$V6_14992_HOST_SHA"
expected origin_rtl_sha "$V6_14992_RTL_SHA"
expected origin_card_sha "$V6_14992_CARD_SHA"
expected host_model_qual_id "$V6_QUAL_ID"
expected host_model_qual_digest "$V6_QUAL_DIGEST"
expected host_model_qual_manifest_sha "$V6_QUAL_MANIFEST_SHA"
expected model_sha "$V6_MODEL_SHA"
expected host_model_diff_14952_to_14972_20m_verified 1
expected host_model_terminal_14992_zero_guest_cycles 1
expected signed_no_cold_boot 1
expected debian_login_claim 0
expected terminal_90d_artifact_id "$V6_TECH_TERMINAL_ID"
expected terminal_90d_artifact_name "$V6_TECH_TERMINAL_NAME"
expected terminal_90d_artifact_zip_digest "$V6_TECH_TERMINAL_DIGEST"
expected stage_host_sha 5928695d9637e5e54a3b394f4e2aac7669e86063cbe0018578981d3c07336f62
expected stage_rtl_sha 28501616ad4ff83ec93565a6cc43d4793403ce978a17d07964d679d6da23392a
expected stage_card_sha 1f04938348893282bab7771e2cd5b013b749b18a825089827996f0add7a2d18f
echo 'V6_TECH_QUAL_REPORT_VERIFIED=1' >> "$GITHUB_ENV"
echo 'PRODUCT_SD1_V6_FORMAL_AUTHENTICATED_15012_TECH_TERMINAL_NO_DEBIAN_PASS_CLAIM'
