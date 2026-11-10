#!/usr/bin/env bash
set -euo pipefail

# Independent G5 RV64GC/LP64D RAM-initramfs engine selection. Reuse the
# exact G3 generator/compiler/host transport implementation but never reuse
# G3 generated RTL, its binary, its model identity or its runtime evidence.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_GSIM_G5_REJECTED: SD1 SPI/card bridge not yet qualified' >&2
  exit 2
}
export PRODUCT_GSIM_DEBIAN_MODE=1
export PRODUCT_GSIM_OUT="${PRODUCT_GSIM_G5_OUT:-$ROOT/build/product-gsim-debian-bridge}"
exec bash "$ROOT/tools/ci/product_gsim_build.sh"
