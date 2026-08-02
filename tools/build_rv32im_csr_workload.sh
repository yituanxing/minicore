#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-csr/software}"
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

mkdir -p "$out_dir"
elf="$out_dir/rv32im-csr.elf"
bin="$out_dir/rv32im-csr.bin"

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

"$cc" --version > "$out_dir/compiler-version.txt"
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"

"$cc" "${cflags[@]}" \
  "$rv32_dir/crt0.S" "$rv32_dir/csr_workload.S" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/rv32im-csr.map" \
  -lgcc -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/rv32im-csr.dis"
"$readelf" -h -l -S "$elf" > "$out_dir/rv32im-csr.elf.txt"

bytes="$(stat -c '%s' "$bin")"
words="$(((bytes + 3) / 4))"
digest="$(sha256sum "$bin" | awk '{print $1}')"

cat > "$out_dir/manifest.txt" <<EOF
compiler=riscv64-unknown-elf-gcc
march=rv32im_zicsr
mabi=ilp32
optimization=O2
binary_bytes=$bytes
binary_words=$words
binary_sha256=$digest
EOF

cat "$out_dir/manifest.txt"
