#!/usr/bin/env bash
set -euo pipefail

# Self-hosted, persistent, explicitly opted-in SD1 Debian RTL relay.
# The source/media/model preflights and build must already have succeeded.
# The full RTL/DDR/SD snapshots stay on the named runner's private disk;
# this program NEVER uploads them to public Actions artifacts or git.
#
# Required: AETHERCORE_SD1_RELAY_STORE=/stable/private/sd1-checkpoints
#           AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY
#           AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1
# Usage: product_sd1_background_runner.sh run FINAL_CYCLE

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
[[ $# == 2 && "$1" == run ]] || { echo 'usage: background_runner.sh run FINAL_CYCLE' >&2; exit 2; }
fail() { echo "PRODUCT_SD1_BACKGROUND_REJECTED: $*" >&2; exit 3; }
[[ "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY &&
   "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-}" == 1 ]] ||
  fail 'explicit full SD1 checkpoint relay confirmation required'
[[ "${AETHERCORE_SD1_RELAY_STORE:-}" == /* &&
   "${AETHERCORE_SD1_RELAY_STORE:-}" != / &&
   "${AETHERCORE_SD1_RELAY_STORE:-}" != "$ROOT" &&
   "${AETHERCORE_SD1_RELAY_STORE:-}" != "$ROOT"/* ]] ||
  fail 'persistent checkpoint store must be an explicit absolute path outside checkout'
STORE="$AETHERCORE_SD1_RELAY_STORE"
[[ ! -L "$STORE" ]] || fail 'checkpoint store cannot be a symlink'
mkdir -p -- "$STORE/stages" "$STORE/logs"
[[ -d "$STORE/stages" && ! -L "$STORE/stages" && ! -L "$STORE/latest.txt" ]] ||
  fail 'invalid persistent storage layout'
TARGET="$2"
[[ "$TARGET" =~ ^[1-9][0-9]{6,9}$ ]] && (( TARGET <= 4000000000 )) ||
  fail 'positive bounded target cycle <=4B required'

source_sha="$(git rev-parse HEAD)"
python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
  fail 'official Debian SD1 source seed absent or invalid'
[[ -x build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop ]] ||
  fail 'compiled distinct SD1 --savable model unavailable; run build first'

stage_path() { printf '%s/stages/c%s' "$STORE" "$1"; }
stage_number() { sed -n 's/^cycles=//p' "$1/relay-manifest.txt"; }
stage_commits() { sed -n 's/^commits=//p' "$1/relay-manifest.txt"; }
stage_verified() {
  bash tools/ci/product_sd1_checkpoint_relay.sh verify "$1" >/dev/null ||
    fail "persistent stage validation failed: $1"
  [[ "$(sed -n 's/^source_commit=//p' "$1/relay-manifest.txt")" == "$source_sha" ]] ||
    fail 'stored Product source/RTL host SHA differs; cannot silently replay on a new commit'
}
publish() {
  local origin="$1" cycle="$2" dest tmp
  dest="$(stage_path "$cycle")"
  [[ ! -e "$dest" && ! -L "$dest" ]] || fail "checkpoint cycle already exists: $cycle"
  # Move the publish-once source stage into persistent runner-private storage.
  # On a cross-device move, `mv` copies then removes source only after success;
  # RELAY_COMPLETE + hashes are always independently revalidated afterward.
  mv -- "$origin" "$dest"
  stage_verified "$dest"
  tmp="$STORE/latest.txt.tmp.$$"
  printf 'cycle=%s\nrelative=stages/c%s\nsource_commit=%s\n' \
    "$cycle" "$cycle" "$source_sha" > "$tmp"
  sync -f "$tmp" "$dest/RELAY_COMPLETE"
  mv -f -- "$tmp" "$STORE/latest.txt"
  sync -f "$STORE/latest.txt"
  echo "PRODUCT_SD1_BACKGROUND_CHECKPOINT_DURABLE cycle=$cycle commits=$(stage_commits "$dest") store=$dest"
}

if [[ -e "$STORE/latest.txt" ]]; then
  [[ -f "$STORE/latest.txt" && ! -L "$STORE/latest.txt" ]] || fail 'invalid latest checkpoint pointer'
  relative="$(sed -n 's/^relative=//p' "$STORE/latest.txt")"
  [[ "$relative" =~ ^stages/c[1-9][0-9]{6,9}$ ]] || fail 'latest checkpoint path is malformed'
  CURRENT="$STORE/$relative"
  stage_verified "$CURRENT"
  cycle="$(stage_number "$CURRENT")"
  [[ "$(sed -n 's/^cycle=//p' "$STORE/latest.txt")" == "$cycle" ]] ||
    fail 'latest pointer cycle differs from sealed manifest'
  echo "PRODUCT_SD1_BACKGROUND_RESTORED cycle=$cycle commits=$(stage_commits "$CURRENT") no_guest_cold_boot=1"
else
  out="$(bash tools/ci/product_sd1_checkpoint_relay.sh bootstrap)" ||
    fail 'initial bounded 12M cold checkpoint could not be created'
  source_stage="$(sed -n 's/^PRODUCT_SD1_RELAY_BOOTSTRAP_READY directory=\([^ ]*\).*/\1/p' <<< "$out" | tail -1)"
  [[ -d "$source_stage" ]] || fail 'bootstrap produced no verifiable checkpoint directory'
  cycle="$(stage_number "$source_stage")"
  publish "$source_stage" "$cycle"
  CURRENT="$(stage_path "$cycle")"
fi

while (( cycle < TARGET )); do
  next="$((cycle + 20000000))"
  (( next > TARGET )) && next="$TARGET"
  # The stage itself has a hard 330s execution watchdog. A non-progressing
  # CPU, fatal UART, stale media, or corrupt snapshot aborts before publishing.
  out="$(bash tools/ci/product_sd1_checkpoint_relay.sh advance "$CURRENT" "$next")" ||
    fail "stage $cycle->$next failed; last good persistent checkpoint remains $CURRENT"
  source_stage="$(sed -n 's/^PRODUCT_SD1_RELAY_STAGE_READY directory=\([^ ]*\).*/\1/p' <<< "$out" | tail -1)"
  [[ -d "$source_stage" ]] || fail 'stage produced no complete snapshot'
  publish "$source_stage" "$next"
  CURRENT="$(stage_path "$next")"
  cycle="$next"
  printf 'cycle=%s commits=%s wall_utc=%s\n' \
    "$cycle" "$(stage_commits "$CURRENT")" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$STORE/logs/progress.log"
  if [[ "$out" == *'PRODUCT_SD1_RELAY_NATIVE_GETTY_PROMPT_PASS '* ]]; then
    echo "PRODUCT_SD1_BACKGROUND_NATIVE_LOGIN_PROMPT_PASS cycle=$cycle checkpoint=$CURRENT"
    exit 0
  fi
done

echo "PRODUCT_SD1_BACKGROUND_HORIZON_REACHED cycle=$cycle target=$TARGET no_login_claim=1 last_checkpoint=$CURRENT"
