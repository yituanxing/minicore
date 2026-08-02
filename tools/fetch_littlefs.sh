#!/usr/bin/env bash
set -euo pipefail

revision="${LITTLEFS_REVISION:-6cb4e86540eca0d9ba62500a298385c9d863c8be}"
root="${LITTLEFS_SOURCE_ROOT:-build/upstream-src}"
checkout="$root/littlefs-$revision"

if [[ ! -d "$checkout/.git" ]]; then
  rm -rf "$checkout"
  mkdir -p "$checkout"
  git -C "$checkout" init -q
  git -C "$checkout" remote add origin https://github.com/littlefs-project/littlefs.git
  git -C "$checkout" fetch --depth=1 origin "$revision"
  git -C "$checkout" checkout -q FETCH_HEAD
fi

actual="$(git -C "$checkout" rev-parse HEAD)"
if [[ "$actual" != "$revision" ]]; then
  echo "ERROR: littlefs checkout mismatch: expected $revision, got $actual" >&2
  exit 1
fi

printf '%s\n' "$checkout"
