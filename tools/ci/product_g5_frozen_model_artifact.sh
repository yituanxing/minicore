#!/usr/bin/env bash
set -euo pipefail

# Build the *exact historical* Product G5 D2 --savable RTL objects without
# building Debian, OpenSBI or executing any guest cycles. Keep this helper
# outside the pinned source checkout so archived source bytes remain frozen.
# Usage: helper build FROZEN_SOURCE MODEL_ARTIFACT_DIR
#        helper benchmark FROZEN_SOURCE MODEL_ARTIFACT_DIR OLD_G5_ARTIFACT_DIR RESULT_DIR

ORIGIN=99a5c86e99f52ed12f95b304635d77613c9505c4
TOP=AetherCoreProductDebianRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductDebianRV64SimTop

reject() { echo "G5_FROZEN_MODEL_REJECTED reason=$*" >&2; exit 3; }
[[ "$#" == 3 || "$#" == 5 ]] || reject 'expected build SRC ARTIFACT or benchmark SRC ARTIFACT ARCHIVE RESULTS'
ACTION="$1"
[[ "$ACTION" == build && "$#" == 3 || "$ACTION" == benchmark && "$#" == 5 ]] || reject 'invalid action/argument count'
[[ "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${VERILATOR_THREADS:-1}" == 1 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] || reject 'only historical one-thread zero-wait reference savable model is allowed'
SOURCE="$(realpath -e -- "$2")"
ARTIFACT="$3"
[[ -d "$SOURCE/.git" || -f "$SOURCE/.git" ]] || reject 'source must be a git checkout'
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$ORIGIN" ]] || reject 'source commit does not match immutable G5 checkpoint'
git -C "$SOURCE" diff --quiet HEAD -- \
  src/main/scala/aethercore sim build.mill Makefile.l32-linux-boot \
  tools/ci/product_sim_cache.sh tools/ci/product_checkpoint_g5_short.sh || reject 'frozen Product source has edited tracked inputs'
# The frozen commit tracks mill as 100644; its Makefile intentionally chmod +x
# before launching it. Preserve immutable launcher bytes while accepting only
# that execution-bit change (other tracked Product inputs remain strict above).
git -C "$SOURCE" show HEAD:mill | cmp -s - "$SOURCE/mill" || reject 'frozen Mill launcher bytes differ'
[[ -z "$(git -C "$SOURCE" ls-files --others --exclude-standard -- src/main/scala/aethercore sim)" ]] || reject 'frozen Product source has untracked RTL/host inputs'

abi_cpu_signature() {
  awk -F: '/^model name[[:space:]]*:|^flags[[:space:]]*:/{print $0; n++; if(n==2)exit}' /proc/cpuinfo | sha256sum | awk '{print $1}'
}

manifest_field() {
  sed -n "s/^$1=//p" "$ARTIFACT/manifest.txt"
}

verify_artifact() {
  [[ -s "$ARTIFACT/manifest.txt" && -s "$ARTIFACT/SHA256SUMS" && -d "$ARTIFACT/obj" &&
     -s "$ARTIFACT/obj/V${TOP}__ALL.a" && -s "$ARTIFACT/obj/verilated.o" &&
     -s "$ARTIFACT/obj/verilated_save.o" && -d "$ARTIFACT/verilator-include" ]] || reject 'incomplete model artifact'
  [[ "$(manifest_field schema)" == g5-frozen-savable-v1 &&
     "$(manifest_field source_commit)" == "$ORIGIN" &&
     "$(manifest_field top)" == "$TOP" &&
     "$(manifest_field verilator_version)" == 5.054 &&
     "$(manifest_field host_arch)" == "$(uname -m)" &&
     "$(manifest_field cpu_signature)" == "$(abi_cpu_signature)" &&
     "$(manifest_field compiler_version)" == "$(g++ --version | head -1)" &&
     "$(manifest_field compiler_sha256)" == "$(sha256sum "$(readlink -f "$(command -v g++)")" | awk '{print $1}')" ]] || reject 'CPU/compiler/Verilator artifact ABI drifted'
  [[ "$(manifest_field model_archive_sha256)" == "$(sha256sum "$ARTIFACT/obj/V${TOP}__ALL.a" | awk '{print $1}')" ]] || reject 'archive checksum drifted'
  (cd "$ARTIFACT" && sha256sum --check --status SHA256SUMS) || reject 'model object/header checksum drifted'
}

if [[ "$ACTION" == build ]]; then
  [[ ! -e "$ARTIFACT" && ! -L "$ARTIFACT" ]] || reject 'artifact output already exists'
  mkdir -p -- "$ARTIFACT/obj" "$ARTIFACT/verilator-include"
  ARTIFACT="$(realpath -- "$ARTIFACT")"
  TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
  VERILATOR_BIN="$TOOLCHAIN/verilator-5.054/bin/verilator"
  VERILATOR_INCLUDE="$TOOLCHAIN/verilator-5.054/include"
  [[ -x "$VERILATOR_BIN" && -s "$VERILATOR_INCLUDE/verilated_save.h" ]] || reject 'pinned Verilator 5.054 not installed'
  WORK="$ARTIFACT/../g5-frozen-model-build"
  [[ ! -e "$WORK" && ! -L "$WORK" ]] || reject 'frozen model temporary build path already exists'
  mkdir -p "$WORK/rtl-seed" "$WORK/logs"
  FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$SOURCE/sim/product_rv64_opensbi_shim -I$SOURCE/sim"
  (
    cd "$SOURCE"
    make -f Makefile.l32-linux-boot BUILD_DIR="$WORK/rtl-seed" \
      TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$WORK/logs/rtl-build.log" 2>&1
    [[ -n "$(find "$WORK/rtl-seed/rtl" -maxdepth 1 -name '*.sv' -print -quit)" ]] || reject 'missing generated RTL'
    PATH="$TOOLCHAIN/verilator-5.054/bin:$PATH" "$VERILATOR_BIN" \
      -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
      --savable --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
      -Mdir "$ARTIFACT/obj" -CFLAGS "$FLAGS" \
      "$WORK"/rtl-seed/rtl/*.sv "$SOURCE/sim/opensbi_boot_main.cpp" \
      -j "${JOBS:-4}" > "$WORK/logs/verilator-savable-build.log" 2>&1
  ) || { tail -50 "$WORK/logs/rtl-build.log" "$WORK/logs/verilator-savable-build.log" 2>/dev/null; reject 'savable model build failed'; }
  [[ -s "$ARTIFACT/obj/V${TOP}__ALL.a" && -s "$ARTIFACT/obj/verilated.o" &&
     -s "$ARTIFACT/obj/verilated_save.o" && -s "$ARTIFACT/obj/V${TOP}.h" &&
     -x "$ARTIFACT/obj/V${TOP}" ]] || reject 'savable model object set incomplete'
  cp -a "$VERILATOR_INCLUDE/." "$ARTIFACT/verilator-include/"
  {
    printf 'schema=g5-frozen-savable-v1\nsource_commit=%s\ntop=%s\n' "$ORIGIN" "$TOP"
    printf 'elaborate=%s\nverilator_version=5.054\nhost_arch=%s\n' "$ELABORATE" "$(uname -m)"
    printf 'cpu_signature=%s\n' "$(abi_cpu_signature)"
    printf 'compiler_version=%s\n' "$(g++ --version | head -1)"
    printf 'compiler_sha256=%s\n' "$(sha256sum "$(readlink -f "$(command -v g++)")" | awk '{print $1}')"
    printf 'model_archive_sha256=%s\n' "$(sha256sum "$ARTIFACT/obj/V${TOP}__ALL.a" | awk '{print $1}')"
    printf 'verilator_bin_sha256=%s\n' "$(sha256sum "$VERILATOR_BIN" | awk '{print $1}')"
    printf 'model_flags=%s\n' "$FLAGS"
  } > "$ARTIFACT/manifest.txt"
  (cd "$ARTIFACT" && find obj verilator-include -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS)
  printf 'qualification=FROZEN_MODEL_OBJECTS_ONLY_NOT_ADAPTIVE_OR_HISTORICAL_GOLDEN_PASS\n' > "$ARTIFACT/qualification.txt"
  verify_artifact
  echo "G5_FROZEN_MODEL_ARTIFACT_PASS source_commit=$ORIGIN top=$TOP sha256=$(manifest_field model_archive_sha256)"
  exit 0
fi

# Optional bounded check runs on the *same GitHub runner* that built the model;
# no large checkpoint needs to cross incompatible -march=native hosts.
verify_artifact
ARCHIVE="$(realpath -e -- "$4")"
RESULTS="$5"
[[ ! -e "$RESULTS" && ! -L "$RESULTS" ]] || reject 'benchmark output must be fresh'
mapfile -t proofs < <(find "$ARCHIVE" -type f -name persistent.pre.host -path '*/product-g5-long-forensic/*')
[[ "${#proofs[@]}" == 1 ]] || reject 'archived 900M checkpoint not uniquely located'
PROOF="$(dirname "${proofs[0]}")"
[[ -s "$PROOF/identity.txt" && -s "$PROOF/persistent.pre.rtl" &&
   -s "$PROOF/persistent.restored.host" && -s "$PROOF/persistent.restored.rtl" &&
   -s "$PROOF/pre.sha256" ]] || reject 'old G5 proof incomplete'
(cd "$PROOF" && sha256sum --check --status pre.sha256) || reject '900M original checkpoint SHA mismatch'
ORIGINAL_SHA="$(sed -n 's/^model_sha256=//p' "$PROOF/identity.txt")"
FW_SHA="$(sed -n 's/^firmware_sha256=//p' "$PROOF/identity.txt")"
[[ "$ORIGINAL_SHA" =~ ^[0-9a-f]{64}$ && "$FW_SHA" =~ ^[0-9a-f]{64}$ ]] || reject 'old model/firmware identity invalid'
[[ "$ORIGINAL_SHA" == aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71 &&
   "$FW_SHA" == d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c &&
   "$(sed -n 's/^checkpoint_cycles=//p' "$PROOF/identity.txt")" == 900000000 &&
   "$(sed -n 's/^top=//p' "$PROOF/identity.txt")" == "$TOP" &&
   "$(sed -n 's/^vmlinux_sha256=//p' "$PROOF/identity.txt")" == 6f5afbdc6fabd14819219d4928289c163d73f3aee9b38c72c4c866b1c50c5c69 ]] || reject 'archived golden identity differs from frozen 900M D2'
[[ "$(head -c8 "$PROOF/persistent.pre.host")" == AETG5CP1 &&
   "$(stat -c%s "$PROOF/persistent.pre.host")" -gt 268435456 &&
   "$(stat -c%s "$PROOF/persistent.restored.host")" -gt 268435456 ]] || reject 'archived G5 full DDR state invalid'
mkdir -p -- "$RESULTS"
RESULTS="$(realpath -- "$RESULTS")"
OBJ="$ARTIFACT/obj"
INC="$ARTIFACT/verilator-include"
SHIM="$SOURCE/sim/product_rv64_opensbi_shim"
COMMON_FLAGS=(-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN
              -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE
              -I"$OBJ" -I"$SOURCE/sim" -I"$SHIM" -I"$INC" -I"$INC/vltstd")
runtime_objs=("$OBJ/verilated.o" "$OBJ/verilated_save.o")
for extra in verilated_threads.o verilated_timing.o verilated_dpi.o; do
  [[ ! -s "$OBJ/$extra" ]] || runtime_objs+=("$OBJ/$extra")
done
for variant in reference adaptive; do
  flags=("${COMMON_FLAGS[@]}")
  [[ "$variant" != adaptive ]] || flags+=(-DAETHERCORE_SIM_ADAPTIVE_SETTLE)
  g++ "${flags[@]}" -c "$SOURCE/sim/product_rv64_checkpoint_g4_main.cpp" -o "$RESULTS/$variant.o"
  g++ -O3 "$RESULTS/$variant.o" "$OBJ/V${TOP}__ALL.a" "${runtime_objs[@]}" \
    -pthread -ldl -latomic -o "$RESULTS/$variant"
done
[[ "$(sha256sum "$RESULTS/reference" | awk '{print $1}')" != "$(sha256sum "$RESULTS/adaptive" | awk '{print $1}')" ]] || reject 'candidate has same byte identity as reference'
for variant in reference adaptive; do
  prefix="$RESULTS/$variant-run"
  ln -s "$PROOF/persistent.pre.host" "$prefix.pre.host"
  ln -s "$PROOF/persistent.pre.rtl" "$prefix.pre.rtl"
  start_ns="$(date +%s%N)"
  timeout --signal=TERM --kill-after=5s 90s \
    "$RESULTS/$variant" restore /dev/null "$prefix" "$ORIGINAL_SHA" "$FW_SHA" \
    1800000000 'Linux version 6.6.143' 500000 > "$RESULTS/$variant.log" 2>&1 || {
      tail -40 "$RESULTS/$variant.log" >&2; reject "$variant checkpoint replay failed"
    }
  printf '%s\n' "$(( $(date +%s%N) - start_ns ))" > "$RESULTS/$variant.wall_ns"
  grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$RESULTS/$variant.log" > "$RESULTS/$variant.state"
  grep -Eq '(^| )cycles=900500000( |$)' "$RESULTS/$variant.state" || reject "$variant wrong cycle bound"
  cmp -s "$prefix.restored.host" "$PROOF/persistent.restored.host" || reject "$variant differs from historical GOLDEN host"
  cmp -s "$prefix.restored.rtl" "$PROOF/persistent.restored.rtl" || reject "$variant differs from historical GOLDEN RTL"
done
cmp -s "$RESULTS/reference.state" "$RESULTS/adaptive.state" || reject 'A/B trace/commit/IRQ summary divergence'
ref_ns="$(< "$RESULTS/reference.wall_ns")"
adaptive_ns="$(< "$RESULTS/adaptive.wall_ns")"
speedup="$(awk -v a="$ref_ns" -v b="$adaptive_ns" 'BEGIN {printf "%.3f", a/b}')"
printf 'qualification=HISTORICAL_900M_G5_REFERENCE_ADAPTIVE_FULL_STATE_EQUAL\nsource_commit=%s\nwindow_cycles=500000\nreference_ns=%s\nadaptive_ns=%s\nspeedup_reference_over_adaptive=%s\n' \
  "$ORIGIN" "$ref_ns" "$adaptive_ns" "$speedup" > "$RESULTS/result.txt"
printf 'qualification=HISTORICAL_900M_G5_REFERENCE_ADAPTIVE_500K_ONLY_NOT_PID1_OR_LONG_RUN_PASS\n' > "$ARTIFACT/qualification.txt"
echo "G5_FROZEN_MODEL_AB_PASS speedup=$speedup window=500000 historical_golden=IDENTICAL"
