#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/compiled-workloads}"
source_dir="software/compiled"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"

for tool in "$cc" "$objcopy" "$objdump" "$readelf"; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required RISC-V tool is missing: $tool" >&2
    exit 1
  }
done

# output-name source-name optimization stall-period
matrix=(
  "call_stack call_stack O2 0"
  "memory memory O2 4"
  "arithmetic arithmetic O2 3"
  "sort_O0 sort O0 3"
  "sort_O2 sort O2 0"
  "sort_Os sort Os 5"
  "crc_hash_O0 crc_hash O0 4"
  "crc_hash_O2 crc_hash O2 0"
  "crc_hash_Os crc_hash Os 7"
  "mixed_integer_O0 mixed_integer O0 5"
  "mixed_integer_O2 mixed_integer O2 3"
  "mixed_integer_Os mixed_integer Os 0"
)

base_cflags=(
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
  -mstrict-align
  -Wall
  -Wextra
  -Werror
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

rm -rf "$out_dir"
mkdir -p "$out_dir"
printf '# name source optimization stall words bytes sha256\n' > "$out_dir/manifest.txt"

for entry in "${matrix[@]}"; do
  read -r name source optimization stall <<< "$entry"
  elf="$out_dir/$name.elf"
  bin="$out_dir/$name.bin"

  echo "== compile RV64IM workload: $name source=$source optimization=-$optimization =="
  "$cc" "${base_cflags[@]}" "-$optimization" \
    "$source_dir/crt0.S" "$source_dir/$source.c" \
    "${ldflags[@]}" "-Wl,-Map,$out_dir/$name.map" \
    -o "$elf"

  "$objcopy" -O binary "$elf" "$bin"
  "$objdump" -d -S "$elf" > "$out_dir/$name.dis"
  "$readelf" -h -l -S "$elf" > "$out_dir/$name.elf.txt"

  bytes="$(stat -c '%s' "$bin")"
  words="$(((bytes + 3) / 4))"
  digest="$(sha256sum "$bin" | awk '{print $1}')"
  printf '%s %s %s %s %s %s %s\n' \
    "$name" "$source" "$optimization" "$stall" "$words" "$bytes" "$digest" \
    >> "$out_dir/manifest.txt"
  echo "wrote $bytes bytes ($words words): $bin stall-period=$stall sha256=$digest"
done
