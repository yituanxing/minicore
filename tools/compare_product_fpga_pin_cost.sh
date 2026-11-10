#!/usr/bin/env bash
# Opt-in, exact-geometry full-SoC synthesis: physical pins versus the unchanged
# differential/probe top, with RV64IMAC and RV64GC on the SAME CPU/board path.
# Neither Yosys synth_ecp5 nor its ltp proxy establishes real-board Fmax.
set -euo pipefail

if [[ ( $# -ne 2 && $# -ne 4 ) ||
      ( "${1:-}" != small && "${1:-}" != expanded ) ]]; then
  echo "Usage: $0 small|expanded OUTPUT_DIRECTORY [integer|floating probe|pins]" >&2
  exit 2
fi

# The two-argument interface remains the local, complete 4-way comparison.
# CI isolates the same four variants into independent runners so a killed
# synthesis host cannot discard the other variants' completed measurements.
selected_isa="${3:-}"
selected_boundary="${4:-}"
if [[ $# -eq 4 ]]; then
  case "${selected_isa}/${selected_boundary}" in
    integer/probe|integer/pins|floating/probe|floating/pins) ;;
    *) echo 'ERROR: invalid ISA/boundary variant' >&2; exit 2 ;;
  esac
fi

capacity="$1"
output_dir="$2"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
command -v yosys >/dev/null || { echo 'yosys is required' >&2; exit 2; }
command -v jq >/dev/null || { echo 'jq is required' >&2; exit 2; }

# A hosted synthesis worker can disappear without reaching the artifact upload
# step. Keep the complete log on disk, but send bounded stage and resource
# breadcrumbs to the live GitHub log so a runner shutdown is diagnosable.
# This observer never changes the Yosys command or its exit status.
monitor_yosys_resources() {
  local synth_pid="$1"
  local started=$SECONDS
  local status_path="/proc/$synth_pid/status"
  local rss_kib peak_rss_kib available_kib cgroup_bytes cgroup_limit oom_kill
  while kill -0 "$synth_pid" 2>/dev/null; do
    rss_kib="$(awk '$1 == "VmRSS:" { print $2 }' "$status_path" 2>/dev/null || true)"
    peak_rss_kib="$(awk '$1 == "VmHWM:" { print $2 }' "$status_path" 2>/dev/null || true)"
    available_kib="$(awk '$1 == "MemAvailable:" { print $2 }' /proc/meminfo 2>/dev/null || true)"
    cgroup_bytes=unavailable
    cgroup_limit=unavailable
    oom_kill=unavailable
    if [[ -r /sys/fs/cgroup/memory.current ]]; then
      cgroup_bytes="$(< /sys/fs/cgroup/memory.current)"
    fi
    if [[ -r /sys/fs/cgroup/memory.max ]]; then
      cgroup_limit="$(< /sys/fs/cgroup/memory.max)"
    fi
    if [[ -r /sys/fs/cgroup/memory.events ]]; then
      oom_kill="$(awk '$1 == "oom_kill" { print $2 }' /sys/fs/cgroup/memory.events)"
    fi
    printf 'PPA_YOSYS_RESOURCE: elapsed_s=%s pid=%s rss_kib=%s peak_rss_kib=%s mem_available_kib=%s cgroup_bytes=%s cgroup_limit=%s oom_kill=%s\n' \
      "$((SECONDS - started))" "$synth_pid" "${rss_kib:-unavailable}" \
      "${peak_rss_kib:-unavailable}" "${available_kib:-unavailable}" \
      "$cgroup_bytes" "$cgroup_limit" "${oom_kill:-unavailable}"
    sleep 20
  done
}

# Full Yosys logs can be tens of MB. Publish at most 800 stage messages and
# 30 warnings/errors, plus periodic bounded resource observations. The exact
# full transcript remains in yosys.log for successfully completed workers.
publish_yosys_progress() {
  awk '
    /^PPA_YOSYS_RESOURCE:/ { print; fflush(); next }
    /^[[:digit:]]+(\.[[:digit:]]+)*\. Executing / {
      if (++stages <= 800) { print "PPA_YOSYS_STAGE: " $0; fflush() }
      else if (stages == 801) { print "PPA_YOSYS_STAGE: further stages suppressed"; fflush() }
      next
    }
    /(Warning:|ERROR:|fatal:|Longest topological path|End of script)/ {
      if (++diagnostics <= 30) { print "PPA_YOSYS_DIAGNOSTIC: " $0; fflush() }
      else if (diagnostics == 31) { print "PPA_YOSYS_DIAGNOSTIC: further messages suppressed"; fflush() }
    }
  '
}

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

for isa in integer floating; do
  [[ -z "$selected_isa" || "$isa" == "$selected_isa" ]] || continue
  for boundary in probe pins; do
    [[ -z "$selected_boundary" || "$boundary" == "$selected_boundary" ]] || continue
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
    echo "PPA_YOSYS_START: isa=$isa boundary=$boundary capacity=$capacity top=$top"
    (
      yosys -Q -p "$read_cmd; hierarchy -check -top $top; synth_ecp5 -top $top -json $run_dir/netlist.json; stat -top $top; flatten; ltp -noff $top" &
      synth_pid=$!
      monitor_yosys_resources "$synth_pid" &
      monitor_pid=$!
      if wait "$synth_pid"; then
        synth_status=0
      else
        synth_status=$?
      fi
      kill "$monitor_pid" 2>/dev/null || true
      wait "$monitor_pid" 2>/dev/null || true
      exit "$synth_status"
    ) 2>&1 | tee "$run_dir/yosys.log" | publish_yosys_progress
    echo "PPA_YOSYS_SUCCESS: isa=$isa boundary=$boundary capacity=$capacity"
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

if [[ $# -eq 2 ]]; then
  python3 tools/report_product_fpga_pin_cost.py "$capacity" "$output_dir"
else
  test -s "$output_dir/$capacity/$selected_isa/$selected_boundary/metrics.json"
  echo "PRODUCT_FPGA_PHYSICAL_VARIANT_PASS: capacity=$capacity isa=$selected_isa boundary=$selected_boundary"
fi
