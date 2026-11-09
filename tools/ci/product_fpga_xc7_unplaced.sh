#!/usr/bin/env bash
# One bounded, real Yosys Xilinx 7-series logic-mapping experiment.
# Not device placement, routed Fmax, DDR PHY support, or FPGA board bring-up.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 VERIFIED_RTL_EXPORT_DIR OUTPUT_DIR" >&2
  exit 2
fi
export_dir="$1"
output_dir="$2"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v yosys >/dev/null || { echo 'ERROR: yosys is required' >&2; exit 2; }
command -v jq >/dev/null || { echo 'ERROR: jq is required' >&2; exit 2; }
command -v sha256sum >/dev/null || { echo 'ERROR: sha256sum is required' >&2; exit 2; }
command -v cmp >/dev/null || { echo 'ERROR: cmp is required' >&2; exit 2; }
command -v python3 >/dev/null || { echo 'ERROR: python3 is required for XC7 hierarchy stats' >&2; exit 2; }
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
export_dir="$(cd "$export_dir" && pwd)"
rtl_dir="$export_dir/rtl"
top=AetherCoreProductFpgaUartDdrBoard
sha="$(git -C "$repo_dir" rev-parse HEAD)"
cache_ab_entries="${AETHERCORE_XC7_CACHE_AB_ENTRIES:-}"
log="$output_dir/yosys.log"
stats="$output_dir/stats.json"
result="$output_dir/result.json"
guard="$output_dir/abort_reason.txt"

[[ ! -e "$result" && ! -e "$stats" && ! -e "$guard" ]] || {
  echo 'ERROR: refusing to overwrite existing XC7 mapping evidence' >&2
  exit 2
}
[[ -s "$export_dir/manifest.json" && -s "$export_dir/rtl.sha256" && -d "$rtl_dir" ]] || {
  echo 'ERROR: missing verified Product RTL; run exact-SHA Artix-7 prepare first' >&2
  exit 2
}
if [[ -z "$cache_ab_entries" ]]; then
  emitter=aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc
else
  case "$cache_ab_entries" in
    64|256|512) ;;
    *) echo 'ERROR: unreviewed Product cache A/B mapping profile' >&2; exit 2 ;;
  esac
  emitter=aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc
fi
jq -e --arg sha "$sha" --arg top "$top" --arg emitter "$emitter" \
  --arg entries "$cache_ab_entries" \
  '.status == "RTL_EXPORT_PASS" and .git_sha == $sha and .top == $top and
   .emitter == $emitter and
   .isa == "rv64gc" and .abi == "lp64d" and .geometry == "small" and
   .spi_sd_enabled == true and .clock_frequency_hz == 50000000 and
   .vendor_ddr_phy_included == false and
   (if $entries == "" then (.cache_i_entries == null and .cache_d_entries == null)
    else (.cache_i_entries == ($entries | tonumber) and .cache_d_entries == ($entries | tonumber)) end)' \
  "$export_dir/manifest.json" >/dev/null || {
    echo 'ERROR: this RTL export is not pinned to the checked-out Product Small RV64GC' >&2
    exit 2
  }
