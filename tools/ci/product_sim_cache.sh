#!/usr/bin/env bash
set -euo pipefail

# Persistent, content-addressed Product model cache contract. Only RTL/model
# inputs are hashed: the OpenSBI/Linux payload is deliberately excluded, so a
# new rootfs can reuse an identical CPU simulator. Payload identity remains
# checked by Makefile.l32-linux-boot at run time.
#
# Usage: product_sim_cache.sh fingerprint|record|verify BUILD_DIR TOP ELABORATE_MAIN SIM_CXXFLAGS
# Environment: AETHERCORE_VERILATOR_VERSION, SIM_SETTLE_MODE, VERILATOR_THREADS

if [[ $# -ne 5 ]]; then
  echo 'usage: product_sim_cache.sh fingerprint|record|verify BUILD_DIR TOP ELABORATE_MAIN SIM_CXXFLAGS' >&2
  exit 2
fi

mode="$1"
build_dir="$2"
top="$3"
elaborate_main="$4"
sim_cxxflags="$5"
verilator_version="${AETHERCORE_VERILATOR_VERSION:-5.024}"
settle_mode="${SIM_SETTLE_MODE:-reference}"
verilator_threads="${VERILATOR_THREADS:-1}"
verilator_model_opt="${VERILATOR_MODEL_OPT:--O3}"
verilator_make_opt="${VERILATOR_MAKE_OPT:--MAKEFLAGS OPT_FAST=-O3 OPT_GLOBAL=-O3}"
sim_binary="$build_dir/obj/V$top"
manifest="$build_dir/.product-model-identity"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

case "$settle_mode" in reference|adaptive) ;; *) echo "unknown settle mode: $settle_mode" >&2; exit 3 ;; esac
case "$verilator_version" in 5.024|5.054) ;; *) echo "unsupported Verilator: $verilator_version" >&2; exit 3 ;; esac

input_identity() {
  # The SDHC host model is included in the fingerprint ONLY for the explicit
  # SD1 physical-card top. Existing RAM-only G4/G5 binaries neither compile
  # nor depend upon this header and must not recompile when it is edited.
  local model_specific_sources=()
  if [[ "$sim_cxxflags" == *-DAETHERCORE_PRODUCT_SPI_SD* ]]; then
    model_specific_sources+=(sim/l32_sd_spi_card.h)
  fi
  {
    printf 'top=%s\nelaborate=%s\ncompiler-flags=%s\nsettle=%s\nverilator=%s\nthreads=%s\nmodel-opt=%s\nmake-opt=%s\n' \
      "$top" "$elaborate_main" "$sim_cxxflags" "$settle_mode" \
      "$verilator_version" "$verilator_threads" "$verilator_model_opt" "$verilator_make_opt"
    # -march=native makes a cross-host cached executable unsafe unless the
    # actual host ISA/model and compiler identity are part of the key.
    printf 'host-arch=%s\n' "$(uname -m)"
    printf 'host-compiler=%s\n' "$(c++ --version | sed -n '1p')"
    if [[ -r /proc/cpuinfo ]]; then
      awk -F: '/^(vendor_id|cpu family|model|model name|flags)[[:space:]]*:/ {if (!seen[$1]++) print $1 ":" $2}' /proc/cpuinfo
    fi
    git ls-files -z -- \
      src/main/scala/aethercore \
      sim/opensbi_boot_main.cpp \
      sim/l32_opensbi_runtime.h \
      sim/product_rv64_opensbi_shim \
      Makefile.l32-linux-boot build.mill mill \
      tools/ci/product_sim_cache.sh tools/ensure_verilator_5_024.sh \
      tools/ensure_verilator_5_054.sh "${model_specific_sources[@]}" | LC_ALL=C sort -z | \
      while IFS= read -r -d '' file; do
        [[ -f "$file" ]] || { echo "missing model source: $file" >&2; exit 4; }
        sha256sum "$file"
      done
  } | sha256sum | awk '{print $1}'
}

identity="$(input_identity)"
case "$mode" in
  fingerprint)
    printf '%s\n' "$identity"
    ;;
  record)
    [[ -x "$sim_binary" ]] || { echo "missing compiled Product simulator: $sim_binary" >&2; exit 5; }
    mkdir -p "$build_dir"
    {
      printf 'identity=%s\n' "$identity"
      printf 'simulator_sha256=%s\n' "$(sha256sum "$sim_binary" | awk '{print $1}')"
    } > "$manifest"
    echo "PRODUCT_MODEL_CACHE_RECORDED identity=$identity"
    ;;
  verify)
    [[ -f "$manifest" && -x "$sim_binary" ]] || { echo 'Product model cache missing manifest or executable' >&2; exit 6; }
    grep -Fxq "identity=$identity" "$manifest" || { echo 'Product model cache inputs changed' >&2; exit 7; }
    actual="$(sha256sum "$sim_binary" | awk '{print $1}')"
    grep -Fxq "simulator_sha256=$actual" "$manifest" || { echo 'Product model cache executable changed' >&2; exit 8; }
    echo "PRODUCT_MODEL_CACHE_VERIFIED identity=$identity"
    ;;
  *)
    echo "unknown cache operation: $mode" >&2
    exit 2
    ;;
esac
