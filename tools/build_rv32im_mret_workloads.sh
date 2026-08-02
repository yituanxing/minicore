#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-mret/software}"
rv32_dir="software/rv32"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"

for tool in "$cc" "$objcopy" "$objdump" "$readelf"; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool is missing: $tool" >&2
    exit 1
  }
done

rm -rf "$out_dir"
mkdir -p "$out_dir"
"$cc" --version > "$out_dir/compiler-version.txt"

cflags=(
  -march=rv32im_zicsr
  -mabi=ilp32
  -mcmodel=medany
  -mno-relax
  -msmall-data-limit=0
  -O2
  -g
  -ffreestanding
  -fno-stack-protector
  -fno-pic
  -fno-plt
  -fno-unwind-tables
  -fno-asynchronous-unwind-tables
)
ldflags=(
  -nostdlib
  -nostartfiles
  -static
  "-Wl,-T,$rv32_dir/linker.ld"
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"

cases=(
  "ecall-next:1"
  "ebreak-rewrite:2"
  "load-fault:3"
  "double-ecall:4"
)

: > "$out_dir/manifest.txt"
for entry in "${cases[@]}"; do
  name="${entry%%:*}"
  number="${entry##*:}"
  elf="$out_dir/$name.elf"
  bin="$out_dir/$name.bin"

  "$cc" "${cflags[@]}" -DMRET_CASE="$number" \
    "$rv32_dir/crt0.S" "$rv32_dir/mret_workload.S" \
    "${ldflags[@]}" "-Wl,-Map,$out_dir/$name.map" \
    -lgcc -o "$elf"

  "$objcopy" -O binary "$elf" "$bin"
  "$objdump" -d -S "$elf" > "$out_dir/$name.dis"
  "$readelf" -h -l -S "$elf" > "$out_dir/$name.elf.txt"

  bytes="$(stat -c '%s' "$bin")"
  words="$(((bytes + 3) / 4))"
  digest="$(sha256sum "$bin" | awk '{print $1}')"
  printf '%s %s %s %s\n' "$name" "$bytes" "$words" "$digest" >> "$out_dir/manifest.txt"
done

cat "$out_dir/manifest.txt"
