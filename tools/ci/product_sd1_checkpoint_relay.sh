#!/usr/bin/env bash
set -euo pipefail

# Explicit SD1 full DebIan relay, consuming the existing qualified --savable
# SD1 host and independently verifying every 256MiB RTL/DDR/SD prestate.
#
#  AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY \
#  AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
#    bash tools/ci/product_sd1_checkpoint_relay.sh bootstrap
#
#  ... bash tools/ci/product_sd1_checkpoint_relay.sh advance PROOF_DIR NEXT_CYCLE
#  ... bash tools/ci/product_sd1_checkpoint_relay.sh range PROOF_DIR END_CYCLE
#  bash tools/ci/product_sd1_checkpoint_relay.sh verify PROOF_DIR
#
# `range` advances at most 100M cycles per invocation, creating independently
# hashed, recoverable 20M stages. Callers must persist the final raw snapshot
# in trusted, access-controlled storage; public Actions artifacts are NOT an
# approved home for a Debian RAM+SD snapshot or future guest credentials.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in bootstrap|advance|range|verify) ;; *)
  echo 'usage: product_sd1_checkpoint_relay.sh bootstrap|advance PROOF_DIR NEXT_CYCLE|range PROOF_DIR END_CYCLE|verify PROOF_DIR' >&2
  exit 2;;
esac

