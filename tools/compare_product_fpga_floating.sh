#!/usr/bin/env bash
# Opt-in, board-neutral FPGA area/depth comparison of the *same* SmallOoO core
# with F/D disabled versus enabled. Not a Linux boot or placed/routed Fmax gate.
set -euo pipefail

if [[ $# -ne 2 || ( "${1:-}" != small && "${1:-}" != expanded ) ]]; then
  echo "Usage: $0 small|expanded OUTPUT_DIRECTORY" >&2
  exit 2
fi

capacity="$1"
output_dir="$2"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
command -v yosys >/dev/null || { echo 'yosys is required' >&2; exit 2; }
command -v jq >/dev/null || { echo 'jq is required' >&2; exit 2; }

# Deliberately do not turn on both capacities on every PR push. The caller
# chooses one capacity, so expensive synthesis occurs only when requested.
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
for isa in integer floating; do
  run_dir="$output_dir/$capacity/$isa"
  rtl_dir="$run_dir/rtl"
  mkdir -p "$rtl_dir"
  ./mill aethercore.runMain aethercore.ElaborateProductFloatingSlice \
    "$capacity" "$isa" --target-dir "$rtl_dir"
  mapfile -t svs < <(find "$rtl_dir" -maxdepth 1 -name '*.sv' -print | sort)
  if [[ ${#svs[@]} -eq 0 ]]; then
    echo "No RTL generated for $capacity/$isa" >&2
    exit 1
  fi

  read_cmd='read_verilog -sv -DSYNTHESIS'
  for sv in "${svs[@]}"; do
    # Quoted Yosys file paths also work for spaces in the output directory.
    read_cmd+=" \"${sv//\"/\\\"}\""
  done
  top=ProductSmallMemoryCore
  yosys -Q -p "$read_cmd; hierarchy -check -top $top; synth_ecp5 -top $top -json $run_dir/netlist.json; stat -top $top; flatten; ltp -noff $top" \
    > "$run_dir/yosys.log" 2>&1

  sed -n "s/.*Longest topological path in $top (length=\([0-9][0-9]*\)).*/\1/p" \
    "$run_dir/yosys.log" | tail -1 > "$run_dir/depth"
  test -s "$run_dir/depth"
  jq -n \
    --arg capacity "$capacity" --arg isa "$isa" \
    --argjson cells "$(jq '[.modules["'"$top"'"].cells[]] | length' "$run_dir/netlist.json")" \
    --argjson lut4 "$(jq '[.modules["'"$top"'"].cells[] | select(.type == "LUT4")] | length' "$run_dir/netlist.json")" \
    --argjson ff "$(jq '[.modules["'"$top"'"].cells[] | select(.type == "TRELLIS_FF")] | length' "$run_dir/netlist.json")" \
    --argjson bram "$(jq '[.modules["'"$top"'"].cells[] | select(.type == "DP16KD")] | length' "$run_dir/netlist.json")" \
    --argjson dpr "$(jq '[.modules["'"$top"'"].cells[] | select(.type == "TRELLIS_DPR16X4")] | length' "$run_dir/netlist.json")" \
    --argjson dsp "$(jq '[.modules["'"$top"'"].cells[] | select(.type == "MULT18X18D")] | length' "$run_dir/netlist.json")" \
    --argjson depth "$(<"$run_dir/depth")" \
    '{capacity: $capacity, isa: $isa, cells: $cells, lut4: $lut4, ff: $ff, bram: $bram, dpr: $dpr, dsp: $dsp, depth: $depth}' \
    > "$run_dir/metrics.json"
done

python3 tools/report_product_fpga_floating_cost.py "$capacity" "$output_dir"
