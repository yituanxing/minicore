#!/usr/bin/env bash
# Opt-in, exact-geometry full-SoC synthesis: physical pins versus the unchanged
# differential/probe top, with RV64IMAC and RV64GC on the SAME CPU/board path.
# Neither Yosys synth_ecp5 nor its ltp proxy establishes real-board Fmax.
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

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

for isa in integer floating; do
  for boundary in probe pins; do
    run_dir="$output_dir/$capacity/$isa/$boundary"
    rtl_dir="$run_dir/rtl"
    mkdir -p "$rtl_dir"

    case "$isa/$boundary" in
      integer/probe) emitter=ElaborateProductFpgaSoCGeometry; top=AetherCoreProductFpgaSoC ;;
      integer/pins) emitter=ElaborateProductFpgaPinsGeometry; top=AetherCoreProductFpgaPins ;;
      floating/probe) emitter=ElaborateProductFpgaSoCRv64gc; top=AetherCoreProductFpgaSoC ;;
      floating/pins) emitter=ElaborateProductFpgaPinsRv64gc; top=AetherCoreProductFpgaPins ;;
    esac

    ./mill aethercore.runMain "aethercore.soc.$emitter" \
      "$capacity" --target-dir "$rtl_dir"
    mapfile -t svs < <(find "$rtl_dir" -maxdepth 1 -name '*.sv' -print | sort)
    if [[ ${#svs[@]} -eq 0 ]]; then
      echo "ERROR: no RTL for $capacity/$isa/$boundary" >&2
      exit 1
    fi

    read_cmd='read_verilog -sv -DSYNTHESIS'
    for sv in "${svs[@]}"; do
      read_cmd+=" \"${sv//\"/\\\"}\""
    done
    yosys -Q -p "$read_cmd; hierarchy -check -top $top; synth_ecp5 -top $top -json $run_dir/netlist.json; stat -top $top; flatten; ltp -noff $top" \
      > "$run_dir/yosys.log" 2>&1
    sed -n "s/.*Longest topological path in $top (length=\([0-9][0-9]*\)).*/\1/p" \
      "$run_dir/yosys.log" | tail -1 > "$run_dir/depth"
    test -s "$run_dir/depth"

    netlist="$run_dir/netlist.json"
    jq -n \
      --arg capacity "$capacity" --arg isa "$isa" --arg boundary "$boundary" \
      --arg top "$top" \
      --argjson port_bits "$(jq --arg t "$top" '[.modules[$t].ports[].bits | length] | add // 0' "$netlist")" \
      --argjson cells "$(jq --arg t "$top" '[.modules[$t].cells[]] | length' "$netlist")" \
      --argjson lut4 "$(jq --arg t "$top" '[.modules[$t].cells[] | select(.type == "LUT4")] | length' "$netlist")" \
      --argjson ff "$(jq --arg t "$top" '[.modules[$t].cells[] | select(.type == "TRELLIS_FF")] | length' "$netlist")" \
      --argjson bram "$(jq --arg t "$top" '[.modules[$t].cells[] | select(.type == "DP16KD")] | length' "$netlist")" \
      --argjson dpr "$(jq --arg t "$top" '[.modules[$t].cells[] | select(.type == "TRELLIS_DPR16X4")] | length' "$netlist")" \
      --argjson dsp "$(jq --arg t "$top" '[.modules[$t].cells[] | select(.type == "MULT18X18D")] | length' "$netlist")" \
      --argjson depth "$(<"$run_dir/depth")" \
      '{capacity: $capacity, isa: $isa, boundary: $boundary, top: $top,
        port_bits: $port_bits, cells: $cells, lut4: $lut4, ff: $ff,
        bram: $bram, dpr: $dpr, dsp: $dsp, depth: $depth}' \
      > "$run_dir/metrics.json"
  done
done

python3 tools/report_product_fpga_pin_cost.py "$capacity" "$output_dir"
