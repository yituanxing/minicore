#!/usr/bin/env bash
set -euo pipefail

# Rebuild only the already-generated Verilator 5.054 C++, never Scala, RTL or
# Verilator itself. Qualify an x86-64-baseline host against the byte-for-byte
# historical 1.795B -> 1.8B GOLDEN before continuing the saved G5 state.
# prepare PINNED_SOURCE DOWNLOADED_MODEL DOWNLOADED_1_8B FRESH_OUT
# run     PREPARED_OUT 95000000|100000000

reject() { printf 'G5_PORTABLE_REJECT reason=%s\n' "$*" >&2; exit 3; }
readonly ORIGIN=99a5c86e99f52ed12f95b304635d77613c9505c4
readonly MODEL_SHA=aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71
readonly FIRMWARE_SHA=d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c
readonly FROZEN_SHA=5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d
readonly TOP=AetherCoreProductDebianRV64SimTop
readonly FROZEN_RUN=42168606753
readonly MODEL_RUN=42175820228
readonly BOOT_ARG=1800000000
readonly LIMIT=2700000000
readonly MARKER='Linux version 6.6.143'

sha() { sha256sum -- "$1" | cut -d' ' -f1; }
decimal() { [[ "$1" =~ ^(0|[1-9][0-9]*)$ ]] || reject "invalid integer $1"; }
field() {
  local value
  [[ -s "$1" ]] || reject "missing manifest $1"
  value="$(sed -n "s/^${2}=//p" "$1")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || reject "missing/duplicate $2 in $1"
  printf '%s' "$value"
}

old_complete() {
  local dir="$1" manifest="$1/manifest.txt" cycle="$2"
  [[ -s "$dir/checkpoint.host" && -s "$dir/checkpoint.rtl" &&
     -s "$manifest" && -s "$dir/COMPLETE" ]] || reject "missing golden $cycle"
  [[ "$(awk '{print $2}' "$dir/COMPLETE")" == $'checkpoint.host\ncheckpoint.rtl\nmanifest.txt' ]] || reject "golden COMPLETE layout $cycle"
  (cd "$dir" && sha256sum --check --status COMPLETE) || reject "golden SHA mismatch $cycle"
  [[ "$(field "$manifest" format)" == g5-d2-checkpoint-v1 &&
     "$(field "$manifest" cycles)" == "$cycle" &&
     "$(field "$manifest" source_commit)" == "$ORIGIN" &&
     "$(field "$manifest" model_sha256)" == "$MODEL_SHA" &&
     "$(field "$manifest" binary_sha256)" == "$FROZEN_SHA" &&
     "$(field "$manifest" firmware_sha256)" == "$FIRMWARE_SHA" ]] ||
    reject "historical golden provenance $cycle"
  decimal "$(field "$manifest" commits)"
}

portable_complete() {
  local dir="$1" manifest="$1/manifest.txt" binary_sha="$2" cycle
  [[ -s "$dir/checkpoint.host" && -s "$dir/checkpoint.rtl" &&
     -s "$manifest" && -s "$dir/COMPLETE" ]] || return 1
  (cd "$dir" && sha256sum --check --status COMPLETE) || return 1
  [[ "$(awk '{print $2}' "$dir/COMPLETE")" == $'checkpoint.host\ncheckpoint.rtl\nmanifest.txt' ]] || return 1
  [[ "$(field "$manifest" format)" == g5-d2-portable-v1 &&
     "$(field "$manifest" source_commit)" == "$ORIGIN" &&
     "$(field "$manifest" model_sha256)" == "$MODEL_SHA" &&
     "$(field "$manifest" firmware_sha256)" == "$FIRMWARE_SHA" &&
     "$(field "$manifest" portable_binary_sha256)" == "$binary_sha" &&
     "$(field "$manifest" golden_source_run)" == "$FROZEN_RUN" ]] || return 1
  cycle="$(field "$manifest" cycles)"; decimal "$cycle"
  [[ "$dir" == */c"$cycle" && "$cycle" -ge 1800000000 &&
     "$cycle" -le "$LIMIT" && "$((cycle % 5000000))" -eq 0 ]] || return 1
}

