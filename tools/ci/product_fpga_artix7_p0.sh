#!/usr/bin/env bash
# Reproducible, board-neutral Product RV64GC Artix-7 feasibility gate.
# This is NOT a physical DDR/PLL/XDC implementation or a board Fmax result.
set -euo pipefail

usage() {
  echo "Usage: $0 prepare OUTPUT_DIR | synth OUTPUT_DIR xc7a100tfgg676-2|xc7a200tfbg676-2" >&2
  exit 2
}

[[ $# -ge 2 && $# -le 3 ]] || usage
mode="$1"
output_dir="$2"
part="${3:-}"
case "$mode" in
  prepare) [[ $# -eq 2 ]] || usage ;;
  synth)
    [[ $# -eq 3 ]] || usage
    case "$part" in
      xc7a100tfgg676-2|xc7a200tfbg676-2) ;;
      *) echo "ERROR: unsupported or unreviewed Artix-7 part: $part" >&2; usage ;;
    esac
    ;;
  *) usage ;;
esac

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v jq >/dev/null || { echo 'ERROR: jq is required' >&2; exit 2; }
command -v sha256sum >/dev/null || { echo 'ERROR: sha256sum is required' >&2; exit 2; }
current_sha="$(git -C "$repo_dir" rev-parse HEAD)"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
rtl_dir="$output_dir/rtl"
manifest="$output_dir/manifest.json"
hashes="$output_dir/rtl.sha256"
top=AetherCoreProductFpgaUartDdrBoard
emitter=aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc

verify_export() {
  [[ -s "$manifest" && -s "$hashes" && -d "$rtl_dir" ]] || {
    echo 'ERROR: incomplete RTL export; prepare on a fresh output directory' >&2
    exit 1
  }
  jq -e --arg sha "$current_sha" --arg top "$top" --arg emitter "$emitter" \
    '.status == "RTL_EXPORT_PASS" and .git_sha == $sha and .top == $top and
     .emitter == $emitter and .geometry == "small" and
     .isa == "rv64gc" and .abi == "lp64d" and
     .spi_sd_enabled == true and .vendor_ddr_phy_included == false' \
    "$manifest" >/dev/null || {
      echo 'ERROR: RTL export identity differs from the checked-out Product source' >&2
      exit 1
    }
  (cd "$rtl_dir" && sha256sum --status --check "$hashes") || {
    echo 'ERROR: emitted RTL bytes differ from their pinned SHA256 manifest' >&2
    exit 1
  }
  local bundle_sha
  bundle_sha="$(sha256sum "$hashes" | cut -d' ' -f1)"
  jq -e --arg bundle "$bundle_sha" '.rtl_file_hashes_sha256 == $bundle' \
    "$manifest" >/dev/null || {
      echo 'ERROR: RTL hash list differs from the pinned bundle identity' >&2
      exit 1
    }
}

if [[ "$mode" == prepare ]]; then
  if [[ -e "$manifest" || -e "$hashes" || -e "$rtl_dir" ]]; then
    verify_export
    echo "RTL_EXPORT_CACHE_HIT: commit=$current_sha manifest=$manifest"
    exit 0
  fi
  [[ -f "$repo_dir/mill" ]] || { echo 'ERROR: repository Mill launcher is missing' >&2; exit 2; }
  mkdir -p "$rtl_dir"
  (cd "$repo_dir" && sh ./mill aethercore.runMain "$emitter" small --target-dir "$rtl_dir")
  mapfile -t svs < <(find "$rtl_dir" -maxdepth 1 -type f -name '*.sv' -print | sort)
  ((${#svs[@]} > 0)) || { echo 'ERROR: Chisel emitted no SystemVerilog' >&2; exit 1; }
  rg -q "module[[:space:]]+$top([[:space:]]|\()" "${svs[@]}" || {
    echo "ERROR: emitted RTL does not contain expected Product top $top" >&2
    exit 1
  }
  (cd "$rtl_dir" && sha256sum -- *.sv | sort) > "$hashes"
  bundle_sha="$(sha256sum "$hashes" | cut -d' ' -f1)"
  jq -n \
    --arg sha "$current_sha" --arg top "$top" --arg emitter "$emitter" \
    --arg bundle "$bundle_sha" --argjson count "${#svs[@]}" \
    '{status:"RTL_EXPORT_PASS", git_sha:$sha, top:$top, emitter:$emitter,
      geometry:"small", decode_width:1, issue_width:1, retire_width:1,
      isa:"rv64gc", abi:"lp64d", sv39:true, spi_sd_enabled:true,
      paddr_bits:32, rtl_files:$count, rtl_file_hashes_sha256:$bundle,
      vendor_ddr_phy_included:false, pll_included:false, board_xdc_included:false,
      qualification:"RTL export only; not synthesis, routed timing or FPGA boot"}' \
    > "$manifest"
  verify_export
  echo "RTL_EXPORT_PASS: commit=$current_sha files=${#svs[@]} sha256=$bundle_sha"
  exit 0
fi

verify_export
command -v vivado >/dev/null || {
  echo 'ARTIX7_SYNTH_NOT_RUN: Vivado unavailable; no device utilization or Fmax is claimed' >&2
  exit 3
}
run_dir="$output_dir/artix7/$part"
[[ ! -e "$run_dir/result.json" ]] || {
  echo "ERROR: refusing to overwrite completed synthesis evidence: $run_dir" >&2
  exit 1
}
mkdir -p "$run_dir"
script="$repo_dir/tools/fpga/artix7_product_synth.tcl"
[[ -f "$script" ]] || { echo 'ERROR: missing pinned Vivado Tcl flow' >&2; exit 2; }
export ARTIX7_PART="$part" ARTIX7_RTL_DIR="$rtl_dir" ARTIX7_OUTPUT_DIR="$run_dir"
vivado -mode batch -nojournal -nolog -source "$script" 2>&1 | tee "$run_dir/vivado.log"
for report in utilization.rpt timing_synth.rpt synth.dcp; do
  [[ -s "$run_dir/$report" ]] || {
    echo "ERROR: Vivado returned without required evidence: $report" >&2
    exit 1
  }
done
jq -n --arg sha "$current_sha" --arg part "$part" --arg top "$top" \
  --arg bundle "$(sha256sum "$hashes" | cut -d' ' -f1)" \
  '{status:"ARTIX7_LOGIC_SYNTH_ONLY", git_sha:$sha, part:$part, top:$top,
    rtl_file_hashes_sha256:$bundle, period_ns:40,
    routed:false, board_ddr_phy_included:false, physical_timing_qualified:false,
    qualification:"Out-of-context logic synthesis only; not routed Fmax or board DDR"}' \
  > "$run_dir/result.json"
echo "ARTIX7_LOGIC_SYNTH_ONLY: part=$part reports=$run_dir"
