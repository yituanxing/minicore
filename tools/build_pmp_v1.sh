#!/usr/bin/env bash
set -euo pipefail

xlen="${1:-}"
out_dir="${2:-}"
case "$xlen" in
  32)
    march=rv32im_zicsr
    mabi=ilp32
    elf_class=ELF32
    expected_faults=4
    ;;
  64)
    march=rv64im_zicsr
    mabi=lp64
    elf_class=ELF64
    expected_faults=6
    ;;
  *)
    echo 'usage: tools/build_pmp_v1.sh <32|64> <output-dir>' >&2
    exit 2
    ;;
esac
[[ -n "$out_dir" ]] || {
  echo 'usage: tools/build_pmp_v1.sh <32|64> <output-dir>' >&2
  exit 2
}

shared_dir="software/rv32"
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
  "-march=$march"
  "-mabi=$mabi"
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
  "-Wl,-T,$shared_dir/pmp_v1_linker.ld"
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"

elf="$out_dir/pmp-v1.elf"
bin="$out_dir/pmp-v1.bin"

# crt0, linker geometry and the PMP isolation workload are shared by RV32/RV64.
# 启动、链接布局与 PMP 隔离 workload 在两个 XLEN 间共用同一份源码。
"$cc" "${cflags[@]}" \
  "$shared_dir/crt0.S" "$shared_dir/pmp_v1_workload.S" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/pmp-v1.map" \
  -lgcc -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/pmp-v1.dis"
"$readelf" -h -l -S -A "$elf" > "$out_dir/pmp-v1.elf.txt"
"$nm" -n "$elf" > "$out_dir/pmp-v1.nm"

grep -q "Class:[[:space:]]*$elf_class" "$out_dir/pmp-v1.elf.txt" || {
  echo "ERROR: PMP V1 workload is not $elf_class" >&2
  exit 1
}
for mnemonic in mret csrw csrr ecall; do
  grep -Eq "[[:space:]]${mnemonic}([[:space:]]|$)" "$out_dir/pmp-v1.dis" || {
    echo "ERROR: PMP V1 workload is missing required instruction: $mnemonic" >&2
    exit 1
  }
done
for csr in pmpcfg0 pmpaddr0 pmpaddr1 pmpaddr2 mtvec mscratch mepc mcause mtval; do
  grep -q "$csr" "$out_dir/pmp-v1.dis" || {
    echo "ERROR: PMP V1 workload is missing required CSR: $csr" >&2
    exit 1
  }
done

symbol_addr() {
  local name="$1"
  awk -v name="$name" '$3 == name { print "0x" $1; exit }' "$out_dir/pmp-v1.nm"
}
main_addr="$(symbol_addr main)"
user_text_addr="$(symbol_addr user_entry)"
user_data_addr="$(symbol_addr user_data_begin)"
[[ "$main_addr" == 0x800000* || "$main_addr" == 0x00000000800000* ]] || {
  echo "ERROR: PMP V1 machine text is outside the frozen 0x80000000 page: $main_addr" >&2
  exit 1
}
[[ "$user_text_addr" == 0x80001000 || "$user_text_addr" == 0x0000000080001000 ]] || {
  echo "ERROR: user_entry identity drifted: $user_text_addr" >&2
  exit 1
}
[[ "$user_data_addr" == 0x80002000 || "$user_data_addr" == 0x0000000080002000 ]] || {
  echo "ERROR: user_data_begin identity drifted: $user_data_addr" >&2
  exit 1
}

bytes="$(stat -c '%s' "$bin")"
digest="$(sha256sum "$bin" | awk '{print $1}')"
{
  echo 'contract=pmp-v1-shared-executable'
  echo "xlen=$xlen"
  echo "march=$march"
  echo "mabi=$mabi"
  echo 'modes=M,S,U'
  echo 'vm=bare'
  echo 'pmp_entries=16'
  echo 'pmp_regions=machine-deny,user-rx,user-rw'
  echo "expected_faults=$expected_faults"
  if [[ "$xlen" == 64 ]]; then
    echo 'pa_boundary=56'
    echo 'pa_overflow_proof=user-rw-load,user-rx-execute'
  fi
  printf 'bytes=%s\nsha256=%s\n' "$bytes" "$digest"
} > "$out_dir/result.txt"
cat "$out_dir/result.txt"