publish() {
  local dir="$1" cycle="$2" commits="$3" origin="$4" binary_sha="$5"
  decimal "$cycle"; decimal "$commits"
  printf 'format=g5-d2-portable-v1\nsource_commit=%s\nmodel_sha256=%s\nfirmware_sha256=%s\nportable_binary_sha256=%s\nfrozen_binary_sha256=%s\ngolden_source_run=%s\ngenerated_model_run=%s\ncycles=%s\ncommits=%s\norigin=%s\n' \
    "$ORIGIN" "$MODEL_SHA" "$FIRMWARE_SHA" "$binary_sha" "$FROZEN_SHA" \
    "$FROZEN_RUN" "$MODEL_RUN" "$cycle" "$commits" "$origin" > "$dir/manifest.txt"
  (cd "$dir" && sha256sum checkpoint.host checkpoint.rtl manifest.txt > COMPLETE)
  portable_complete "$dir" "$binary_sha" || reject "portable checkpoint incomplete $cycle"
}

latest() {
  local out="$1" binary_sha="$2" dir cycle best=0 best_dir=''
  for dir in "$out"/checkpoints/c*; do
    [[ -d "$dir" ]] || continue
    portable_complete "$dir" "$binary_sha" || reject "corrupted portable snapshot $dir"
    cycle="$(field "$dir/manifest.txt" cycles)"
    if (( cycle > best )); then best="$cycle"; best_dir="$dir"; fi
  done
  [[ -n "$best_dir" ]] || reject 'no portable checkpoint exists'
  printf '%s' "$best_dir"
}

step5m() {
  local out="$1" input_dir="$2" prefix="$3" logfile="$4" binary_sha="$5"
  local cycle commits next restored line endline
  cycle="$(field "$input_dir/manifest.txt" cycles)"
  commits="$(field "$input_dir/manifest.txt" commits)"
  next="$((cycle + 5000000))"
  [[ ! -e "$prefix.pre.host" && ! -e "$prefix.restored.host" ]] || reject "prefix already exists $prefix"
  ln -s -- "$(realpath -e "$input_dir/checkpoint.host")" "$prefix.pre.host"
  ln -s -- "$(realpath -e "$input_dir/checkpoint.rtl")" "$prefix.pre.rtl"
  if ! timeout --signal=TERM --kill-after=10s 900s \
       "$out/model/product-g5-checkpoint" restore /dev/null "$prefix" "$MODEL_SHA" \
       "$FIRMWARE_SHA" "$BOOT_ARG" "$MARKER" 5000000 > "$logfile" 2>&1; then
    tail -35 "$logfile" >&2 || true
    reject "RTL execution failed $cycle->$next"
  fi
  restored="$(grep '^PRODUCT_G5_PERSISTENT_CHECKPOINT_RESTORED ' "$logfile" | tail -1)"
  line="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$logfile" | tail -1)"
  [[ " $restored " == *" cycles=$cycle commits=$commits "* &&
     " $line " == *" cycles=$next time=$next "* &&
     " $line " == *' linux_banner=1 '* &&
     -s "$prefix.restored.host" && -s "$prefix.restored.rtl" ]] ||
    reject "observer cycle/commit mismatch $next"
  endline="$(sed -n 's/.* commits=\([0-9]*\) .*/\1/p' <<< "$line")"
  decimal "$endline"
  (( endline >= commits )) || reject "retired commits regressed $next"
  printf '%s\n' "$line" >> "$out/progress.log"
  printf '%s' "$endline"
}

