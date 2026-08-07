#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-supervisor-v1/software}"
rv32_dir="software/rv32"
prefix="${RISCV_PREFIX:-riscv-none-elf-}"
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

elf="$out_dir/supervisor-v1.elf"
bin="$out_dir/supervisor-v1.bin"

"$cc" "${cflags[@]}" \
  "$rv32_dir/crt0.S" "$rv32_dir/supervisor_v1_workload.S" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/supervisor-v1.map" \
  -lgcc -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/supervisor-v1.dis"
"$readelf" -h -l -S -A "$elf" > "$out_dir/supervisor-v1.elf.txt"

for mnemonic in mret sret csrw csrr ecall; do
  grep -Eq "[[:space:]]${mnemonic}([[:space:]]|$)" "$out_dir/supervisor-v1.dis" || {
    echo "ERROR: executable V1 workload is missing required instruction: $mnemonic" >&2
    exit 1
  }
done
for csr in medeleg mideleg stvec sstatus sscratch sepc scause stval; do
  grep -q "$csr" "$out_dir/supervisor-v1.dis" || {
    echo "ERROR: executable V1 workload is missing required CSR: $csr" >&2
    exit 1
  }
done

bytes="$(stat -c '%s' "$bin")"
words="$(((bytes + 3) / 4))"
digest="$(sha256sum "$bin" | awk '{print $1}')"
printf 'contract=rv32im-supervisor-v1-executable\n' > "$out_dir/result.txt"
printf 'march=rv32im_zicsr\n' >> "$out_dir/result.txt"
printf 'modes=M,S,U\n' >> "$out_dir/result.txt"
printf 'sv32=disabled\n' >> "$out_dir/result.txt"
printf 'mideleg=WARL-zero\n' >> "$out_dir/result.txt"
printf 'bytes=%s\nwords=%s\nsha256=%s\n' "$bytes" "$words" "$digest" >> "$out_dir/result.txt"
cat "$out_dir/result.txt"
