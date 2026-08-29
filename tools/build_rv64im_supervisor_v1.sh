#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv64im-supervisor-v1/software}"
shared_dir="software/rv32"
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
  -march=rv64im_zicsr
  -mabi=lp64
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
  "-Wl,-T,$shared_dir/linker.ld"
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"

elf="$out_dir/supervisor-v1.elf"
bin="$out_dir/supervisor-v1.bin"

# crt0/linker and the Supervisor V1 workload are XLEN-neutral. Keep one
# executable privilege contract for RV32 and RV64 instead of cloning control
# flow by XLEN. / 启动、链接与 V1 特权态 workload 共用一份源码。
"$cc" "${cflags[@]}" \
  "$shared_dir/crt0.S" "$shared_dir/supervisor_v1_workload.S" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/supervisor-v1.map" \
  -lgcc -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/supervisor-v1.dis"
"$readelf" -h -l -S -A "$elf" > "$out_dir/supervisor-v1.elf.txt"

grep -q 'Class:[[:space:]]*ELF64' "$out_dir/supervisor-v1.elf.txt" || {
  echo 'ERROR: RV64 Supervisor V1 workload is not ELF64' >&2
  exit 1
}
for mnemonic in mret sret csrw csrr ecall; do
  grep -Eq "[[:space:]]${mnemonic}([[:space:]]|$)" "$out_dir/supervisor-v1.dis" || {
    echo "ERROR: RV64 Supervisor V1 workload is missing required instruction: $mnemonic" >&2
    exit 1
  }
done
for csr in mstatus medeleg mideleg stvec sstatus sscratch sepc scause stval; do
  grep -q "$csr" "$out_dir/supervisor-v1.dis" || {
    echo "ERROR: RV64 Supervisor V1 workload is missing required CSR: $csr" >&2
    exit 1
  }
done

bytes="$(stat -c '%s' "$bin")"
digest="$(sha256sum "$bin" | awk '{print $1}')"
{
  echo 'contract=rv64im-supervisor-v1-executable'
  echo 'march=rv64im_zicsr'
  echo 'mabi=lp64'
  echo 'modes=M,S,U'
  echo 'vm=bare'
  echo 'sxl=64-fixed'
  echo 'uxl=64-fixed'
  printf 'bytes=%s\nsha256=%s\n' "$bytes" "$digest"
} > "$out_dir/result.txt"
cat "$out_dir/result.txt"
