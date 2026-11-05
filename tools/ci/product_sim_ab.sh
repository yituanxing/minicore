#!/usr/bin/env bash
set -euo pipefail

# Explicit, SHORT Product RTL A/B qualification; intentionally never called by
# PR push/pull_request. Three identical-cycle Product runs qualify BOTH the
# change from 5.024 to 5.054 and the optional adaptive settle loop. Never
# promote a faster version using a historical V2/QEMU measurement as proof.

if [[ "${AETHERCORE_ALLOW_PRODUCT_AB:-0}" != 1 ]]; then
  echo 'Product simulator A/B requires explicit AETHERCORE_ALLOW_PRODUCT_AB=1' >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
top="${TOP:-AetherCoreProductDebianRV64SimTop}"
elaborate="${ELABORATE_MAIN:-aethercore.sim.ElaborateProductDebianRV64SimTop}"
fw_bin="${FW_BIN:-build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
max_cycles="${MAX_CYCLES:-12000000}"
out="${OUT_ROOT:-build/product-sim-short-ab}"
jobs="${JOBS:-4}"
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"

# A/B itself is short and operator-only. Large values silently defeat its
# purpose: this is not a replacement for the separately gated G4/G5 run.
[[ "$max_cycles" =~ ^[0-9]+$ ]] && (( max_cycles > 0 && max_cycles <= 20000000 )) || {
  echo 'Product A/B MAX_CYCLES must be in the short qualification range 1..20000000' >&2
  exit 3
}

[[ -s "$fw_bin" ]] || { echo "missing Product payload: $fw_bin" >&2; exit 3; }
firmware_bytes="$(stat -c%s "$fw_bin")"
if (( firmware_bytes > 268435456 )); then
  echo "Product A/B payload exceeds simulator's 256MiB RAM: ${firmware_bytes} bytes" >&2
  exit 3
fi
case "$top" in
  AetherCoreProductDebianRV64SimTop)
    product_flag='-DAETHERCORE_PRODUCT_DEBIAN'
    ;;
  AetherCoreProductOpenSbiRV64SimTop)
    product_flag=''
    ;;
  *) echo "unsupported Product A/B top: $top" >&2; exit 4 ;;
esac

mkdir -p "$out/rtl-seed/rtl" "$out/logs"
make -f Makefile.l32-linux-boot \
  BUILD_DIR="$out/rtl-seed" TOP="$top" ELABORATE_MAIN="$elaborate" rtl

flags="-std=c++20 -O3 -march=native $product_flag -I$root/sim/product_rv64_opensbi_shim -I$root/sim"
results="$out/results.tsv"
printf 'version\tmode\tseconds\tcycles_per_second\n' > "$results"

