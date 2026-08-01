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
git init -q "$dest"
git -C "$dest" remote add origin "$repo_url"
git -C "$dest" fetch -q --depth=1 origin "$revision"
git -C "$dest" checkout -q --detach FETCH_HEAD

actual="$(git -C "$dest" rev-parse HEAD)"
[[ "$actual" == "$revision" ]] || {
  echo "ERROR: CoreMark revision mismatch: expected=$revision actual=$actual" >&2
  exit 1
}

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