prepare() {
  [[ $# == 4 ]] || reject 'prepare requires SOURCE MODEL_ARTIFACT CHECKPOINT_ARTIFACT FRESH_OUT'
  local source="$1" input_model="$2" input_ckpt="$3" out="$4"
  [[ -d "$source" && -d "$input_model" && -d "$input_ckpt" &&
     ! -e "$out" && ! -L "$out" ]] || reject 'invalid or non-fresh input/output'
  # The generated .mk executes with make -C OBJ, so every -I path embedded
  # into VM_USER_CFLAGS must be absolute, even if actions/checkout used a
  # relative checkout directory such as frozen-source/.
  source="$(realpath -e -- "$source")"
  input_model="$(realpath -e -- "$input_model")"
  input_ckpt="$(realpath -e -- "$input_ckpt")"
  [[ "$(git -C "$source" rev-parse HEAD)" == "$ORIGIN" ]] || reject 'G5 pinned source commit drift'
  git -C "$source" diff --quiet HEAD -- sim src/main/scala/aethercore || reject 'pinned host/RTL source edits'
  # actions/upload-artifact strips the producer's leading model-artifact/
  # when extracting its content; accept either layout, never guess a file.
  local art="$input_model" frozen="$input_ckpt" old1795 old1800
  [[ ! -d "$input_model/model-artifact" ]] || art="$input_model/model-artifact"
  [[ -s "$art/manifest.txt" && -d "$art/obj" ]] || reject 'model artifact root incomplete'
  [[ "$(field "$art/manifest.txt" schema)" == g5-frozen-savable-v1 &&
     "$(field "$art/manifest.txt" source_commit)" == "$ORIGIN" &&
     "$(field "$art/manifest.txt" top)" == "$TOP" &&
     "$(field "$art/manifest.txt" verilator_version)" == 5.054 ]] ||
    reject 'model source/Verilator provenance drift'
  [[ "$(sha "$art/obj/V${TOP}__ALL.a")" == "$(field "$art/manifest.txt" model_archive_sha256)" ]] || reject 'native model archive mismatch'
  # upload-artifact@v4 excludes hidden files. Exactly one harmless formatter
  # file (.clang-format) was dropped from the producer's SHA256SUMS; verify
  # that omission by its exact name, and verify EVERY other listed byte.
  [[ "$(grep -Ec '^([0-9a-f]{64})  verilator-include/fstcpp/\.clang-format$' "$art/SHA256SUMS")" == 1 ]] ||
    reject 'known .clang-format omission missing or duplicated'
  [[ ! -e "$art/verilator-include/fstcpp/.clang-format" ]] ||
    reject '.clang-format unexpectedly present; re-evaluate whitelist'
  (cd "$art" && sha256sum --check --status <(sed '\#  verilator-include/fstcpp/\.clang-format$#d' SHA256SUMS)) ||
    reject 'generated objects/headers checksum mismatch beyond known .clang-format omission'
  old1795="$frozen/checkpoints/c1795000000"
  old1800="$frozen/checkpoints/c1800000000"
  old_complete "$old1795" 1795000000
  old_complete "$old1800" 1800000000
  [[ "$(field "$old1800/manifest.txt" commits)" == 462447787 ]] || reject '1.8B terminal commit drift'
  # Building in a *new* copy is essential: every archived .o/.a contains
  # native CPU instructions and is unsafe on another GitHub runner.
  mkdir -p "$out/model" "$out/checkpoints" "$out/logs" "$out/work" "$out/.staging" "$out/toolchain"
  cp -a -- "$art/obj" "$out/model/obj"
  ln -s -- "$(realpath -e "$art/verilator-include")" "$out/toolchain/include"
  local obj="$out/model/obj" inc="$art/verilator-include" sim="$source/sim"
  local flags="-std=c++20 -O3 -march=x86-64 -mtune=generic -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$sim/product_rv64_opensbi_shim -I$sim -I$obj -I$inc -I$inc/vltstd"
  [[ -s "$obj/V${TOP}.mk" ]] || reject 'generated model makefile unavailable'
  # Verilator embeds one *absolute* historical source prerequisite in the
  # generated makefile (apart from overridable variables). Rewrite exactly
  # this make rule in our private model copy so Make's dependency scanner
  # cannot query the no-longer-existent old CI checkout.
  local makefile="$obj/V${TOP}.mk"
  local old_source='/home/runner/work/minicore/minicore/frozen-source/sim/opensbi_boot_main.cpp'
  [[ "$(grep -Fxc "opensbi_boot_main.o: $old_source " "$makefile")" == 1 &&
     -s "$sim/opensbi_boot_main.cpp" ]] || reject 'pinned historical opensbi make prerequisite unexpected'
  sed -i "s#opensbi_boot_main.o: $old_source #opensbi_boot_main.o: $sim/opensbi_boot_main.cpp #" "$makefile"
  [[ "$(grep -Fxc "opensbi_boot_main.o: $sim/opensbi_boot_main.cpp " "$makefile")" == 1 ]] ||
    reject 'portable opensbi make prerequisite rewrite failed'
  local -a extra=() runtime=(verilated.o verilated_save.o) objectfiles=() generated_cpp=() immutable_args=()
  mapfile -d '' -t generated_cpp < <(find "$obj" -maxdepth 1 -type f -name 'V*.cpp' -print0)
  (( ${#generated_cpp[@]} >= 3 )) || reject 'generated Verilator C++ sources incomplete'
  for cpp in "${generated_cpp[@]}"; do immutable_args+=(-o "$(basename "$cpp")"); done
  for n in verilated_threads.o verilated_timing.o verilated_dpi.o; do
    [[ ! -s "$obj/$n" ]] || { extra+=("$n"); runtime+=("$n"); }
  done
  # Private copy only: remove *all* native objects, precompiled headers and
  # GCC dependency files. Historical *.d refer to the previous runner's
  # absolute /home/runner/.../verilator-5.054/include paths. Keeping them
  # makes GNU Make fail BEFORE compiling even with VERILATOR_ROOT overridden:
  # "No rule to make target .../verilated.h, needed by ...__pch.h.fast.gch".
  # The fresh build regenerates these dependencies against our pinned 5.054
  # include tree. All generated .cpp/.h/.mk source bytes remain untouched.
  mapfile -d '' -t objectfiles < <(find "$obj" -maxdepth 1 -type f \( \
    -name '*.o' -o -name '*.a' -o -name '*.d' -o -name '*.gch' -o -name '*.pch' \
    \) -print0)
  (( ${#objectfiles[@]} >= 3 )) || reject 'historical generated object set unexpectedly small'
  rm -- "${objectfiles[@]}"
  [[ -z "$(find "$obj" -maxdepth 1 -type f \( -name '*.d' -o -name '*.gch' -o -name '*.pch' \) -print -quit)" ]] ||
    reject 'stale native precompiled header/dependency files survived cleanup'
  local archive="V${TOP}__ALL.a" log="$out/logs/portable-build.log"
  # -o treats all present generated *.cpp as immutable (there is no
  # __ALL.cpp in the 5.054 parallel-build artifact). No Verilator, Perl or
  # Chisel is invoked. VM_USER_CFLAGS overrides baked -march=native.
  if ! make -C "$obj" -f "V${TOP}.mk" -j4 "${immutable_args[@]}" \
      VERILATOR_ROOT="$(realpath -e "$out/toolchain")" \
      VM_USER_DIR="$sim" \
      VM_USER_CFLAGS="$flags" OPT_FAST=-O3 OPT_GLOBAL=-O3 \
      "$archive" "${runtime[@]}" > "$log" 2>&1; then
    tail -65 "$log" >&2 || true
    reject 'portable Verilator generated C++ build failed'
  fi
  # Explicitly gate the *actual* generated-model compilation transcript.
  ! grep -Fq -- '-march=native' "$log" || reject 'native compiler flag leaked into generated-model build'
  [[ "$(grep -Ec -- '(^| )-march=x86-64( |$)' "$log")" -ge "$(( ${#generated_cpp[@]} + ${#runtime[@]} ))" ]] ||
    reject 'not all generated model/runtime compilation commands proved portable ISA'
  [[ -s "$obj/$archive" && -s "$obj/verilated.o" && -s "$obj/verilated_save.o" ]] || reject 'missing rebuilt generic model/runtime objects'
  local -a runtime_paths=()
  for n in "${runtime[@]}"; do runtime_paths+=("$obj/$n"); done
  # The checkpoint host also MUST be rebuilt at portable ISA, not linked from
  # archived frozen model's opensbi_boot_main native executable.
  g++ -std=c++20 -O3 -march=x86-64 -mtune=generic \
    -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CHECKPOINT_G5 \
    -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
    -I"$obj" -I"$sim" -I"$sim/product_rv64_opensbi_shim" -I"$inc" -I"$inc/vltstd" \
    -c "$sim/product_rv64_checkpoint_g4_main.cpp" -o "$out/model/checkpoint_host.o"
  g++ -O3 -march=x86-64 -mtune=generic \
    "$out/model/checkpoint_host.o" "$obj/$archive" "${runtime_paths[@]}" \
    -pthread -ldl -latomic -o "$out/model/product-g5-checkpoint"
  [[ -x "$out/model/product-g5-checkpoint" ]] || reject 'portable checkpoint executable was not linked'
  local binary_sha
  binary_sha="$(sha "$out/model/product-g5-checkpoint")"
  [[ "$binary_sha" != "$FROZEN_SHA" ]] || reject 'old native binary was accidentally reused'
  printf 'qualification=UNPROVEN_PENDING_1795_1800_GOLDEN\nsource_commit=%s\nverilator_version=5.054\nportable_isa=x86-64\nportable_binary_sha256=%s\nfrozen_binary_sha256=%s\nmodel_run=%s\nfrozen_run=%s\n' \
    "$ORIGIN" "$binary_sha" "$FROZEN_SHA" "$MODEL_RUN" "$FROZEN_RUN" > "$out/model/identity.txt"
  # Old golden 1.8B is the immutable source, but portable identity is *new*.
  local initial="$out/checkpoints/c1800000000" proof_dir="$out/work/golden1795" proof="$proof_dir/replay"
  mkdir -p "$initial" "$proof_dir"
  cp --reflink=auto --sparse=always "$old1800/checkpoint.host" "$initial/checkpoint.host"
  cp -- "$old1800/checkpoint.rtl" "$initial/checkpoint.rtl"
  publish "$initial" 1800000000 462447787 'byte-qualified-golden' "$binary_sha"
  ln -s -- "$(realpath -e "$old1795/checkpoint.host")" "$proof.pre.host"
  ln -s -- "$(realpath -e "$old1795/checkpoint.rtl")" "$proof.pre.rtl"
  if ! timeout --signal=TERM --kill-after=10s 900s \
    "$out/model/product-g5-checkpoint" restore /dev/null "$proof" "$MODEL_SHA" \
    "$FIRMWARE_SHA" "$BOOT_ARG" "$MARKER" 5000000 > "$out/logs/golden-1795-to-1800.log" 2>&1; then
    tail -50 "$out/logs/golden-1795-to-1800.log" >&2 || true
    reject 'portable historical 1.795B->1.8B golden replay failed'
  fi
  cmp -s -- "$proof.restored.host" "$old1800/checkpoint.host" || reject 'portable replay host bytes differ from original 1.8B GOLDEN'
  cmp -s -- "$proof.restored.rtl" "$old1800/checkpoint.rtl" || reject 'portable replay RTL bytes differ from original 1.8B GOLDEN'
  grep -Eq '(^| )cycles=1800000000( |$)' "$out/logs/golden-1795-to-1800.log" || reject 'golden replay wrong endpoint'
  printf 'qualification=HISTORICAL_1795_1800_FULL_HOST_RTL_BYTE_IDENTICAL\nsource_commit=%s\nverilator_version=5.054\nportable_isa=x86-64\nportable_binary_sha256=%s\nfrozen_binary_sha256=%s\nmodel_run=%s\nfrozen_run=%s\ngolden_host_sha256=%s\ngolden_rtl_sha256=%s\n' \
    "$ORIGIN" "$binary_sha" "$FROZEN_SHA" "$MODEL_RUN" "$FROZEN_RUN" \
    "$(sha "$old1800/checkpoint.host")" "$(sha "$old1800/checkpoint.rtl")" > "$out/model/identity.txt"
  echo "G5_PORTABLE_GOLDEN_PASS cycles=1800000000 full_host_rtl_byte_identical=1 binary_sha256=$binary_sha"

  # Prove the new binary advances the next 5M and emit the first new durable
  # portable snapshot, so the following nine stages are 95M + 8 * 100M.
  local prefix="$out/work/smoke1805" commits stage="$out/checkpoints/c1805000000"
  commits="$(step5m "$out" "$initial" "$prefix" "$out/logs/smoke-1800-to-1805.log" "$binary_sha")"
  mkdir -p "$stage"
  mv -- "$prefix.restored.host" "$stage/checkpoint.host"
  mv -- "$prefix.restored.rtl" "$stage/checkpoint.rtl"
  publish "$stage" 1805000000 "$commits" 'portable-smoke-from-1800000000' "$binary_sha"
  printf 'G5_PORTABLE_SMOKE_PASS cycles=1805000000 commits=%s\n' "$commits"
}

run() {
  [[ $# == 2 ]] || reject 'run OUT 95000000|100000000'
  local out="$1" increment="$2" binary="$1/model/product-g5-checkpoint"
  [[ "$increment" == 95000000 || "$increment" == 100000000 ]] || reject 'invalid bounded segment'
  [[ -s "$out/model/identity.txt" && -x "$binary" ]] || reject 'portable model or qualification missing'
  [[ "$(field "$out/model/identity.txt" qualification)" == HISTORICAL_1795_1800_FULL_HOST_RTL_BYTE_IDENTICAL ]] || reject 'golden replay not qualified'
  local binary_sha current current_cycle current_commits end next prefix log commits stage previous_dir='' prev_cycle=0 old_dir old_cycle
  binary_sha="$(sha "$binary")"
  [[ "$binary_sha" == "$(field "$out/model/identity.txt" portable_binary_sha256)" ]] || reject 'portable executable SHA drift'
  current="$(latest "$out" "$binary_sha")"
  current_cycle="$(field "$current/manifest.txt" cycles)"
  current_commits="$(field "$current/manifest.txt" commits)"
  if [[ "$increment" == 95000000 ]]; then
    [[ "$current_cycle" == 1805000000 ]] || reject '95M stage must follow 1.805B smoke'
  fi
  end="$((current_cycle + increment))"
  (( end <= LIMIT )) || reject 'portable continuation exceeds 2.7B'
  for old_dir in "$out"/checkpoints/c*; do
    [[ -d "$old_dir" && "$old_dir" != "$current" ]] || continue
    old_cycle="$(field "$old_dir/manifest.txt" cycles)"
    if (( old_cycle > prev_cycle )); then prev_cycle="$old_cycle"; previous_dir="$old_dir"; fi
  done
  while (( current_cycle < end )); do
    next="$((current_cycle + 5000000))"
    prefix="$out/work/segment-$next"; log="$out/logs/segment-$next.log"
    commits="$(step5m "$out" "$current" "$prefix" "$log" "$binary_sha")"
    stage="$out/.staging/c$next"
    [[ ! -e "$stage" && ! -e "$out/checkpoints/c$next" ]] || reject "checkpoint collision $next"
    mkdir -p "$stage"
    mv -- "$prefix.restored.host" "$stage/checkpoint.host"
    mv -- "$prefix.restored.rtl" "$stage/checkpoint.rtl"
    publish "$stage" "$next" "$commits" "portable-from-$current_cycle" "$binary_sha"
    mv -- "$stage" "$out/checkpoints/c$next"
    rm -- "$prefix.pre.host" "$prefix.pre.rtl"
    if [[ -n "$previous_dir" && -d "$previous_dir" ]]; then
      portable_complete "$previous_dir" "$binary_sha" || reject 'cannot remove invalid prior checkpoint'
      rm -r -- "$previous_dir"
    fi
    previous_dir="$current"
    current="$out/checkpoints/c$next"
    current_cycle="$next"
    current_commits="$commits"
    printf 'G5_PORTABLE_STEP_PASS cycles=%s commits=%s binary_sha=%s\n' "$next" "$commits" "$binary_sha"
  done
  printf 'G5_PORTABLE_STAGE_PASS cycles=%s commits=%s increment=%s\n' "$current_cycle" "$current_commits" "$increment"
}

action="${1:-}"; [[ $# -ge 1 ]] || reject 'usage: prepare SOURCE MODEL CHECKPOINT OUT | run OUT INCREMENT'
shift
case "$action" in
  prepare) prepare "$@" ;;
  run) run "$@" ;;
  *) reject "unknown action $action" ;;
esac
