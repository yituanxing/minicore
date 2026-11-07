#!/usr/bin/env bash
set -euo pipefail

# The Product G5 D2 relay deliberately does not build Linux, Chisel, GSIM or
# Verilator. It only executes the byte-pinned historical --savable binary.
#
# prepare SEED_OR_RELAY_ARTIFACT_DIR FRESH_OUTPUT_DIR seed_950m|relay
# run     PREPARED_OUTPUT_DIR 50000000|100000000
#
# A checkpoint directory is published only after its host, RTL and manifest
# have all been hashed. On failure the previous complete snapshot remains
# available for the workflow's unconditional artifact upload.

reject() { printf 'G5_D2_RELAY_REJECT reason=%s\n' "$*" >&2; exit 3; }

readonly SOURCE_COMMIT=99a5c86e99f52ed12f95b304635d77613c9505c4
readonly MODEL_SHA=aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71
readonly BINARY_SHA=5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d
readonly FIRMWARE_SHA=d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c
# The historical executable rejects a bootMax argument above 1.8B, but its
# ordinary restore mode advances a bounded 5M cycles independently of bootMax.
# Keep that immutable host argument within its compiled ABI contract while the
# relay wrapper independently bounds the accumulated restored RTL horizon.
readonly HOST_BOOT_MAX_CYCLES=1800000000
readonly RELAY_MAX_CYCLES=2700000000
readonly SEED_CYCLES=950000000
readonly SEED_COMMITS=244648576
readonly G5_MARKER='Linux version 6.6.143'

require_decimal() { [[ "$1" =~ ^(0|[1-9][0-9]*)$ ]] || reject "not unsigned decimal: $1"; }
require_sha() { [[ "$1" =~ ^[[:xdigit:]]{64}$ ]] || reject 'invalid SHA256'; }

sha_of() { sha256sum -- "$1" | cut -d' ' -f1; }

field() {
  local value
  value="$(sed -n "s/^${2}=//p" "$1")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || reject "missing/duplicate field $2 in $1"
  printf '%s' "$value"
}

