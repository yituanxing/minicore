#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT/software/freertos/FreeRTOS-Kernel.lock"
DESTINATION="${1:-$ROOT/build/upstream-src/FreeRTOS-Kernel}"
CACHE_ROOT="${AETHERCORE_SOURCE_CACHE:-${HOME}/.cache/aethercore/sources}"
FETCH_TIMEOUT="${AETHERCORE_SOURCE_FETCH_TIMEOUT:-180}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$LOCK_FILE" ]] || fail "missing FreeRTOS lock file: $LOCK_FILE"
[[ "$FETCH_TIMEOUT" =~ ^[1-9][0-9]*$ ]] \
  || fail "AETHERCORE_SOURCE_FETCH_TIMEOUT must be a positive integer"

lock_value() {
  local key="$1"
  local value
  value="$(sed -n "s/^${key}=//p" "$LOCK_FILE")"
  [[ -n "$value" ]] || fail "missing ${key} in $LOCK_FILE"
  printf '%s\n' "$value"
}

REPOSITORY="$(lock_value repository)"
RELEASE="$(lock_value release)"
REVISION="$(lock_value revision)"
PORT_C_SHA="$(lock_value port_c_blob_sha)"
PORT_ASM_SHA="$(lock_value port_asm_blob_sha)"
PORTMACRO_SHA="$(lock_value portmacro_blob_sha)"

[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "revision is not a full lowercase SHA"
for digest in "$PORT_C_SHA" "$PORT_ASM_SHA" "$PORTMACRO_SHA"; do
  [[ "$digest" =~ ^[0-9a-f]{40}$ ]] || fail "port blob SHA is invalid: $digest"
done

CACHE_DIR="$CACHE_ROOT/freertos-kernel-$REVISION"

validate_tree() {
  local tree="$1"
  [[ -d "$tree/.git" ]] || return 1
  [[ "$(git -C "$tree" rev-parse HEAD 2>/dev/null)" == "$REVISION" ]] || return 1
  [[ "$(git -C "$tree" hash-object portable/GCC/RISC-V/port.c 2>/dev/null)" == "$PORT_C_SHA" ]] || return 1
  [[ "$(git -C "$tree" hash-object portable/GCC/RISC-V/portASM.S 2>/dev/null)" == "$PORT_ASM_SHA" ]] || return 1
  [[ "$(git -C "$tree" hash-object portable/GCC/RISC-V/portmacro.h 2>/dev/null)" == "$PORTMACRO_SHA" ]] || return 1
  git -C "$tree" diff --quiet --ignore-submodules -- || return 1
  git -C "$tree" diff --cached --quiet --ignore-submodules -- || return 1
  [[ -z "$(git -C "$tree" status --porcelain --untracked-files=all)" ]] || return 1
}

mkdir -p "$CACHE_ROOT"

if ! validate_tree "$CACHE_DIR"; then
  rm -rf "$CACHE_DIR"
  temporary="$(mktemp -d "$CACHE_ROOT/.freertos-kernel-$REVISION.XXXXXX")"
  cleanup() {
    rm -rf "$temporary"
  }
  trap cleanup EXIT

  git -C "$temporary" init --quiet
  git -C "$temporary" remote add origin "$REPOSITORY"

  fetched=false
  for attempt in 1 2 3 4 5; do
    echo "FreeRTOS Kernel fetch attempt ${attempt}/5: ${RELEASE} ${REVISION}" >&2
    if timeout "$FETCH_TIMEOUT" \
      git -c http.version=HTTP/1.1 \
          -c http.lowSpeedLimit=1024 \
          -c http.lowSpeedTime=60 \
          -C "$temporary" fetch --quiet --depth=1 origin "$REVISION"; then
      fetched=true
      break
    fi
    sleep $((attempt * 3))
  done
  [[ "$fetched" == true ]] || fail "unable to fetch pinned FreeRTOS Kernel revision"

  git -C "$temporary" checkout --quiet --detach FETCH_HEAD
  validate_tree "$temporary" || fail "fetched FreeRTOS source failed validation"
  mv "$temporary" "$CACHE_DIR"
  trap - EXIT
fi

validate_tree "$CACHE_DIR" || fail "cached FreeRTOS source failed validation"

rm -rf "$DESTINATION"
mkdir -p "$(dirname "$DESTINATION")"
git clone --quiet --no-hardlinks --no-checkout "$CACHE_DIR" "$DESTINATION"
git -C "$DESTINATION" checkout --quiet --detach "$REVISION"
validate_tree "$DESTINATION" || fail "materialized FreeRTOS source failed validation"
printf '%s\n' "$REVISION" > "$DESTINATION/.aethercore-source-revision"
printf 'release=%s\nrevision=%s\nsource=%s\n' "$RELEASE" "$REVISION" "$DESTINATION"
