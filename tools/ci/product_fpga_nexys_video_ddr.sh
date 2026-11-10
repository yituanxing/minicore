#!/usr/bin/env bash
# Fixed-source Nexys Video DDR collateral gate. Import-only is NOT a board PASS.
set -euo pipefail

usage() {
  echo "Usage: $0 preflight PINNED_REFERENCE_CHECKOUT RESULT_JSON" >&2
  echo "       $0 import-ddr PINNED_REFERENCE_CHECKOUT NEW_OUTPUT_DIR" >&2
  echo "       $0 integrate-product PINNED_REFERENCE_CHECKOUT PINNED_PRODUCT_EXPORT_DIR NEW_OUTPUT_DIR" >&2
  echo "       $0 physical-product PINNED_REFERENCE_CHECKOUT PINNED_PRODUCT_EXPORT_DIR NEW_OUTPUT_DIR" >&2
  echo "       $0 physical-product-windows PINNED_REFERENCE_CHECKOUT PINNED_PRODUCT_EXPORT_DIR NEW_OUTPUT_DIR [WINDOWS_VIVADO_BAT]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
mode="$1"
if [[ "$mode" == integrate-product || "$mode" == physical-product || "$mode" == physical-product-windows ]]; then
  if [[ "$mode" == physical-product-windows ]]; then
    [[ $# -eq 4 || $# -eq 5 ]] || usage
    windows_vivado_bin="${5:-}"
  else
    [[ $# -eq 4 ]] || usage
  fi
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
case "$mode" in preflight|import-ddr|integrate-product|physical-product|physical-product-windows) ;; *) usage ;; esac

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
if [[ "$mode" == integrate-product || "$mode" == physical-product || "$mode" == physical-product-windows ]]; then
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
if [[ "$mode" == physical-product-windows ]]; then
  # Run the exact same source/RTL preflight in WSL, but pass only Windows
  # drive-backed paths to native Vivado. WSL ext4/UNC projects can be rejected
  # by Windows tools and must not silently fall back to an unverified copy.
  for tool in powershell.exe wslpath realpath mktemp tee; do
    command -v "$tool" >/dev/null 2>&1 || {
      echo "NEXYS_WINDOWS_NOT_READY: $tool unavailable in WSL" >&2
      exit 3
    }
  done
  win_path() {
    local path
    path="$(realpath -m -- "$1")"
    if [[ ! "$path" =~ ^/mnt/[a-zA-Z]/.+ ]]; then
      echo "NEXYS_WINDOWS_NOT_READY: use a shared /mnt/<drive> directory, not WSL ext4/UNC: $path" >&2
      return 3
    fi
    wslpath -w "$path"
  }
  [[ -d "$(dirname "$output")" ]] || {
    echo "NEXYS_WINDOWS_NOT_READY: output parent directory must already exist" >&2
    exit 3
  }
  win_repo="$(win_path "$repo_dir")"
  win_reference="$(win_path "$reference_root")"
  win_rtl="$(win_path "$product_rtl_dir")"
  win_output="$(win_path "$output")"
  win_script="$(win_path "$repo_dir/tools/ci/nexys_video_windows_vivado.ps1")"
  args=(-NoProfile -File "$win_script" -Repository "$win_repo" \
    -Reference "$win_reference" -RtlDir "$win_rtl" -OutputDir "$win_output")
  [[ -z "$windows_vivado_bin" ]] || args+=(-VivadoBin "$windows_vivado_bin")
  logical_log="$(mktemp "${output}.logical-vivado.XXXXXX.log")"
  powershell.exe "${args[@]}" -Phase Logical 2>&1 | tee "$logical_log"
  [[ -s "$output/LOGICAL_BD_ONLY.txt" && -s "$output/aether_nexys_product.xpr" ]] || {
    echo "NEXYS_WINDOWS_LOGICAL_FAILED: no Vivado BD validation evidence; log=$logical_log" >&2
    exit 1
  }
  mv -- "$logical_log" "$output/logical-vivado.log"
  printf '%s\n' "$reference_report" > "$output/reference-source.json"
  printf '%s\n' "$port_report" > "$output/product-axi-ports.json"
  powershell.exe "${args[@]}" -Phase Physical 2>&1 | tee "$output/physical-vivado.log"
  [[ -s "$output/physical_reports/route-evidence.txt" ]] || {
    echo "NEXYS_WINDOWS_PHYSICAL_FAILED: no real Vivado route marker" >&2
    exit 1
  }
  # Native Windows tools must not change the pinned board source, bridge,
  # Chisel inputs, or generated RTL. Verify all three after actual routing.
  python3 "$gate" --reference-root "$reference_root" >/dev/null
  [[ -z "$(git -C "$repo_dir" status --porcelain --untracked-files=all -- \
    src/main build.mill mill tools/ci tools/fpga)" ]] || {
    echo 'NEXYS_WINDOWS_PHYSICAL_FAILED: Windows execution changed pinned source files' >&2
    exit 1
  }
  bash "$repo_dir/tools/ci/product_fpga_artix7_p0.sh" prepare "$product_export_dir" >/dev/null
  python3 "$repo_dir/tools/fpga/product_nexys_axi_port_gate.py" \
    --rtl-dir "$product_rtl_dir" \
    --bridge "$repo_dir/tools/fpga/aether_product_nexys_axi_bridge.sv" >/dev/null
  python3 "$repo_dir/tools/fpga/nexys_video_physical_report_gate.py" \
    --output-dir "$output" --repository "$repo_dir" \
    --product-export-dir "$product_export_dir"
  echo "NEXYS_ROUTED_TIMING_ONLY: $output (Windows Vivado route; not FPGA board boot)"
  exit 0
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
  if [[ "$mode" == physical-product ]]; then
    # A separate Vivado session opens the exact project created above. The
    # logical-only script and its established contract remain unchanged.
    vivado -mode batch -nojournal -nolog \
      -source "$repo_dir/tools/fpga/nexys_video_product_physical.tcl" \
      2>&1 | tee "$output/physical-vivado.log"
    python3 "$repo_dir/tools/fpga/nexys_video_physical_report_gate.py" \
      --output-dir "$output" --repository "$repo_dir" \
      --product-export-dir "$product_export_dir"
    echo "NEXYS_ROUTED_TIMING_ONLY: $output (no bitstream, actual DDR calibration or FPGA Linux boot)"
  fi
fi