# Re-check the complete root-level synthesis file set. The companion
# prepare command also checks the pinned SHA256 manifest and bundle digest.
(cd "$rtl_dir" && sha256sum -- *.sv | sort) | cmp -s "$export_dir/rtl.sha256" - || {
  echo 'ERROR: XC7 synthesis inputs differ from the pinned RTL file set' >&2
  exit 2
}
mapfile -t svs < <(find "$rtl_dir" -maxdepth 1 -type f -name '*.sv' -print | sort)
((${#svs[@]} > 0)) || { echo 'ERROR: no synthesizable Product SV files' >&2; exit 2; }
jq -e --argjson count "${#svs[@]}" '.rtl_files == $count' "$export_dir/manifest.json" >/dev/null || {
  echo 'ERROR: Product RTL file count disagrees with pinned manifest' >&2
  exit 2
}

# Build an explicit input list. Yosys's command interpreter must not be asked
# to expand an unquoted rtl/*.sv glob, and only these files are synthesized.
read_cmd='read_verilog -sv -DSYNTHESIS'
for sv in "${svs[@]}"; do
  read_cmd+=" \"${sv//\"/\\\"}\""
done
# Yosys tee opens the raw argument, including literal quote characters.
# GitHub RUNNER_TEMP has no whitespace; reject spaces and pass an unquoted
# filename token, or the entire mapping can finish but stats export fail.
[[ "$stats" != *[[:space:]]* ]] || {
  echo 'ERROR: XC7 output path must not contain whitespace (Yosys tee)' >&2
  exit 2
}
flow="$read_cmd; hierarchy -check -top $top; synth_xilinx -family xc7 -noiopad -top $top; tee -o $stats stat -json -tech xilinx; stat -tech xilinx"

# The old floating ECP5 SHARE pass exhausted a 16 GiB hosted runner. Observe
# the exact XC7 process and cgroup live, then stop before the OS kills the job
# and loses its failure evidence. This is a deliberately bounded single lane.
monitor_mapping() {
  local pid="$1" started=$SECONDS elapsed rss peak current limit
  while kill -0 "$pid" 2>/dev/null; do
    elapsed="$((SECONDS - started))"
    rss="$(awk '$1 == "VmRSS:" { print $2 }' "/proc/$pid/status" 2>/dev/null || true)"
    peak="$(awk '$1 == "VmHWM:" { print $2 }' "/proc/$pid/status" 2>/dev/null || true)"
    current=unavailable
    limit=unavailable
    [[ ! -r /sys/fs/cgroup/memory.current ]] || current="$(< /sys/fs/cgroup/memory.current)"
    [[ ! -r /sys/fs/cgroup/memory.max ]] || limit="$(< /sys/fs/cgroup/memory.max)"
    printf 'XC7_RESOURCE: elapsed_s=%s rss_kib=%s peak_rss_kib=%s cgroup_bytes=%s cgroup_limit=%s\n' \
      "$elapsed" "${rss:-unavailable}" "${peak:-unavailable}" "$current" "$limit"
    if ((elapsed >= 420)); then
      echo 'TIME_GUARD_420_SECONDS' > "$guard"
    elif [[ "$rss" =~ ^[0-9]+$ ]] && ((rss >= 12582912)); then
      echo 'RSS_GUARD_12_GIB' > "$guard"
    elif [[ "$current" =~ ^[0-9]+$ && "$limit" =~ ^[0-9]+$ ]] &&
         ((current * 100 >= limit * 84)); then
      echo 'CGROUP_GUARD_84_PERCENT' > "$guard"
    fi
    if [[ -s "$guard" ]]; then
      echo "XC7_MAPPING_ABORT: $(<"$guard")" >&2
      kill -TERM "$pid" 2>/dev/null || true
      sleep 3
      kill -KILL "$pid" 2>/dev/null || true
      break
    fi
    sleep 8
  done
}

echo "XC7_MAPPING_START: sha=$sha top=$top sv_files=${#svs[@]} family=xc7 io_pads=disabled"
yosys -Q -p "$flow" > "$log" 2>&1 &
synth_pid=$!
monitor_mapping "$synth_pid" &
monitor_pid=$!
if wait "$synth_pid"; then
  rc=0
else
  rc=$?
fi
kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true

# Yosys synth_xilinx prints real hierarchy-wide cell counts before its final
# report-writing step. Parse and cross-check every mapped primitive against
# the weighted instances. Keep this diagnostic even if a later tee/stat
# command fails; it is never a substitute for a successful Yosys exit.
python3 "$repo_dir/tools/ci/product_fpga_xc7_hierarchy.py" \
  "$log" "$output_dir/hierarchy.json" || true

if ((rc != 0)) || [[ -s "$guard" ]]; then
  reason="YOSYS_EXIT_$rc"
  [[ ! -s "$guard" ]] || reason="$(<"$guard")"
  jq -n --arg sha "$sha" --arg reason "$reason" \
    '{status:"XC7_UNPLACED_MAPPING_FAILED",git_sha:$sha,reason:$reason,
      qualification:"No resource result; bounded Xilinx 7-series mapping did not complete"}' \
    > "$result"
  echo "XC7_UNPLACED_MAPPING_FAILED: $reason; inspect $log" >&2
  exit 1
fi
if [[ ! -s "$stats" ]] || ! jq -e '
  type == "object" and
  (.modules | type == "object" and length > 0) and
  ([.modules[] | select((.num_cells // 0) > 0 or
    ((.num_cells_by_type // {}) | length > 0))] | length > 0)
' "$stats" >/dev/null; then
  jq -n --arg sha "$sha" \
    '{status:"XC7_UNPLACED_MAPPING_FAILED",git_sha:$sha,reason:"MISSING_VALID_STAT_JSON"}' > "$result"
  echo 'XC7_UNPLACED_MAPPING_FAILED: no valid Yosys cell statistics' >&2
  exit 1
fi
jq -n --arg sha "$sha" --arg top "$top" --arg version "$(yosys -V)" \
  --arg cache_entries "$cache_ab_entries" --arg emitter "$emitter" \
  --argjson rtl_files "${#svs[@]}" \
  --arg rtl_bundle "$(sha256sum "$export_dir/rtl.sha256" | cut -d' ' -f1)" \
  '{status:"XC7_UNPLACED_MAPPING_PASS",git_sha:$sha,top:$top,emitter:$emitter,
    cache_i_entries:(if $cache_entries == "" then null else ($cache_entries | tonumber) end),
    cache_d_entries:(if $cache_entries == "" then null else ($cache_entries | tonumber) end),
    yosys_version:$version,family:"xc7",io_pads:false,rtl_files:$rtl_files,
    rtl_file_hashes_sha256:$rtl_bundle,resource_report:"stats.json",
    device_part:null,placed:false,routed:false,physical_timing_qualified:false,
    qualification:"Xilinx 7-series mapped cells only; not device fit, routed Fmax or board boot"}' \
  > "$result"
echo "XC7_UNPLACED_MAPPING_PASS: stats=$stats log=$log"
