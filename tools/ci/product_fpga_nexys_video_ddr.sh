#!/usr/bin/env bash
# Fixed-source Nexys Video DDR collateral gate. Import-only is NOT a board PASS.
set -euo pipefail

usage() {
  echo "Usage: $0 preflight PINNED_REFERENCE_CHECKOUT RESULT_JSON" >&2
  echo "       $0 import-ddr PINNED_REFERENCE_CHECKOUT NEW_OUTPUT_DIR" >&2
  echo "       $0 integrate-product PINNED_REFERENCE_CHECKOUT PINNED_PRODUCT_EXPORT_DIR NEW_OUTPUT_DIR" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
mode="$1"
if [[ "$mode" == integrate-product ]]; then
  [[ $# -eq 4 ]] || usage
  reference_root="$2"
  product_export_dir="$3"
  product_rtl_dir="$product_export_dir/rtl"
  output="$4"
else
  [[ $# -eq 3 ]] || usage
  reference_root="$2"
  output="$3"
fi
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gate="$repo_dir/tools/fpga/nexys_video_reference_gate.py"
case "$mode" in preflight|import-ddr|integrate-product) ;; *) usage ;; esac

if [[ "$mode" == preflight ]]; then
  exec python3 "$gate" --reference-root "$reference_root" --output "$output"
fi

# Validate upstream commit, every source file's git-blob SHA, DDR ABI, XDC
# pins and reset dependencies *before* creating a Vivado project.
[[ ! -e "$output" ]] || {
  echo "NEXYS_IMPORT_FAILED: output already exists: $output" >&2
  exit 2
}
reference_report="$(python3 "$gate" --reference-root "$reference_root")"
if [[ "$mode" == integrate-product ]]; then
  if [[ -n "$(git -C "$repo_dir" status --porcelain --untracked-files=all -- \
    src/main build.mill mill tools/ci tools/fpga)" ]]; then
    echo "PRODUCT_NEXYS_BD_FAILED: Product/board build sources are dirty; commit before exact-SHA verification" >&2
    exit 2
  fi
  # Require an existing, SHA-pinned export from product_fpga_artix7_p0.sh.
  # Never silently regenerate Product RTL during an expensive board build.
  [[ -s "$product_export_dir/manifest.json" && -s "$product_export_dir/rtl.sha256" && -d "$product_rtl_dir" ]] || {
    echo "PRODUCT_NEXYS_BD_FAILED: use an existing SHA-pinned Product export; prepare explicitly first" >&2
    exit 2
  }
  bash "$repo_dir/tools/ci/product_fpga_artix7_p0.sh" prepare "$product_export_dir" >/dev/null
  port_report="$(python3 "$repo_dir/tools/fpga/product_nexys_axi_port_gate.py" \
    --rtl-dir "$product_rtl_dir" \
    --bridge "$repo_dir/tools/fpga/aether_product_nexys_axi_bridge.sv")"
fi
command -v vivado >/dev/null 2>&1 || {
  echo "NEXYS_IMPORT_NOT_RUN: Vivado 2026.1 unavailable; reference source alone is not a DDR IP build" >&2
  exit 3
}

export NEXYS_REFERENCE_DIR="$reference_root"
export NEXYS_OUTPUT_DIR="$output"
if [[ "$mode" == import-ddr ]]; then
  vivado -mode batch -nojournal -nolog \
    -source "$repo_dir/tools/fpga/nexys_video_ddr_probe.tcl"
  [[ -s "$output/IMPORT_ONLY.txt" ]] || {
    echo "NEXYS_IMPORT_FAILED: no DDR hierarchy evidence" >&2
    exit 1
  }
  printf '%s\n' "$reference_report" > "$output/reference-source.json"
  echo "NEXYS_DDR_HIERARCHY_IMPORTED_ONLY: $output (no Product AXI, P&R or DDR calibration)"
else
  export NEXYS_PRODUCT_RTL_DIR="$product_rtl_dir"
  vivado -mode batch -nojournal -nolog \
    -source "$repo_dir/tools/fpga/nexys_video_product_bd.tcl"
  [[ -s "$output/LOGICAL_BD_ONLY.txt" ]] || {
    echo "NEXYS_PRODUCT_BD_FAILED: no logical Block Design validation evidence" >&2
    exit 1
  }
  printf '%s\n' "$reference_report" > "$output/reference-source.json"
  printf '%s\n' "$port_report" > "$output/product-axi-ports.json"
  echo "PRODUCT_NEXYS_LOGICAL_BD_ONLY: $output (no P&R, DDR calibration or Debian login)"
fi
