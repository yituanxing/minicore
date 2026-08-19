#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv64im-sv39-pmp-v1/software}"
prefix="${RISCV_PREFIX:-riscv-none-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"
nm="${prefix}nm"

for tool in "$cc" "$objcopy" "$objdump" "$readelf" "$nm"; do
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
  -Wl,-T,software/rv64/sv39_pmp_v1_linker.ld
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"

elf="$out_dir/sv39-pmp-v1.elf"
bin="$out_dir/sv39-pmp-v1.bin"

"$cc" "${cflags[@]}" software/rv64/sv39_pmp_v1_workload.S \
  "${ldflags[@]}" -Wl,-Map,"$out_dir/sv39-pmp-v1.map" -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/sv39-pmp-v1.dis"
"$readelf" -h -l -S -A "$elf" > "$out_dir/sv39-pmp-v1.elf.txt"
"$nm" -n "$elf" > "$out_dir/sv39-pmp-v1.nm"

grep -q 'Class:[[:space:]]*ELF64' "$out_dir/sv39-pmp-v1.elf.txt" || {
  echo 'ERROR: Sv39 PMP V1 workload is not ELF64' >&2
  exit 1
}
for mnemonic in mret sret ecall ld sd; do
  grep -Eq "[[:space:]]${mnemonic}([[:space:]]|$)" "$out_dir/sv39-pmp-v1.dis" || {
    echo "ERROR: Sv39 PMP V1 workload is missing required instruction: $mnemonic" >&2
    exit 1
  }
done
grep -Eq '[[:space:]]sfence\.vma([[:space:]]|$)' "$out_dir/sv39-pmp-v1.dis" || {
  echo 'ERROR: Sv39 PMP V1 workload is missing sfence.vma' >&2
  exit 1
}
for csr in satp pmpcfg0 pmpaddr0 mtvec stvec medeleg mstatus mepc sstatus sepc scause stval sscratch; do
  grep -q "$csr" "$out_dir/sv39-pmp-v1.dis" || {
    echo "ERROR: Sv39 PMP V1 workload is missing required CSR: $csr" >&2
    exit 1
  }
done

symbol_addr() {
  local name="$1"
  awk -v name="$name" '$3 == name { print "0x" $1; exit }' "$out_dir/sv39-pmp-v1.nm"
}
[[ "$(symbol_addr _start)" == 0x0000000080000000 ]] || {
  echo "ERROR: machine entry drifted: $(symbol_addr _start)" >&2
  exit 1
}
[[ "$(symbol_addr supervisor_entry)" == 0x0000000080001000 ]] || {
  echo "ERROR: supervisor entry drifted: $(symbol_addr supervisor_entry)" >&2
  exit 1
}
[[ "$(symbol_addr user_entry)" == 0x0000000080002000 ]] || {
  echo "ERROR: user entry drifted: $(symbol_addr user_entry)" >&2
  exit 1
}
[[ "$(symbol_addr user_marker)" == 0x0000000080003000 ]] || {
  echo "ERROR: user data drifted: $(symbol_addr user_marker)" >&2
  exit 1
}
[[ "$(symbol_addr __sv39_root)" == 0x0000000080004000 ]] || {
  echo "ERROR: Sv39 root drifted: $(symbol_addr __sv39_root)" >&2
  exit 1
}
[[ "$(symbol_addr __sv39_level1)" == 0x0000000080005000 ]] || {
  echo "ERROR: Sv39 level1 drifted: $(symbol_addr __sv39_level1)" >&2
  exit 1
}
[[ "$(symbol_addr __sv39_level0)" == 0x0000000080006000 ]] || {
  echo "ERROR: Sv39 level0 drifted: $(symbol_addr __sv39_level0)" >&2
  exit 1
}

bytes="$(stat -c '%s' "$bin")"
digest="$(sha256sum "$bin" | awk '{print $1}')"
{
  echo 'contract=rv64-sv39-pmp-v1-executable'
  echo 'march=rv64im_zicsr'
  echo 'mabi=lp64'
  echo 'modes=M,S,U'
  echo 'vm=Sv39'
  echo 'page_levels=3'
  echo 'pte_bytes=8'
  echo 'pmp_entries=16'
  echo 'paddr_bits=56'
  echo 'user_data=64-bit-ld-sd'
  printf 'bytes=%s\nsha256=%s\n' "$bytes" "$digest"
} > "$out_dir/result.txt"
cat "$out_dir/result.txt"
