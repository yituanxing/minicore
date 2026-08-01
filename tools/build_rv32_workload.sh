#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32/software}"
source_dir="software/rv32"
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

cflags=(
  -march=rv32i
  -mabi=ilp32
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
elf="$out_dir/rv32_smoke.elf"
bin="$out_dir/rv32_smoke.bin"

echo "== compile GCC RV32I/ILP32 workload =="
"$cc" "${cflags[@]}" \
  "$source_dir/crt0.S" "$source_dir/rv32_smoke.c" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/rv32_smoke.map" \
  -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/rv32_smoke.dis"
"$readelf" -h -l -S "$elf" > "$out_dir/rv32_smoke.elf.txt"

bytes="$(stat -c '%s' "$bin")"
words="$(((bytes + 3) / 4))"
digest="$(sha256sum "$bin" | awk '{print $1}')"
compiler="$($cc --version | head -n 1)"

cat > "$out_dir/manifest.txt" <<EOF
name=rv32_smoke
march=rv32i
mabi=ilp32
optimization=O2
words=$words
bytes=$bytes
sha256=$digest
compiler=$compiler
EOF

echo "wrote $bytes bytes ($words words): $bin sha256=$digest"
