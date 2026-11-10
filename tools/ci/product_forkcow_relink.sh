#!/usr/bin/env bash
set -euo pipefail

# Reuse an *already compiled* RAM-only Product G3/G4 Verilator RTL archive.
# Rebuilding all RTL just to change the host main would erase the intended
# rapid checkpoint workflow. This script does not run any simulation.
#
# Example (after product_g3_short_ab has built a cached model):
#   TOP=AetherCoreProductOpenSbiRV64SimTop \
#   bash tools/ci/product_forkcow_relink.sh \
#     build/product-g3-short-ab/model-verilator-5.054-adaptive/obj
#
# Then run the returned PRODUCT_FORKCOW_BINARY with:
#   FW_PAYLOAD.bin BOOT_MAX UART_TRIGGER REPLAY_CYCLES [REPEATS] ...

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
obj="${1:?usage: product_forkcow_relink.sh EXISTING_VERILATOR_OBJ_DIR}"
obj="$(realpath "$obj")"
model_root="$(dirname "$obj")"
[[ "$obj" == "$model_root/obj" ]] || {
  echo 'PRODUCT_FORKCOW_RELINK_REJECTED: require a cached Product model-*/obj directory' >&2
  exit 2
}
label="$(basename "$model_root")"
case "$label" in
  model-verilator-5.024-reference) cached_version=5.024; cached_settle=reference ;;
  model-verilator-5.054-reference) cached_version=5.054; cached_settle=reference ;;
  model-verilator-5.054-adaptive) cached_version=5.054; cached_settle=adaptive ;;
  *) echo "PRODUCT_FORKCOW_RELINK_REJECTED: unrecognized pinned cached model identity $label" >&2; exit 2 ;;
esac
top="${TOP:-AetherCoreProductOpenSbiRV64SimTop}"
case "$top" in
  AetherCoreProductOpenSbiRV64SimTop) product_flags=() ;;
  AetherCoreProductDebianRV64SimTop) product_flags=(-DAETHERCORE_PRODUCT_DEBIAN) ;;
  *) echo "PRODUCT_FORKCOW_RELINK_REJECTED: only RAM-only Product tops are supported: $top" >&2; exit 2 ;;
esac

archive="$obj/V${top}__ALL.a"
[[ -s "$archive" && -s "$obj/verilated.o" &&
   -s "$model_root/.product-model-identity" ]] || {
  echo "PRODUCT_FORKCOW_RELINK_REJECTED: missing compiled RTL archive/runtime in $obj" >&2
  exit 3
}
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
verilator_version="${AETHERCORE_VERILATOR_VERSION:-$cached_version}"
settle_mode="${SIM_SETTLE_MODE:-$cached_settle}"
[[ "$verilator_version" == "$cached_version" && "$settle_mode" == "$cached_settle" &&
   "${VERILATOR_THREADS:-1}" == 1 ]] || {
  echo "PRODUCT_FORKCOW_RELINK_REJECTED: model $label requires version=$cached_version mode=$cached_settle threads=1" >&2
  exit 4
}
# Re-verify the *live* pinned generator, generated RTL inputs, host ABI,
# compiler identity, and existing executable. The folder name is not proof
# that the compiled archive matches the current Product CPU.
case "$top" in
  AetherCoreProductOpenSbiRV64SimTop)
    elaborate=aethercore.sim.ElaborateProductOpenSbiRV64SimTop ;;
  AetherCoreProductDebianRV64SimTop)
    elaborate=aethercore.sim.ElaborateProductDebianRV64SimTop ;;
esac
flags="-std=c++20 -O3 -march=native"
if [[ "$top" == AetherCoreProductDebianRV64SimTop ]]; then
  flags+=' -DAETHERCORE_PRODUCT_DEBIAN'
fi
flags+=" -I$root/sim/product_rv64_opensbi_shim -I$root/sim"
if ! AETHERCORE_VERILATOR_VERSION="$cached_version" SIM_SETTLE_MODE="$cached_settle" \
  bash "$root/tools/ci/product_sim_cache.sh" verify \
    "$model_root" "$top" "$elaborate" "$flags"; then
  echo 'PRODUCT_FORKCOW_RELINK_REJECTED: compiled Product model is stale or not identity-verified' >&2
  exit 4
fi
inc="$cache_root/verilator-$verilator_version/include"
if [[ -n "${VERILATOR_INCLUDE_DIR:-}" &&
      "$(realpath -m "$VERILATOR_INCLUDE_DIR")" != "$(realpath -m "$inc")" ]]; then
  echo 'PRODUCT_FORKCOW_RELINK_REJECTED: include override differs from fingerprinted pinned Verilator runtime' >&2
  exit 4
fi
[[ -f "$inc/verilated.h" ]] || {
  echo "PRODUCT_FORKCOW_RELINK_REJECTED: missing fingerprinted pinned Verilator runtime header: $inc/verilated.h" >&2
  exit 4
}
case "$settle_mode" in
  reference) settle_flags=() ;;
  adaptive) settle_flags=(-DAETHERCORE_SIM_ADAPTIVE_SETTLE) ;;
  *) echo 'PRODUCT_FORKCOW_RELINK_REJECTED: SIM_SETTLE_MODE=reference|adaptive' >&2; exit 4 ;;
esac

# Linked Verilator objects are read-only inputs. Product-specific host code
# and executable are stored separately to preserve the cached model's object
# archive and the old opensbi_boot_main.cpp simulator binary.
out_dir="${PRODUCT_FORKCOW_OUT_DIR:-$obj/product-forkcow-relink}"
mkdir -p "$out_dir"
out_dir="$(realpath "$out_dir")"
host_obj="$out_dir/product_rv64_forkcow_main.o"
host_bin="$out_dir/product-forkcow"
threads=()
for runtime_obj in verilated_threads.o verilated_timing.o verilated_dpi.o; do
  if [[ -s "$obj/$runtime_obj" ]]; then
    threads+=("$obj/$runtime_obj")
  fi
done

"${CXX:-g++}" -std=c++20 -O3 -march=native \
  -I"$obj" -I"$root/sim" -I"$inc" -I"$inc/vltstd" \
  "${product_flags[@]}" "${settle_flags[@]}" \
  -c "$root/sim/product_rv64_forkcow_main.cpp" -o "$host_obj"
"${CXX:-g++}" -O3 "$host_obj" "$archive" "$obj/verilated.o" \
  "${threads[@]}" -pthread -ldl -latomic -o "$host_bin"

echo "PRODUCT_FORKCOW_RELINK_PASS top=$top model=$archive mode=$settle_mode version=$cached_version"
echo "PRODUCT_FORKCOW_BINARY=$host_bin"
