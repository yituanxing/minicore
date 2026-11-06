#!/usr/bin/env bash
set -euo pipefail

# Rebuild ONLY the C++ host half of the frozen G5 Product simulator with
# adaptive low-phase settle.  This script never builds Chisel/Verilator RTL,
# starts a long guest run, changes a live binary, or qualifies the result.
#
# Input artifact layout, produced by the frozen-model-only CI workflow:
#   model-artifact/{manifest.txt,SHA256SUMS,obj/,verilator-include/}
#
# Usage: product_g5_adaptive_host_relink.sh FROZEN_99A5_SOURCE_TREE \
#        MODEL_ARTIFACT_DIR EXACT_G5_ARCHIVE_DIR NEW_OUTPUT_DIR

reject() { echo "G5_ADAPTIVE_RELINK_REJECTED reason=$*" >&2; exit 3; }
[[ $# == 4 ]] || { echo 'usage: script FROZEN_SOURCE MODEL_ARTIFACT EXACT_ARCHIVE FRESH_OUTPUT_DIR' >&2; exit 2; }

SOURCE_ROOT="$(realpath -e -- "$1")"
ARTIFACT="$(realpath -e -- "$2")"
ARCHIVE="$(realpath -e -- "$3")"
OUT="$4"

MODEL_COMMIT=99a5c86e99f52ed12f95b304635d77613c9505c4
MODEL_TOP=AetherCoreProductDebianRV64SimTop
MANIFEST="$ARTIFACT/manifest.txt"
HASHLIST="$ARTIFACT/SHA256SUMS"
OBJ="$ARTIFACT/obj"
VERILATOR_INCLUDE="$ARTIFACT/verilator-include"
MODEL_A="$OBJ/V${MODEL_TOP}__ALL.a"
FROZEN_CPP="$SOURCE_ROOT/sim/product_rv64_checkpoint_g4_main.cpp"
BRIDGE="$ARCHIVE/bridge-identity.txt"
BASELINE="$ARCHIVE/product-g5-checkpoint"

[[ -d "$SOURCE_ROOT/.git" || -f "$SOURCE_ROOT/.git" ]] || reject 'source tree must be a Git checkout'
[[ -r "$MANIFEST" && -r "$HASHLIST" && -s "$MODEL_A" &&
   -s "$OBJ/verilated.o" && -s "$OBJ/verilated_save.o" &&
   -s "$OBJ/V${MODEL_TOP}.h" && -s "$VERILATOR_INCLUDE/verilated.h" &&
   -s "$VERILATOR_INCLUDE/verilated_save.h" && -s "$FROZEN_CPP" &&
   -s "$BRIDGE" && -x "$BASELINE" ]] ||
  reject 'missing frozen model objects, runtime headers, source or baseline'
[[ ! -e "$OUT" && ! -L "$OUT" && "$OUT" != / && "$OUT" != . ]] ||
  reject 'output directory must not exist (no overwrite)'

field() { sed -n "s/^${1}=//p" "$MANIFEST"; }
bridge_field() { sed -n "s/^${1}=//p" "$BRIDGE"; }
expect_field() {
  local key="$1" expected="$2"
  [[ "$(field "$key")" == "$expected" ]] || reject "artifact $key differs"
}

[[ "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" == "$MODEL_COMMIT" ]] ||
  reject 'source checkout does not match archived RTL commit'
git -C "$SOURCE_ROOT" diff --quiet HEAD -- sim src/main/scala ||
  reject 'frozen model source has local edits'
[[ -z "$(git -C "$SOURCE_ROOT" ls-files --others --exclude-standard -- sim src/main/scala)" ]] ||
  reject 'frozen source contains untracked simulator inputs'

expect_field schema g5-frozen-savable-v1
expect_field source_commit "$MODEL_COMMIT"
expect_field top "$MODEL_TOP"
expect_field verilator_version 5.054
expect_field host_arch "$(uname -m)"
[[ "$(bridge_field source_commit)" == "$MODEL_COMMIT" &&
   "$(bridge_field golden_rtl_host_identical)" == 1 ]] ||
  reject 'archive identity does not qualify the same frozen RTL model'
[[ "$(sha256sum "$BASELINE" | awk '{print $1}')" == \
   "$(bridge_field rebuilt_model_sha256)" ]] ||
  reject 'reference binary SHA drifted from exact archived replay'

# Original model build is -march=native. Do not execute cached CI binaries on
# a different host CPU/compiler ABI merely because both machines are x86_64.
CPU_SIGNATURE="$(awk -F: '/^model name[[:space:]]*:|^flags[[:space:]]*:/{print $0; n++; if(n==2)exit}' /proc/cpuinfo | sha256sum | awk '{print $1}')"
GXX_PATH="$(command -v g++ || true)"
[[ -n "$GXX_PATH" && -f "$(readlink -f "$GXX_PATH")" ]] || reject 'g++ missing'
COMPILER_SHA="$(sha256sum "$(readlink -f "$GXX_PATH")" | awk '{print $1}')"
expect_field cpu_signature "$CPU_SIGNATURE"
expect_field compiler_version "$(g++ --version | sed -n '1p')"
expect_field compiler_sha256 "$COMPILER_SHA"

[[ "$(field model_archive_sha256)" == "$(sha256sum "$MODEL_A" | awk '{print $1}')" ]] ||
  reject 'model archive content differs from manifest'
(cd "$ARTIFACT" && sha256sum --check --status SHA256SUMS) ||
  reject 'one or more model object/runtime header checksums differ'

mkdir -p -- "$OUT"
OUT="$(realpath -e -- "$OUT")"
HOST_OBJ="$OUT/product-g5-adaptive-host.o"
CANDIDATE="$OUT/product-g5-adaptive-checkpoint"

# Match historical G5 pinned host flags, adding ONLY the adaptive guard.
g++ -std=c++20 -O3 -march=native \
  -DAETHERCORE_PRODUCT_DEBIAN \
  -DAETHERCORE_PRODUCT_CHECKPOINT_G5 \
  -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
  -DAETHERCORE_SIM_ADAPTIVE_SETTLE \
  -I"$OBJ" -I"$SOURCE_ROOT/sim" \
  -I"$VERILATOR_INCLUDE" -I"$VERILATOR_INCLUDE/vltstd" \
  -c "$FROZEN_CPP" -o "$HOST_OBJ" > "$OUT/host-compile.log" 2>&1 || {
    tail -50 "$OUT/host-compile.log" >&2
    reject 'host-only adaptive compilation failed'
  }
runtime_objs=("$OBJ/verilated.o" "$OBJ/verilated_save.o")
for extra in verilated_threads.o verilated_timing.o verilated_dpi.o; do
  [[ ! -s "$OBJ/$extra" ]] || runtime_objs+=("$OBJ/$extra")
done
g++ -O3 "$HOST_OBJ" "$MODEL_A" "${runtime_objs[@]}" \
  -pthread -ldl -latomic -o "$CANDIDATE" > "$OUT/host-link.log" 2>&1 || {
    tail -50 "$OUT/host-link.log" >&2
    reject 'host-only adaptive link failed'
  }

BASE_SHA="$(sha256sum "$BASELINE" | awk '{print $1}')"
CAND_SHA="$(sha256sum "$CANDIDATE" | awk '{print $1}')"
[[ "$BASE_SHA" != "$CAND_SHA" ]] ||
  reject 'candidate and reference binaries are identical; not an adaptive A/B'
{
  printf 'qualification=UNQUALIFIED_HOST_ONLY_CANDIDATE\n'
  printf 'source_commit=%s\nmodel_archive_sha256=%s\n' "$MODEL_COMMIT" "$(field model_archive_sha256)"
  printf 'reference_binary_sha256=%s\ncandidate_binary_sha256=%s\n' "$BASE_SHA" "$CAND_SHA"
  printf 'cpu_signature=%s\ncompiler_sha256=%s\n' "$CPU_SIGNATURE" "$COMPILER_SHA"
  printf 'candidate_path=%s\n' "$CANDIDATE"
  printf 'required_next_gate=product_g5_checkpoint_host_ab.sh\n'
} > "$OUT/candidate-identity.txt"
echo "G5_ADAPTIVE_HOST_BUILT_UNQUALIFIED candidate=$CANDIDATE sha256=$CAND_SHA"
echo 'G5_ADAPTIVE_HOST_NEXT: run the existing exact-checkpoint A/B gate; never substitute candidate into the long runner unless full state bytes match and speedup is measured.'
