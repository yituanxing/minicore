#!/usr/bin/env bash
# Exact-device Product Small RV64GC 7-Series candidate gate.
# OOC logic synthesis is NOT a routed board fit, a bitstream, or Linux PASS.
set -euo pipefail

usage() {
  echo "Usage: $0 prepare OUTPUT_DIR | probe|synth|board-gate OUTPUT_DIR xc7k325tffg676-2|xc7a200tfbg484-2|xc7a200tsbg484-1|xc7k325tffg900-2" >&2
  exit 2
}

[[ $# -ge 2 && $# -le 3 ]] || usage
mode="$1"
output_dir="$2"
part="${3:-}"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export_gate="$repo_dir/tools/ci/product_fpga_artix7_p0.sh"

# The legacy SHA-pinned export cache checks committed identity and RTL bytes.
# A dirty Scala/build/tooling tree could otherwise reuse old RTL under the
# same commit SHA. This additional source gate refuses that ambiguity.
if [[ -n "$(git -C "$repo_dir" status --porcelain --untracked-files=all -- \
  src/main build.mill mill tools/ci tools/fpga)" ]]; then
  echo 'ERROR: Product RTL source/tooling worktree is dirty; commit before candidate comparisons' >&2
  exit 1
fi

if [[ "$mode" == prepare ]]; then
  [[ $# -eq 2 ]] || usage
  exec bash "$export_gate" prepare "$output_dir"
fi

[[ $# -eq 3 ]] || usage
case "$mode" in probe|synth|board-gate) ;; *) usage ;; esac
case "$part" in
  xc7k325tffg676-2|xc7a200tfbg484-2|xc7a200tsbg484-1|xc7k325tffg900-2) ;;
  *) echo "ERROR: unreviewed candidate device/packaging: $part" >&2; exit 2 ;;
esac

if [[ "$mode" == board-gate ]]; then
  # No vendor XDC/MIG/PLL/CDC physical wrapper exists in this source tree.
  # Merely passing an arbitrary vendor path cannot establish board provenance.
  echo "BOARD_PHYSICAL_INPUTS_MISSING: $part" >&2
  echo 'Required: revision-matched schematic, complete pin/timing XDC, MIG .prj/IP, PLL/CDC and reviewed board wrapper.' >&2
  echo 'Then require place_design, route_design, post-route WNS >= 0, DDR calibration and actual FPGA serial Linux login.' >&2
  exit 4
fi

command -v jq >/dev/null || { echo 'ERROR: jq required' >&2; exit 2; }
[[ -s "$output_dir/manifest.json" && -s "$output_dir/rtl.sha256" && -d "$output_dir/rtl" ]] || {
  echo 'ERROR: RTL export missing; run prepare explicitly before probe/synth' >&2
  exit 1
}
# Reuse the existing strict git-SHA, full SV input-set and byte-hash verifier;
# do not regenerate changed RTL and call it the same experiment.
bash "$export_gate" prepare "$output_dir" >/dev/null
output_dir="$(cd "$output_dir" && pwd)"
command -v vivado >/dev/null || {
  echo "SEVEN_SERIES_${mode^^}_NOT_RUN: Vivado unavailable; no exact-device or routed claim" >&2
  exit 3
}

run_dir="$output_dir/7series/$part/$mode"
[[ ! -e "$run_dir/result.json" ]] || {
  echo "ERROR: refusing to overwrite completed candidate evidence: $run_dir" >&2
  exit 1
}
mkdir -p "$run_dir"
export SEVEN_SERIES_PART="$part" SEVEN_SERIES_RTL_DIR="$output_dir/rtl"
export SEVEN_SERIES_OUTPUT_DIR="$run_dir" SEVEN_SERIES_MODE="$mode"
vivado -mode batch -nojournal -nolog \
  -source "$repo_dir/tools/fpga/product_7series_candidate.tcl" 2>&1 | tee "$run_dir/vivado.log"
if [[ "$mode" == synth ]]; then
  for report in utilization.rpt timing_synth.rpt synth.dcp; do
    [[ -s "$run_dir/$report" ]] || {
      echo "ERROR: Vivado omitted evidence: $report" >&2
      exit 1
    }
  done
fi
[[ -s "$run_dir/device.txt" ]] || { echo 'ERROR: exact Vivado device probe absent' >&2; exit 1; }
[[ "$(<"$run_dir/device.txt")" == "$part" ]] || {
  echo 'ERROR: Vivado device probe did not match requested part' >&2
  exit 1
}
status=SEVEN_SERIES_DEVICE_PROBE_ONLY
[[ "$mode" == synth ]] && status=SEVEN_SERIES_LOGIC_SYNTH_ONLY
jq -n --arg sha "$(git -C "$repo_dir" rev-parse HEAD)" \
  --arg part "$part" --arg status "$status" \
  --arg bundle "$(sha256sum "$output_dir/rtl.sha256" | cut -d' ' -f1)" \
  '{status:$status, git_sha:$sha, part:$part,
    top:"AetherCoreProductFpgaUartDdrBoard", rtl_file_hashes_sha256:$bundle,
    period_ns:20, clock_frequency_hz:50000000,
    board_revision_verified:false, mig_included:false, pll_cdc_included:false,
    routed:false, physical_timing_qualified:false, fpga_boot_verified:false,
    qualification:"Exact-device probe or out-of-context logic only; not board place/route, DDR calibration or Debian boot"}' \
  > "$run_dir/result.json"
echo "$status: part=$part evidence=$run_dir"
