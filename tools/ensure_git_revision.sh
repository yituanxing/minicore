#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 4 ]] || fail "usage: ensure_git_revision.sh REPOSITORY REVISION DESTINATION CACHE_NAME"

repository="$1"
revision="$2"
destination="$3"
cache_name="$4"

[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || fail "revision must be a full 40-character lowercase SHA"
[[ "$cache_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "cache name contains unsupported characters"

cache_root="${AETHERCORE_SOURCE_CACHE:-${HOME}/.cache/aethercore/sources}"
cache_dir="${cache_root}/${cache_name}-${revision}"
fetch_timeout="${AETHERCORE_SOURCE_FETCH_TIMEOUT:-180}"

[[ "$fetch_timeout" =~ ^[1-9][0-9]*$ ]] || fail "AETHERCORE_SOURCE_FETCH_TIMEOUT must be a positive integer"

validate_tree() {
  local tree="$1"
  [[ -d "${tree}/.git" ]] || return 1
  [[ "$(git -C "$tree" rev-parse HEAD 2>/dev/null)" == "$revision" ]] || return 1
  git -C "$tree" diff --quiet --ignore-submodules -- || return 1
  git -C "$tree" diff --cached --quiet --ignore-submodules -- || return 1
  [[ -z "$(git -C "$tree" status --porcelain --untracked-files=all)" ]] || return 1
}

mkdir -p "$cache_root"

if ! validate_tree "$cache_dir"; then
  rm -rf "$cache_dir"
  temporary="$(mktemp -d "${cache_root}/.${cache_name}-${revision}.XXXXXX")"
  cleanup() {
    rm -rf "$temporary"
  }
  trap cleanup EXIT

  git -C "$temporary" init --quiet
  git -C "$temporary" remote add origin "$repository"

  fetched=false
  for attempt in 1 2 3 4 5; do
    echo "${cache_name} fetch attempt ${attempt}/5 at ${revision}" >&2
    if timeout "$fetch_timeout" \
      git -c http.version=HTTP/1.1 \
      -c http.lowSpeedLimit=1024 \
      -c http.lowSpeedTime=60 \
      -C "$temporary" fetch --quiet --depth=1 origin "$revision"; then
      fetched=true
      break
    fi
    sleep $((attempt * 3))
  done
  [[ "$fetched" == true ]] || fail "unable to fetch ${cache_name} revision ${revision}"

  git -C "$temporary" checkout --quiet --detach FETCH_HEAD
  validate_tree "$temporary" || fail "fetched ${cache_name} tree failed validation"
  mv "$temporary" "$cache_dir"
  trap - EXIT
fi

validate_tree "$cache_dir" || fail "cached ${cache_name} tree failed validation"

rm -rf "$destination"
mkdir -p "$(dirname "$destination")"
git clone --quiet --no-hardlinks --no-checkout "$cache_dir" "$destination"
git -C "$destination" checkout --quiet --detach "$revision"
validate_tree "$destination" || fail "materialized ${cache_name} tree failed validation"
printf '%s\n' "$revision"
