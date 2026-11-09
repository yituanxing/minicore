#!/usr/bin/env bash
# Cache-capacity-specific, immutable Product SD1 board RTL export and bounded mapping.
# Separate from the signed 64/64 production export/checkpoints. Not board timing.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo 'Usage: bash product_fpga_xc7_cache_ab.sh prepare|map 64|256|512 OUTPUT_DIR' >&2
  exit 2
fi
mode="$1"
entries="$2"
output_dir="$3"
case "$mode:$entries" in
  prepare:64|prepare:256|prepare:512|map:64|map:256|map:512) ;;
  *) echo 'ERROR: unreviewed Product cache A/B mode or capacity' >&2; exit 2 ;;
esac

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
for dep in jq sha256sum cmp git; do
  command -v "$dep" >/dev/null || { echo "ERROR: missing $dep" >&2; exit 2; }
done
sha="$(git -C "$repo_dir" rev-parse HEAD)"
if [[ -n "$(git -C "$repo_dir" -c core.filemode=false status --porcelain --untracked-files=normal)" ]]; then
  echo 'ERROR: cache A/B RTL evidence must come from an entirely committed source tree' >&2
  exit 2
fi
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
rtl_dir="$output_dir/rtl"
manifest="$output_dir/manifest.json"
hashes="$output_dir/rtl.sha256"
emitter=aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc
top=AetherCoreProductFpgaUartDdrBoard

verify_export() {
  [[ -s "$manifest" && -s "$hashes" && -d "$rtl_dir" ]] || {
    echo 'ERROR: missing complete cache A/B FPGA RTL export' >&2; exit 2;
  }
  jq -e --arg sha "$sha" --arg emitter "$emitter" --arg top "$top" --argjson entries "$entries" \
    '.status == "RTL_EXPORT_PASS" and .git_sha == $sha and
     .emitter == $emitter and .top == $top and
     .cache_i_entries == $entries and .cache_d_entries == $entries and
     .geometry == "small" and .isa == "rv64gc" and .abi == "lp64d" and
     .spi_sd_enabled == true and .clock_frequency_hz == 50000000 and
     .vendor_ddr_phy_included == false and .pll_included == false and
     .board_xdc_included == false' "$manifest" >/dev/null || {
      echo 'ERROR: incorrect source, top or cache geometry in export manifest' >&2; exit 2;
    }
  (cd "$rtl_dir" && sha256sum -- *.sv | sort) | cmp -s "$hashes" - || {
    echo 'ERROR: cache A/B SystemVerilog file set or contents changed' >&2; exit 2;
  }
  local bundle
  bundle="$(sha256sum "$hashes" | cut -d' ' -f1)"
  jq -e --arg bundle "$bundle" --argjson count "$(find "$rtl_dir" -maxdepth 1 -type f -name '*.sv' | wc -l)" \
    '.rtl_file_hashes_sha256 == $bundle and .rtl_files == $count' "$manifest" >/dev/null || {
      echo 'ERROR: cache A/B bundle identity or file count mismatch' >&2; exit 2;
    }
}

if [[ "$mode" == prepare ]]; then
  if [[ -e "$manifest" || -e "$hashes" || -e "$rtl_dir" ]]; then
    verify_export
    echo "CACHE_AB_RTL_EXPORT_CACHE_HIT: sha=$sha entries=$entries"
    exit 0
  fi
  mkdir -p "$rtl_dir"
  (cd "$repo_dir" && sh ./mill aethercore.runMain "$emitter" \
    small "$entries" "$entries" --target-dir "$rtl_dir")
  mapfile -t svs < <(find "$rtl_dir" -maxdepth 1 -type f -name '*.sv' -print | sort)
  ((${#svs[@]} > 0)) || { echo 'ERROR: no generated Product SV' >&2; exit 2; }
  grep -Eq "module[[:space:]]+$top([[:space:]]|\()" "${svs[@]}" || {
    echo 'ERROR: generated SV does not contain full physical SD1 board top' >&2; exit 2;
  }
  (cd "$rtl_dir" && sha256sum -- *.sv | sort) > "$hashes"
  bundle="$(sha256sum "$hashes" | cut -d' ' -f1)"
  jq -n --arg sha "$sha" --arg emitter "$emitter" --arg top "$top" \
    --arg bundle "$bundle" --argjson entries "$entries" --argjson count "${#svs[@]}" \
    '{status:"RTL_EXPORT_PASS", git_sha:$sha, emitter:$emitter, top:$top,
      geometry:"small", decode_width:1, issue_width:1, retire_width:1,
      cache_i_entries:$entries, cache_d_entries:$entries, cache_line_bytes:8,
      isa:"rv64gc", abi:"lp64d", sv39:true, spi_sd_enabled:true,
      clock_frequency_hz:50000000, paddr_bits:32,
      rtl_files:$count, rtl_file_hashes_sha256:$bundle,
      vendor_ddr_phy_included:false, pll_included:false, board_xdc_included:false,
      qualification:"Explicit cache A/B RTL export only; no timing, device fit or Debian boot"}' > "$manifest"
  verify_export
  echo "CACHE_AB_RTL_EXPORT_PASS: sha=$sha entries=$entries bundle=$bundle"
  exit 0
fi

verify_export
[[ -z "${AETHERCORE_XC7_CACHE_AB_ENTRIES:-}" || "$AETHERCORE_XC7_CACHE_AB_ENTRIES" == "$entries" ]] || {
  echo 'ERROR: cache A/B mapping environment contradicts pinned manifest' >&2; exit 2;
}
export AETHERCORE_XC7_CACHE_AB_ENTRIES="$entries"
exec bash "$repo_dir/tools/ci/product_fpga_xc7_unplaced.sh" "$output_dir" "$output_dir/mapped"
