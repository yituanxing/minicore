#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REVISION="8601834e4889e6bf3b6113eb5f824ba7689126f5"
OPT_SHA256="0e9dc52aeb2f02c399beaa6c5415ff2f4b6c54cfc9aec84f5be0282fe608cd8a"
SINGLE_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"

PROBE_DIR="$ROOT/build/rv32-nemu-probe"
OPT_ARCHIVE_DIR="$ROOT/build/rv32-nemu-abi"
LOG_DIR="$ROOT/build/ci/logs"
PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
CACHE_PARENT="${AETHERCORE_BINARY_CACHE:-${HOME}/.cache/aethercore/binaries}/rv32-nemu"
CACHE_KEY="${REVISION}-${OPT_SHA256:0:16}-${SINGLE_SHA256:0:16}"
CACHE_DIR="$CACHE_PARENT/$CACHE_KEY"
OPT_CACHE="$CACHE_DIR/optimized"
SINGLE_CACHE="$CACHE_DIR/single-step"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

reference_so() {
  local tree="$1"
  find "$tree/nemu/build" -maxdepth 1 -type f \
    -name 'riscv32-nemu-interpreter-so*' -print -quit
}

validate_reference() {
  local tree="$1"
  local expected_sha="$2"
  local expected_single_step="$3"
  local expected_perf_opt="$4"
  local result="$tree/evidence/result.txt"
  local config="$tree/evidence/generated.config"
  local so

  [[ -f "$result" && -f "$config" ]] || return 1
  grep -q '^status=PASS$' "$result" || return 1
  grep -q "^revision=${REVISION}$" "$result" || return 1
  grep -q '^reproducible=true$' "$result" || return 1
  grep -q "^single_step=${expected_single_step}$" "$result" || return 1
  grep -q "^perf_opt=${expected_perf_opt}$" "$result" || return 1
  grep -q "^reference_sha256=${expected_sha}$" "$result" || return 1

  so="$(reference_so "$tree")"
  [[ -n "$so" && -f "$so" ]] || return 1
  [[ "$(sha256sum "$so" | awk '{print $1}')" == "$expected_sha" ]] || return 1

  if [[ "$expected_single_step" == 1 ]]; then
    grep -q '^CONFIG_ENABLE_INSTR_CNT=y$' "$config" || return 1
    grep -q '^# CONFIG_PERF_OPT is not set$' "$config" || return 1
  fi
}

materialize_reference() {
  local cache="$1"
  local destination="$2"
  rm -rf "$destination"
  mkdir -p "$destination/nemu"
  cp -a "$cache/evidence" "$destination/evidence"
  cp -a "$cache/nemu/build" "$destination/nemu/build"
}

write_reference_path() {
  local so
  so="$(reference_so "$PROBE_DIR")"
  [[ -n "$so" && -f "$so" ]] || fail "RV32 NEMU shared object missing"
  printf '%s\n' "$so" > "$PATH_FILE"
}

rm -rf "$PROBE_DIR" "$OPT_ARCHIVE_DIR"
mkdir -p "$LOG_DIR" "$CACHE_PARENT"

if [[ -f "$CACHE_DIR/READY" ]] \
  && validate_reference "$OPT_CACHE" "$OPT_SHA256" 0 true \
  && validate_reference "$SINGLE_CACHE" "$SINGLE_SHA256" 1 false; then
  echo "RV32 NEMU binary cache hit: $CACHE_KEY" | tee "$LOG_DIR/rv32-nemu-cache.log"
  materialize_reference "$OPT_CACHE" "$OPT_ARCHIVE_DIR"
  materialize_reference "$SINGLE_CACHE" "$PROBE_DIR"
  write_reference_path
  exit 0
fi

rm -rf "$CACHE_DIR"
echo "RV32 NEMU binary cache miss: $CACHE_KEY" | tee "$LOG_DIR/rv32-nemu-cache.log"

set -o pipefail
bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-optimized.log"
validate_reference "$PROBE_DIR" "$OPT_SHA256" 0 true \
  || fail "optimized RV32 NEMU reference failed validation"
mv "$PROBE_DIR" "$OPT_ARCHIVE_DIR"

NEMU_SINGLE_STEP=1 bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-single-step.log"
validate_reference "$PROBE_DIR" "$SINGLE_SHA256" 1 false \
  || fail "single-step RV32 NEMU reference failed validation"
write_reference_path

stage="$(mktemp -d "$CACHE_PARENT/.${CACHE_KEY}.XXXXXX")"
cleanup() {
  rm -rf "$stage"
}
trap cleanup EXIT

mkdir -p "$stage/optimized/nemu" "$stage/single-step/nemu"
cp -a "$OPT_ARCHIVE_DIR/evidence" "$stage/optimized/evidence"
cp -a "$OPT_ARCHIVE_DIR/nemu/build" "$stage/optimized/nemu/build"
cp -a "$PROBE_DIR/evidence" "$stage/single-step/evidence"
cp -a "$PROBE_DIR/nemu/build" "$stage/single-step/nemu/build"

validate_reference "$stage/optimized" "$OPT_SHA256" 0 true \
  || fail "optimized RV32 NEMU cache staging failed validation"
validate_reference "$stage/single-step" "$SINGLE_SHA256" 1 false \
  || fail "single-step RV32 NEMU cache staging failed validation"
printf '%s\n' "$CACHE_KEY" > "$stage/READY"
mv "$stage" "$CACHE_DIR"
trap - EXIT

echo "RV32 NEMU binary cache stored: $CACHE_KEY" | tee -a "$LOG_DIR/rv32-nemu-cache.log"
