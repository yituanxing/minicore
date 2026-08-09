#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temp_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
wrapper_dir="$(mktemp -d "$temp_parent/aethercore-deterministic-tools.XXXXXX")"
trap 'rm -rf "$wrapper_dir"' EXIT

revision="${NEMU_REVISION:-8601834e4889e6bf3b6113eb5f824ba7689126f5}"
work_dir="${1:-build/rv32-nemu-probe}"
if [[ "$work_dir" == /* ]]; then
  source_dir="$work_dir/nemu"
else
  source_dir="$PWD/$work_dir/nemu"
fi
cache_checkout="$wrapper_dir/nemu-source"
real_git="$(command -v git)"

# Materialize the pinned historical source from a validated persistent cache.
# The historical probe still executes its original init/fetch/checkout sequence;
# the git wrapper below redirects only that exact fetch to this local checkout.
bash "$script_dir/ensure_git_revision.sh" \
  https://github.com/OpenXiangShan/NEMU.git \
  "$revision" "$cache_checkout" nemu >/dev/null

# The pinned historical NEMU Makefile expands its source list with GNU find.
# find traversal order depends on the backing filesystem, so identical sources
# could link into different ELF layouts on GitHub-hosted ext4 and WSL2 ext4.
# Sort every find result while the historical probe runs, making object/link
# order portable without changing the pinned NEMU revision or ISA behavior.
cat > "$wrapper_dir/find" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/find "$@" | LC_ALL=C sort
EOF
chmod +x "$wrapper_dir/find"

cat > "$wrapper_dir/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 6 \
  && "$1" == "-C" \
  && "$3" == "fetch" \
  && "$4" == "--depth=1" \
  && "$5" == "origin" \
  && "$6" == "$AETHERCORE_NEMU_REVISION" ]]; then
  candidate_dir="$(cd "$2" 2>/dev/null && pwd || true)"
  if [[ "$candidate_dir" == "$AETHERCORE_NEMU_SOURCE_DIR" ]]; then
    exec "$AETHERCORE_REAL_GIT" -C "$2" fetch --depth=1 \
      "$AETHERCORE_NEMU_CACHE_CHECKOUT" "$AETHERCORE_NEMU_REVISION"
  fi
fi

exec "$AETHERCORE_REAL_GIT" "$@"
EOF
chmod +x "$wrapper_dir/git"

AETHERCORE_REAL_GIT="$real_git" \
AETHERCORE_NEMU_SOURCE_DIR="$source_dir" \
AETHERCORE_NEMU_CACHE_CHECKOUT="$cache_checkout" \
AETHERCORE_NEMU_REVISION="$revision" \
PATH="$wrapper_dir:$PATH" \
  bash "$script_dir/probe_rv32_nemu_historical.sh" "$@"
