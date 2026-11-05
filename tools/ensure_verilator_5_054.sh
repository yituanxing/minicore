#!/usr/bin/env bash
set -euo pipefail

# Keep installation, runtime-asset repair, source identity and cache logic in
# the single qualified installer. This wrapper selects the pinned 5.054 release
# for Product RTL A/B qualification without changing legacy 5.024 defaults.
export AETHERCORE_VERILATOR_VERSION=5.054
exec bash "$(dirname "${BASH_SOURCE[0]}")/ensure_verilator_5_024.sh" "$@"
