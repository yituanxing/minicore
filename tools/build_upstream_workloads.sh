#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/upstream-workloads}"
source_dir="software/compiled"
port_dir="software/upstream/coremark"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"

for tool in "$cc" "$objcopy" "$objdump" "$readelf" git; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool is missing: $tool" >&2
    exit 1
  }
done

coremark_dir="$(bash tools/fetch_coremark.sh)"
coremark_revision="$(git -C "$coremark_dir" rev-parse HEAD)"

# output-name optimization stall-period
matrix=(
  "coremark_O2 O2 5"
)

base_cflags=(
  -std=c99
  -march=rv64im
  -mabi=lp64
  -mcmodel=medany
  -mno-relax
  -msmall-data-limit=0
  -g
  -ffreestanding
  -fno-builtin
  -fno-stack-protector
  -fno-pic
  -fno-plt
  -fno-unwind-tables
  -fno-asynchronous-unwind-tables
  -fno-tree-loop-distribute-patterns
  -ffunction-sections
  -fdata-sections
  -fno-common
  -mstrict-align
  -Wall
  -Wextra
  -I"$coremark_dir"
  -I"$port_dir"
  -DPERFORMANCE_RUN=1
  -DITERATIONS=2
  -DTOTAL_DATA_SIZE=2000
  -DMAIN_HAS_NOARGC=1
)

ldflags=(
  -nostdlib
  -nostartfiles
  -static
  "-Wl,-T,$source_dir/linker.ld"
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)

upstream_sources=(
  core_list_join.c
  core_main.c
  core_matrix.c
  core_state.c
  core_util.c
)

rm -rf "$out_dir"
mkdir -p "$out_dir"
printf '# name source optimization stall words bytes sha256\n' > "$out_dir/manifest.txt"
printf '%s\n' "$coremark_revision" > "$out_dir/coremark.revision"
cp "$coremark_dir/LICENSE.md" "$out_dir/CoreMark-LICENSE.md"

for entry in "${matrix[@]}"; do
  read -r name optimization stall <<< "$entry"
  obj_dir="$out_dir/obj-$name"
  elf="$out_dir/$name.elf"
  bin="$out_dir/$name.bin"
  mkdir -p "$obj_dir"

  echo "== compile pinned CoreMark: $name revision=$coremark_revision optimization=-$optimization =="

  objects=()
  for source in "${upstream_sources[@]}"; do
    object="$obj_dir/${source%.c}.o"
    extra=()
    if [[ "$source" == "core_main.c" ]]; then
      extra=(-Dmain=coremark_main)
    fi
    "$cc" "${base_cflags[@]}" "-$optimization" "${extra[@]}" \
      -c "$coremark_dir/$source" -o "$object"
    objects+=("$object")
  done

  for source in core_portme.c coremark_wrapper.c; do
    object="$obj_dir/${source%.c}.o"
    "$cc" "${base_cflags[@]}" "-$optimization" \
      -c "$port_dir/$source" -o "$object"
    objects+=("$object")
  done

  "$cc" "${base_cflags[@]}" "-$optimization" \
    "$source_dir/crt0.S" "${objects[@]}" \
    "${ldflags[@]}" "-Wl,-Map,$out_dir/$name.map" \
    -o "$elf"

  "$objcopy" -O binary "$elf" "$bin"
  "$objdump" -d -S "$elf" > "$out_dir/$name.dis"
  "$readelf" -h -l -S "$elf" > "$out_dir/$name.elf.txt"

  bytes="$(stat -c '%s' "$bin")"
  words="$(((bytes + 3) / 4))"
  digest="$(sha256sum "$bin" | awk '{print $1}')"
  printf '%s %s %s %s %s %s %s\n' \
    "$name" "coremark@$coremark_revision" "$optimization" "$stall" \
    "$words" "$bytes" "$digest" >> "$out_dir/manifest.txt"
  echo "wrote $bytes bytes ($words words): $bin stall-period=$stall sha256=$digest"
done
