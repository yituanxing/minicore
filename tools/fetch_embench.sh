#!/usr/bin/env bash
set -euo pipefail

revision="${EMBENCH_REVISION:-09c2ed8c3b7008c95d08b038de4a3f6dc103ed70}"
root="${EMBENCH_SOURCE_ROOT:-build/upstream-src}"
checkout="$root/embench-iot-$revision"

if [[ ! -d "$checkout/.git" ]]; then
  rm -rf "$checkout"
  mkdir -p "$checkout"
  git -C "$checkout" init -q
  git -C "$checkout" remote add origin https://github.com/embench/embench-iot.git
  git -C "$checkout" fetch --depth=1 origin "$revision"
  git -C "$checkout" checkout -q FETCH_HEAD
fi

actual="$(git -C "$checkout" rev-parse HEAD)"
if [[ "$actual" != "$revision" ]]; then
  echo "ERROR: Embench checkout mismatch: expected $revision, got $actual" >&2
  exit 1
fi

printf '%s\n' "$checkout"
