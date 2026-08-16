#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-coremark/software}"
port_dir="software/upstream/coremark"
rv32_dir="software/rv32"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
march="${AETHERCORE_RV32_MARCH:-rv32im}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"

case "$march" in
  rv32im|rv32imc) ;;
  *)
    echo "ERROR: unsupported CoreMark RV32 march: $march" >&2
    exit 2
    ;;
esac

for tool in "$cc" "$objcopy" "$objdump" "$readelf" git; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool is missing: $tool" >&2
    exit 1
  }
done

coremark_dir="$(bash tools/fetch_coremark.sh)"
coremark_revision="$(git -C "$coremark_dir" rev-parse HEAD)"
name="coremark_${march}_O2"
obj_dir="$out_dir/obj-$name"
elf="$out_dir/$name.elf"
bin="$out_dir/$name.bin"
dis="$out_dir/$name.dis"
compiler_flags="${march^^} freestanding correctness run"

base_cflags=(
  -std=c99
  "-march=$march"
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
  "-DCOMPILER_FLAGS=\"$compiler_flags\""
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

upstream_sources=(
  core_list_join.c
  core_main.c
  core_matrix.c
  core_state.c
  core_util.c
)

rm -rf "$out_dir"
mkdir -p "$obj_dir"
printf '%s\n' "$coremark_revision" > "$out_dir/coremark.revision"
cp "$coremark_dir/LICENSE.md" "$out_dir/CoreMark-LICENSE.md"
"$cc" --version > "$out_dir/compiler-version.txt"
printf '%s\n' "${base_cflags[*]}" > "$out_dir/compiler-flags.txt"

echo "== compile pinned CoreMark for ${march^^}: revision=$coremark_revision =="
objects=()
for source in "${upstream_sources[@]}"; do
  object="$obj_dir/${source%.c}.o"
  extra=()
  if [[ "$source" == "core_main.c" ]]; then
    extra=(-Dmain=coremark_main)
  fi
  "$cc" "${base_cflags[@]}" "${extra[@]}" \
    -c "$coremark_dir/$source" -o "$object"
  objects+=("$object")
done

for source in core_portme.c coremark_wrapper.c; do
  object="$obj_dir/${source%.c}.o"
  "$cc" "${base_cflags[@]}" \
    -c "$port_dir/$source" -o "$object"
  objects+=("$object")
done

"$cc" "${base_cflags[@]}" \
  "$rv32_dir/crt0.S" "${objects[@]}" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/$name.map" \
  -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$dis"
"$readelf" -h -l -S "$elf" > "$out_dir/$name.elf.txt"

bytes="$(stat -c '%s' "$bin")"
words="$(((bytes + 3) / 4))"
digest="$(sha256sum "$bin" | awk '{print $1}')"

cat > "$out_dir/manifest.txt" <<EOF
name=$name
source=coremark@$coremark_revision
march=$march
mabi=ilp32
optimization=O2
iterations=2
total_data_size=2000
words=$words
bytes=$bytes
sha256=$digest
EOF

if [[ "$march" == *c* ]]; then
  compressed_instructions="$(grep -Ec '^[[:space:]]*[0-9a-f]+:[[:space:]]+[0-9a-f]{4}[[:space:]]' "$dis" || true)"
  if [[ "$compressed_instructions" -le 0 ]]; then
    echo "ERROR: $name advertises C but objdump contains no 16-bit instructions" >&2
    exit 1
  fi
  printf 'compressed_instructions=%s\n' "$compressed_instructions" >> "$out_dir/manifest.txt"
  echo "verified compiler-generated RV32C instructions: $compressed_instructions"
fi

printf 'wrote %s bytes (%s words): %s sha256=%s\n' \
  "$bytes" "$words" "$bin" "$digest"
