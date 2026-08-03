#!/usr/bin/env bash
set -euo pipefail

SOFTFLOAT_REPOSITORY="https://github.com/ucb-bar/berkeley-softfloat-3.git"
SOFTFLOAT_REVISION="a0c6494cdc11865811dec815d5c0049fba9d82a8"
CACHE_ROOT="${AETHERCORE_SOURCE_CACHE:-${HOME}/.cache/aethercore/sources}"
CACHE_DIR="${CACHE_ROOT}/berkeley-softfloat-3-${SOFTFLOAT_REVISION}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || fail "usage: ensure_berkeley_softfloat.sh DESTINATION"
DESTINATION="$1"

validate_tree() {
  local tree="$1"
  [[ -d "${tree}/.git" ]] || return 1
  [[ -f "${tree}/COPYING.txt" ]] || return 1
  [[ "$(git -C "$tree" rev-parse HEAD 2>/dev/null)" == "$SOFTFLOAT_REVISION" ]] || return 1
  git -C "$tree" diff --quiet --ignore-submodules -- || return 1
  git -C "$tree" diff --cached --quiet --ignore-submodules -- || return 1
}

mkdir -p "$CACHE_ROOT"

if ! validate_tree "$CACHE_DIR"; then
  rm -rf "$CACHE_DIR"
  temporary="$(mktemp -d "${CACHE_ROOT}/.berkeley-softfloat-3-${SOFTFLOAT_REVISION}.XXXXXX")"
  cleanup() {
    rm -rf "$temporary"
  }
  trap cleanup EXIT

  git -C "$temporary" init --quiet
  git -C "$temporary" remote add origin "$SOFTFLOAT_REPOSITORY"

  fetched=false
  for attempt in 1 2 3 4; do
    echo "SoftFloat fetch attempt ${attempt}/4 at ${SOFTFLOAT_REVISION}" >&2
    if git -c http.version=HTTP/1.1 -C "$temporary" \
      fetch --quiet --depth=1 origin "$SOFTFLOAT_REVISION"; then
      fetched=true
      break
    fi
    sleep $((attempt * 3))
  done
  [[ "$fetched" == true ]] || fail "unable to fetch fixed SoftFloat revision"

  git -C "$temporary" checkout --quiet --detach FETCH_HEAD
  validate_tree "$temporary" || fail "fetched SoftFloat tree failed validation"
  mv "$temporary" "$CACHE_DIR"
  trap - EXIT
fi

validate_tree "$CACHE_DIR" || fail "cached SoftFloat tree failed validation"

rm -rf "$DESTINATION"
mkdir -p "$(dirname "$DESTINATION")"
git clone --quiet --no-hardlinks --no-checkout "$CACHE_DIR" "$DESTINATION"
git -C "$DESTINATION" checkout --quiet --detach "$SOFTFLOAT_REVISION"
validate_tree "$DESTINATION" || fail "materialized SoftFloat tree failed validation"
printf '%s\n' "$SOFTFLOAT_REVISION" > "${DESTINATION}/.aethercore-source-revision"

# stdout is intentionally machine-readable for evidence manifests.
printf '%s\n' "$SOFTFLOAT_REVISION"