reject() { echo "PRODUCT_SD1_RELAY_REJECTED: $*" >&2; exit 3; }
sha() { sha256sum -- "$1" | cut -d' ' -f1; }
decimal() { [[ "$1" =~ ^(0|[1-9][0-9]*)$ ]] || reject "invalid decimal integer $1"; }
field() {
  local value
  value="$(sed -n "s/^${2}=//p" "$1")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || reject "missing or duplicate field $2 in $1"
  printf '%s' "$value"
}
inspect() {
  local dir="$1" previous="${2:--1}" line
  line="$(python3 tools/ci/product_sd1_relay_state.py --snapshot "$dir" \
    --minimum-commits "$previous")" || reject "actual SD1 host state failed validation: $dir"
  [[ "$line" == PRODUCT_SD1_RELAY_CHECKPOINT_OK\ \{* ]] || reject 'malformed UART/observer inspector output'
  printf '%s\n' "$line"
}
read_numeric() {
  local line="$1" key="$2"
  python3 -c 'import json,sys; value=json.loads(sys.argv[1].partition(" ")[2])[sys.argv[2]]; assert type(value) is int and value>=0; print(value)' "$line" "$key"
}
read_flag() {
  local line="$1" key="$2"
  python3 -c 'import json,sys; value=json.loads(sys.argv[1].partition(" ")[2])[sys.argv[2]]; assert type(value) is bool; print(int(value))' "$line" "$key"
}

verify() {
  local dir="$1" state manifest="$1/relay-manifest.txt" expected name
  [[ -d "$dir" && -s "$manifest" && -s "$dir/RELAY_COMPLETE" ]] ||
    reject 'missing sealed SD1 relay stage'
  for expected in 'format=sd1-full-rtl-relay-v1' \
                  'top=AetherCoreProductDebianSdRV64SimTop' \
                  'qualification=FULL_STATE_ONLY_NOT_LINUX_BOOT'; do
    grep -Fxq "$expected" "$manifest" || reject "wrong relay schema: $expected"
  done
  (cd "$dir" && sha256sum --check --status RELAY_COMPLETE) ||
    reject 'SD1 relay stage contents changed after sealing'
  state="$(inspect "$dir")"
  [[ "$(field "$manifest" cycles)" == "$(read_numeric "$state" cycles)" &&
     "$(field "$manifest" commits)" == "$(read_numeric "$state" commits)" ]] ||
    reject 'observer differs from sealed stage cycle/commit count'
  echo "PRODUCT_SD1_RELAY_VERIFIED directory=$dir cycles=$(field "$manifest" cycles) commits=$(field "$manifest" commits)"
}

seal() {
  local dir="$1" source="$2" state="$3" cycle commits
  [[ ! -e "$dir/relay-manifest.txt" && ! -e "$dir/RELAY_COMPLETE" ]] ||
    reject 'refusing to overwrite previously sealed relay checkpoint'
  cycle="$(read_numeric "$state" cycles)"
  commits="$(read_numeric "$state" commits)"
  [[ "$commits" -gt 0 ]] || reject 'stage retired no Product instructions'
  {
    printf 'format=sd1-full-rtl-relay-v1\ntop=AetherCoreProductDebianSdRV64SimTop\n'
    printf 'qualification=FULL_STATE_ONLY_NOT_LINUX_BOOT\ncycles=%s\ncommits=%s\n' "$cycle" "$commits"
    printf 'source_cycle=%s\n' "$source"
    printf 'snapshot_host_sha256=%s\nsnapshot_rtl_sha256=%s\n' \
      "$(sha "$dir/persistent.pre.host")" "$(sha "$dir/persistent.pre.rtl")"
    printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
  } > "$dir/relay-manifest.txt.tmp"
  sync -f "$dir/relay-manifest.txt.tmp"
  mv -- "$dir/relay-manifest.txt.tmp" "$dir/relay-manifest.txt"
  (cd "$dir" && sha256sum -- persistent.pre.host persistent.pre.rtl \
     .snapshot-ready relay-manifest.txt > RELAY_COMPLETE.tmp)
  sync -f "$dir/RELAY_COMPLETE.tmp"
  mv -- "$dir/RELAY_COMPLETE.tmp" "$dir/RELAY_COMPLETE"
  verify "$dir" >/dev/null
}

if [[ "$ACTION" == verify ]]; then
  [[ $# == 2 ]] || reject 'verify requires one checkpoint directory'
  verify "$(realpath -e -- "$2")"
  exit 0
fi

[[ "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY &&
   "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-}" == 1 &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${SIM_SETTLE_MODE:-reference}" == reference ]] ||
  reject 'explicit full SD1 savable 5.054/reference relay opt-in required'
[[ $# == 1 || "$ACTION" != bootstrap ]] || reject 'bootstrap takes no checkpoint argument'
if [[ "$ACTION" == bootstrap ]]; then
  [[ $# == 1 ]] || reject 'bootstrap takes no checkpoint argument'
  python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
    reject 'signed official rootfs/media seed is absent or inconsistent'
  output="$(bash tools/ci/product_checkpoint_sd1_short.sh save-only)" ||
    reject 'SD1 cold boot <=20M checkpoint failed'
  dir="$(sed -n 's/^PRODUCT_SD1_PERSISTENT_PROOF_DIR=//p' <<< "$output" | tail -1)"
  [[ -n "$dir" && -d "$dir" ]] || reject 'bounded cold boot did not save a full checkpoint'
  state="$(inspect "$dir" 0)"
  seal "$dir" 0 "$state"
  echo "PRODUCT_SD1_RELAY_BOOTSTRAP_READY directory=$dir $(field "$dir/relay-manifest.txt" cycles) cycles"
  exit 0
fi

[[ $# == 3 ]] || reject "$ACTION requires checkpoint directory and absolute target cycle"
source_dir="$(realpath -e -- "$2")"
verify "$source_dir" >/dev/null
next="$3"; decimal "$next"
previous="$(field "$source_dir/relay-manifest.txt" cycles)"
if [[ "$ACTION" == advance ]]; then
  (( next > previous && next-previous <= 20000000 && next <= 4000000000 )) ||
    reject 'advance must be 1..20M forward cycles, below hard 4B ceiling'
else
  (( next > previous && next-previous <= 100000000 && next <= 4000000000 )) ||
    reject 'range must cover only a bounded <=100M forward window'
fi

current="$source_dir"
while (( previous < next )); do
  target="$((previous + 20000000))"
  (( target > next )) && target="$next"
  previous_state="$(inspect "$current")"
  old_commits="$(read_numeric "$previous_state" commits)"
  output="$(bash tools/ci/product_checkpoint_sd1_short.sh advance-only "$current" "$target")" ||
    reject "SD1 RTL stage $previous -> $target failed; source remains recoverable"
  stage="$(sed -n 's/^PRODUCT_SD1_PERSISTENT_PROOF_DIR=//p' <<< "$output" | tail -1)"
  [[ -n "$stage" && -d "$stage" ]] || reject 'SD1 stage saved no destination checkpoint'
  state="$(inspect "$stage" "$old_commits")"
  seal "$stage" "$previous" "$state"
  echo "PRODUCT_SD1_RELAY_STAGE_READY directory=$stage old_cycles=$previous new_cycles=$target commits=$(read_numeric "$state" commits)"
  if [[ "$(read_flag "$state" login)" == 1 &&
        "$(read_flag "$state" ext4_root_mounted)" == 1 &&
        "$(read_flag "$state" native_init_started)" == 1 ]]; then
    echo "PRODUCT_SD1_RELAY_NATIVE_GETTY_PROMPT_PASS cycles=$target source=$stage"
    break
  fi
  current="$stage"
  previous="$target"
done