for profile in 5.024:reference 5.054:reference 5.054:adaptive; do
  version="${profile%%:*}"
  mode="${profile#*:}"
  label="verilator-$version-$mode"
  # The exact pinned binary must perform the build. Setting only an output
  # directory name would accidentally run a different version from PATH.
  AETHERCORE_VERILATOR_VERSION="$version" \
    AETHERCORE_VERILATOR_FAST_VERIFY=1 bash tools/ensure_verilator_5_024.sh \
    > "$out/logs/$label.toolchain.log" 2>&1
  verilator_bin="$cache_root/verilator-$version/bin/verilator"
  [[ -x "$verilator_bin" ]] || {
    echo "missing pinned Verilator $version: $verilator_bin" >&2
    exit 5
  }
  unset VERILATOR_ROOT
  verilator_identity="$("$verilator_bin" --version)"
  [[ "$verilator_identity" == "Verilator $version" || \
     "$verilator_identity" == "Verilator $version "* ]] || {
    echo "wrong Verilator toolchain: selected $version but got $verilator_identity" >&2
    exit 5
  }
  obj="$out/obj-$label"
  mkdir -p "$obj"
  extra=''
  [[ "$mode" == adaptive ]] && extra='-DAETHERCORE_SIM_ADAPTIVE_SETTLE'
  PATH="$cache_root/verilator-$version/bin:$PATH" \
  "$verilator_bin" -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
    --cc --exe --build -Wall -Wno-fatal \
    --top-module "$top" -Mdir "$obj" \
    -CFLAGS "$flags $extra" \
    "$out"/rtl-seed/rtl/*.sv "$root/sim/opensbi_boot_main.cpp" \
    -j "$jobs" > "$out/logs/$label.compile.log" 2>&1

  sim="$obj/V$top"
  [[ -x "$sim" ]] || { echo "Product simulator missing: $sim" >&2; exit 5; }
  log="$out/logs/$label.run.log"
  begin="$(date +%s%N)"
  set +e
  "$sim" "$fw_bin" "$max_cycles" '__PRODUCT_SIM_AB_NEVER_MATCH__' \
    0 0 '' '' 0 "$max_cycles" 0 0 0 > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  [[ "$rc" == 2 ]] || { echo "Product A/B unexpected $label runner exit=$rc" >&2; exit 6; }
  grep -E '^L32_OPENSBI_TIMEOUT .*ram-fingerprint=0x' "$log" > "$out/$label.state"
  [[ -s "$out/$label.state" ]] || { echo "Product $label has no full state fingerprint" >&2; exit 7; }
  elapsed="$(awk -v start="$begin" -v stop="$end" 'BEGIN { printf "%.6f", (stop-start)/1000000000 }')"
  cps="$(awk -v n="$max_cycles" -v sec="$elapsed" 'BEGIN { printf "%.3f", n/sec }')"
  printf '%s\t%s\t%s\t%s\n' "$version" "$mode" "$elapsed" "$cps" >> "$results"
done

if ! diff -u "$out/verilator-5.024-reference.state" \
              "$out/verilator-5.054-reference.state" > "$out/version-state.diff"; then
  echo 'PRODUCT_SIM_AB_VERSION_STATE_MISMATCH: keep pinned Verilator 5.024 for Product Linux' >&2
  exit 8
fi
echo 'PRODUCT_SIM_AB_VERSION_STATE_MATCH: same Product cycles, commits, traps, interrupts, RAM/LRSC and recent PCs'

if ! diff -u "$out/verilator-5.054-reference.state" \
              "$out/verilator-5.054-adaptive.state" > "$out/adaptive-state.diff"; then
  echo 'PRODUCT_SIM_AB_ADAPTIVE_STATE_MISMATCH: keep reference settle for all Product Linux runs' >&2
  exit 9
fi
echo 'PRODUCT_SIM_AB_ADAPTIVE_STATE_MATCH: same Product cycles, commits, traps, interrupts, RAM/LRSC and recent PCs'

awk -F '\t' '
  NR == 2 {base=$4}
  NR == 3 {
    speedup=$4/base
    printf "PRODUCT_SIM_AB_VERSION_RATE v5_024_cps=%.0f v5_054_cps=%.0f speedup=%.4fx\n", base, $4, speedup
    if (speedup >= 1.03) print "PRODUCT_SIM_AB_V5054_CANDIDATE_ONLY: requires independent review before long-run promotion"
    else print "PRODUCT_SIM_AB_V5054_NO_PROMOTION"
  }
  NR == 3 {reference=$4}
  NR == 4 {
    speedup=$4/reference
    printf "PRODUCT_SIM_AB_ADAPTIVE_RATE reference_cps=%.0f adaptive_cps=%.0f speedup=%.4fx\n", reference, $4, speedup
    if (speedup >= 1.03) print "PRODUCT_SIM_AB_ADAPTIVE_CANDIDATE_ONLY: requires independent review before long-run promotion"
    else print "PRODUCT_SIM_AB_ADAPTIVE_NO_PROMOTION"
  }
' "$results"