unique_file() {
  local root="$1" filename="$2"
  local -a found=()
  mapfile -t found < <(find "$root" -type f -name "$filename" -print)
  (( ${#found[@]} == 1 )) || reject "expected exactly one $filename; found ${#found[@]}"
  printf '%s' "${found[0]}"
}

check_binary() {
  [[ -x "$1" ]] || reject 'frozen G5 simulator binary not executable'
  [[ "$(sha_of "$1")" == "$BINARY_SHA" ]] || reject 'frozen binary SHA drift'
}

check_complete() {
  local dir="$1" manifest="$1/manifest.txt"
  [[ -s "$dir/checkpoint.host" && -s "$dir/checkpoint.rtl" &&
     -s "$manifest" && -s "$dir/COMPLETE" ]] || return 1
  [[ "$(awk '{print $2}' "$dir/COMPLETE")" == $'checkpoint.host\ncheckpoint.rtl\nmanifest.txt' ]] || return 1
  (cd "$dir" && sha256sum --check --status COMPLETE) || return 1
  [[ "$(field "$manifest" format)" == g5-d2-checkpoint-v1 &&
     "$(field "$manifest" source_commit)" == "$SOURCE_COMMIT" &&
     "$(field "$manifest" model_sha256)" == "$MODEL_SHA" &&
     "$(field "$manifest" binary_sha256)" == "$BINARY_SHA" &&
     "$(field "$manifest" firmware_sha256)" == "$FIRMWARE_SHA" ]] || return 1
  local cycle commits
  cycle="$(field "$manifest" cycles)"
  commits="$(field "$manifest" commits)"
  require_decimal "$cycle"
  require_decimal "$commits"
  [[ "$dir" == */c"$cycle" && "$cycle" -ge "$SEED_CYCLES" &&
     "$cycle" -le "$RELAY_MAX_CYCLES" && "$((cycle % 5000000))" -eq 0 ]] || return 1
}

publish_manifest() {
  local dir="$1" cycle="$2" commits="$3" origin="$4"
  require_decimal "$cycle"
  require_decimal "$commits"
  printf 'format=g5-d2-checkpoint-v1\nsource_commit=%s\nmodel_sha256=%s\nbinary_sha256=%s\nfirmware_sha256=%s\ncycles=%s\ncommits=%s\norigin=%s\n' \
    "$SOURCE_COMMIT" "$MODEL_SHA" "$BINARY_SHA" "$FIRMWARE_SHA" \
    "$cycle" "$commits" "$origin" > "$dir/manifest.txt"
  (cd "$dir" && sha256sum checkpoint.host checkpoint.rtl manifest.txt > COMPLETE)
  check_complete "$dir" || reject "new checkpoint $cycle failed complete-hash gate"
}

select_latest_complete() {
  local root="$1" complete dir cycle best=0
  local best_dir=''
  while IFS= read -r -d '' complete; do
    dir="$(dirname "$complete")"
    [[ "$dir" =~ /checkpoints/c[0-9]+$ ]] || continue
    if ! check_complete "$dir"; then
      printf 'G5_D2_RELAY_IGNORED_INCOMPLETE path=%s\n' "$dir" >&2
      continue
    fi
    cycle="$(field "$dir/manifest.txt" cycles)"
    if (( cycle > best )); then best="$cycle"; best_dir="$dir"; fi
  done < <(find "$root" -type f -name COMPLETE -print0)
  [[ -n "$best_dir" ]] || reject 'no complete verified G5 checkpoint in artifact'
  printf '%s' "$best_dir"
}

prepare() {
  [[ $# == 3 ]] || reject 'prepare requires source_dir fresh_out seed_950m|relay'
  local source="$1" out="$2" kind="$3"
  [[ -d "$source" && ! -L "$source" && "$kind" =~ ^(seed_950m|relay)$ ]] ||
    reject 'invalid download directory or source kind'
  [[ ! -e "$out" && ! -L "$out" ]] || reject 'output directory must be fresh'

  local binary host rtl cycle commits
  binary="$(unique_file "$source" product-g5-checkpoint)"
  # actions/upload-artifact/download-artifact do NOT retain executable bits.
  # The downloaded bytes must match the frozen SHA, but chmod is applied only
  # to our private staged copy before the actual executable is launched.
  [[ -s "$binary" && "$(sha_of "$binary")" == "$BINARY_SHA" ]] ||
    reject 'downloaded frozen binary SHA drift or missing bytes'
  if [[ "$kind" == seed_950m ]]; then
    local bridge manifest bridge_dir
    bridge="$(unique_file "$source" bridge-identity.txt)"
    [[ "$(field "$bridge" source_commit)" == "$SOURCE_COMMIT" &&
       "$(field "$bridge" archived_model_sha256)" == "$MODEL_SHA" &&
       "$(field "$bridge" rebuilt_model_sha256)" == "$BINARY_SHA" &&
       "$(field "$bridge" firmware_sha256)" == "$FIRMWARE_SHA" &&
       "$(field "$bridge" golden_rtl_host_identical)" == 1 &&
       "$(field "$bridge" snapshot_target_cycles)" == "$SEED_CYCLES" ]] ||
      reject 'historical 950M Golden provenance mismatch'
    host="$(unique_file "$source" persistent.bridge-950m.host)"
    rtl="$(unique_file "$source" persistent.bridge-950m.rtl)"
    manifest="$(unique_file "$source" bridge-950m.sha256)"
    bridge_dir="$(dirname "$manifest")"
    [[ "$(dirname "$host")" == "$bridge_dir" &&
       "$(dirname "$rtl")" == "$bridge_dir" ]] ||
      reject 'seed snapshot and SHA manifest not colocated'
    (cd "$bridge_dir" && sha256sum --check --status bridge-950m.sha256) ||
      reject 'original 950M snapshot SHA manifest mismatch'
    cycle="$SEED_CYCLES"
    commits="$SEED_COMMITS"
  else
    local best
    best="$(select_latest_complete "$source")"
    host="$best/checkpoint.host"
    rtl="$best/checkpoint.rtl"
    cycle="$(field "$best/manifest.txt" cycles)"
    commits="$(field "$best/manifest.txt" commits)"
  fi

  mkdir -p -- "$out/model" "$out/checkpoints/c$cycle" "$out/logs" "$out/.staging" "$out/work"
  cp -- "$binary" "$out/model/product-g5-checkpoint"
  chmod +x "$out/model/product-g5-checkpoint"
  cp --reflink=auto --sparse=always -- "$host" "$out/checkpoints/c$cycle/checkpoint.host"
  cp -- "$rtl" "$out/checkpoints/c$cycle/checkpoint.rtl"
  publish_manifest "$out/checkpoints/c$cycle" "$cycle" "$commits" "$kind"
  check_binary "$out/model/product-g5-checkpoint"
  printf 'G5_D2_RELAY_PREPARED source=%s cycles=%s commits=%s binary_sha256=%s\n' \
    "$kind" "$cycle" "$commits" "$BINARY_SHA"
}

run() {
  [[ $# == 2 ]] || reject 'run requires prepared_out 50000000|100000000'
  local out="$1" increment="$2"
  [[ -d "$out/model" && -d "$out/checkpoints" &&
     ( "$increment" == 50000000 || "$increment" == 100000000 ) ]] ||
    reject 'invalid prepared output or segment length'
  local binary="$out/model/product-g5-checkpoint"
  check_binary "$binary"
  local current current_cycle current_commits end_cycle next segment stage line restore_line commits
  local previous_dir='' previous_cycle=0 old_dir old_cycle
  current="$(select_latest_complete "$out")"
  current_cycle="$(field "$current/manifest.txt" cycles)"
  current_commits="$(field "$current/manifest.txt" commits)"
  # Each 50M/100M run is a separate YAML step. Recover the predecessor from
  # the previous step so that the newest two checkpoints, not one per YAML
  # step, remain resident throughout the full 850M push workflow.
  for old_dir in "$out"/checkpoints/c*; do
    [[ -d "$old_dir" ]] || continue
    check_complete "$old_dir" || reject "preexisting checkpoint was corrupted: $old_dir"
    [[ "$old_dir" != "$current" ]] || continue
    old_cycle="$(field "$old_dir/manifest.txt" cycles)"
    if (( old_cycle > previous_cycle )); then
      previous_cycle="$old_cycle"
      previous_dir="$old_dir"
    fi
  done
  # Prune only our own exact, verified, older staging artifacts; a separate
  # GitHub upload from the prior YAML step already persists its milestone.
  for old_dir in "$out"/checkpoints/c*; do
    [[ -d "$old_dir" && "$old_dir" != "$current" && "$old_dir" != "$previous_dir" ]] || continue
    check_complete "$old_dir" || reject "cannot safely rotate unverified checkpoint: $old_dir"
    rm -r -- "$old_dir"
  done
  end_cycle="$((current_cycle + increment))"
  (( end_cycle <= RELAY_MAX_CYCLES )) || reject 'relay exceeds the G5 2.7B external horizon'

  while (( current_cycle < end_cycle )); do
    next="$((current_cycle + 5000000))"
    segment="$out/work/segment-$next"
    [[ ! -e "$segment.pre.host" && ! -e "$segment.restored.host" ]] ||
      reject "segment $next would overwrite prior output"
    ln -s -- "$(realpath -e "$current/checkpoint.host")" "$segment.pre.host"
    ln -s -- "$(realpath -e "$current/checkpoint.rtl")" "$segment.pre.rtl"
    if ! timeout --signal=TERM --kill-after=10s 900s \
      "$binary" restore /dev/null "$segment" "$MODEL_SHA" "$FIRMWARE_SHA" \
      "$HOST_BOOT_MAX_CYCLES" "$G5_MARKER" 5000000 \
      > "$out/logs/segment-$next.log" 2>&1; then
      tail -35 "$out/logs/segment-$next.log" >&2 || true
      reject "frozen RTL execution failed in segment $current_cycle->$next"
    fi
    restore_line="$(grep '^PRODUCT_G5_PERSISTENT_CHECKPOINT_RESTORED ' "$out/logs/segment-$next.log" | tail -1)"
    line="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$out/logs/segment-$next.log" | tail -1)"
    [[ " $restore_line " == *" cycles=$current_cycle commits=$current_commits "* &&
       " $line " == *" cycles=$next time=$next "* &&
       " $line " == *' linux_banner=1 '* &&
       -s "$segment.restored.host" && -s "$segment.restored.rtl" ]] ||
      reject "checkpoint/observer cycle or file mismatch at $next"
    commits="$(sed -n 's/.* commits=\([0-9]*\) .*/\1/p' <<< "$line")"
    require_decimal "$commits"
    (( commits >= current_commits )) || reject "retired instruction count regressed at $next"

    # The first 5M is a bounded ISA/ABI/state/commit/trace smoke from the
    # SHA-qualified 950M state. Do not require old wall-clock-dependent
    # interim counters; only the original input checksum is authoritative.
    if (( current_cycle == SEED_CYCLES && next == 955000000 )); then
      [[ " $line " == *' trace_hash=0x'* &&
         " $line " == *' ram_hash=0x'* &&
         " $line " == *' interrupts='* &&
         " $line " == *' uart_bytes='* ]] ||
        reject '950->955M bounded replay did not emit complete trace/interrupt/RAM witness'
      echo "G5_D2_RELAY_INITIAL_5M_PASS exact_guest_cycle=1 commits=$commits state=SHA_QUALIFIED"
    fi

    stage="$out/.staging/c$next"
    [[ ! -e "$stage" && ! -e "$out/checkpoints/c$next" ]] ||
      reject "new staged checkpoint $next already exists"
    mkdir -p "$stage"
    mv -- "$segment.restored.host" "$stage/checkpoint.host"
    mv -- "$segment.restored.rtl" "$stage/checkpoint.rtl"
    publish_manifest "$stage" "$next" "$commits" "restore-from-$current_cycle"
    mv -- "$stage" "$out/checkpoints/c$next"
    rm -f -- "$segment.pre.host" "$segment.pre.rtl"
    printf '%s\n' "$line" >> "$out/progress.log"
    echo "G5_D2_RELAY_STEP_PASS cycles=$next commits=$commits checkpoint=$out/checkpoints/c$next"

    # Keep the newest two *complete* local states. The prior GitHub artifact
    # remains untouched until the new run's artifact upload succeeds.
    if [[ -n "$previous_dir" && -d "$previous_dir" ]]; then
      check_complete "$previous_dir" || reject 'previous state unexpectedly corrupted before rotation'
      rm -r -- "$previous_dir"
    fi
    previous_dir="$current"
    current="$out/checkpoints/c$next"
    current_cycle="$next"
    current_commits="$commits"
  done
  printf 'G5_D2_RELAY_SEGMENT_PASS cycles=%s commits=%s increment=%s\n' \
    "$current_cycle" "$current_commits" "$increment"
}

action="${1:-}"
[[ $# -ge 1 ]] || reject 'usage: prepare SOURCE FRESH_OUT seed_950m|relay | run OUT 50000000|100000000'
shift
case "$action" in
  prepare) prepare "$@" ;;
  run) run "$@" ;;
  *) reject "unknown action: $action" ;;
esac
