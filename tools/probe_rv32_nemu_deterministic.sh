#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temp_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
wrapper_dir="$(mktemp -d "$temp_parent/aethercore-deterministic-find.XXXXXX")"
trap 'rm -rf "$wrapper_dir"' EXIT

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

PATH="$wrapper_dir:$PATH" \
  bash "$script_dir/probe_rv32_nemu_historical.sh" "$@"
