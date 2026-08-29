#!/usr/bin/env bash
set -euo pipefail

revision="1f483d5b8316753a742cbf5590caf5bd0a4e4777"
repo_url="https://github.com/eembc/coremark.git"
dest="${1:-build/upstream-src/coremark-$revision}"

if [[ -d "$dest/.git" ]]; then
  actual="$(git -C "$dest" rev-parse HEAD)"
  if [[ "$actual" == "$revision" ]]; then
    printf '%s\n' "$dest"
    exit 0
  fi
  rm -rf "$dest"
fi

mkdir -p "$(dirname "$dest")"

fetched=false
for attempt in 1 2 3; do
  rm -rf "$dest"
  git init -q "$dest"
  git -C "$dest" remote add origin "$repo_url"

  if git -C "$dest" -c http.version=HTTP/1.1 \
      fetch -q --depth=1 origin "$revision" && \
      git -C "$dest" checkout -q --detach FETCH_HEAD; then
    actual="$(git -C "$dest" rev-parse HEAD)"
    if [[ "$actual" == "$revision" ]]; then
      fetched=true
      break
    fi
    echo "WARN: CoreMark revision mismatch on attempt $attempt: expected=$revision actual=$actual" >&2
  else
    echo "WARN: CoreMark fetch attempt $attempt/3 failed" >&2
  fi

  rm -rf "$dest"
  if [[ "$attempt" -lt 3 ]]; then
    sleep "$((attempt * 2))"
  fi
done

if [[ "$fetched" != true ]]; then
  echo "ERROR: failed to fetch pinned CoreMark revision after 3 attempts: $revision" >&2
  exit 1
fi

required=(
  core_list_join.c
  core_main.c
  core_matrix.c
  core_state.c
  core_util.c
  coremark.h
  LICENSE.md
)
for path in "${required[@]}"; do
  [[ -f "$dest/$path" ]] || {
    echo "ERROR: pinned CoreMark source is missing $path" >&2
    exit 1
  }
done

printf '%s\n' "$dest"
