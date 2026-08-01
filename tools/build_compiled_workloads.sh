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

programs=(call_stack memory arithmetic)
stalls=(0 4 3)

cflags=(
  -march=rv64im
  -mabi=lp64
  -mcmodel=medany
  -mno-relax
  -msmall-data-limit=0
  -O2
  -g
  -ffreestanding
  -fno-builtin
  -fno-stack-protector
  -fno-pic
  -fno-plt
  -fno-unwind-tables
  -fno-asynchronous-unwind-tables
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
printf '# name stall words bytes sha256\n' > "$out_dir/manifest.txt"

for index in "${!programs[@]}"; do
  name="${programs[$index]}"
  stall="${stalls[$index]}"
  elf="$out_dir/$name.elf"
  bin="$out_dir/$name.bin"

  echo "== compile RV64IM workload: $name =="
  "$cc" "${cflags[@]}" \
    "$source_dir/crt0.S" "$source_dir/$name.c" \
    "${ldflags[@]}" "-Wl,-Map,$out_dir/$name.map" \
    -o "$elf"

  "$objcopy" -O binary "$elf" "$bin"
  "$objdump" -d -S "$elf" > "$out_dir/$name.dis"
  "$readelf" -h -l -S "$elf" > "$out_dir/$name.elf.txt"

  bytes="$(stat -c '%s' "$bin")"
  words="$(((bytes + 3) / 4))"
  digest="$(sha256sum "$bin" | awk '{print $1}')"
  printf '%s %s %s %s %s\n' "$name" "$stall" "$words" "$bytes" "$digest" \
    >> "$out_dir/manifest.txt"
  echo "wrote $bytes bytes ($words words): $bin stall-period=$stall sha256=$digest"
done
