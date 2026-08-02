#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-littlefs/software}"
platform_dir="software/upstream/embench"
workload="software/upstream/littlefs/basic_workload.c"
rv32_dir="software/rv32"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"
nm="${prefix}nm"

for tool in "$cc" "$objcopy" "$objdump" "$readelf" "$nm" git; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool is missing: $tool" >&2
    exit 1
  }
done

littlefs_dir="$(bash tools/fetch_littlefs.sh)"
revision="$(git -C "$littlefs_dir" rev-parse HEAD)"
object_dir="$out_dir/obj"
elf="$out_dir/littlefs-basic.elf"
bin="$out_dir/littlefs-basic.bin"

cflags=(
  -std=c99
  -march=rv32im
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
  -Wno-unused-parameter
  -Wno-sign-compare
  -DLFS_NO_MALLOC
  -DLFS_NO_ASSERT
  -DLFS_NO_DEBUG
  -DLFS_NO_WARN
  -DLFS_NO_ERROR
  -I"$platform_dir/include"
  -I"$littlefs_dir"
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

rm -rf "$out_dir"
mkdir -p "$object_dir"
printf '%s\n' "$revision" > "$out_dir/littlefs.revision"
cp "$littlefs_dir/LICENSE.md" "$out_dir/littlefs-LICENSE.md"
"$cc" --version > "$out_dir/compiler-version.txt"
printf '%s\n' "${cflags[*]}" > "$out_dir/compiler-flags.txt"
sha256sum "$littlefs_dir/lfs.c" "$littlefs_dir/lfs.h" \
  "$littlefs_dir/lfs_util.c" "$littlefs_dir/lfs_util.h" \
  > "$out_dir/upstream-source-sha256.txt"

sources=(
  "$littlefs_dir/lfs.c"
  "$littlefs_dir/lfs_util.c"
  "$workload"
  "$platform_dir/runtime.c"
)

objects=()
for source in "${sources[@]}"; do
  stem="$(basename "${source%.c}")"
  object="$object_dir/$stem.o"
  "$cc" "${cflags[@]}" -c "$source" -o "$object"
  objects+=("$object")
done

"$cc" "${cflags[@]}" \
  "$rv32_dir/crt0.S" "${objects[@]}" \
  "${ldflags[@]}" "-Wl,-Map,$out_dir/littlefs-basic.map" \
  -lgcc -o "$elf"

"$objcopy" -O binary "$elf" "$bin"
"$objdump" -d -S "$elf" > "$out_dir/littlefs-basic.dis"
"$readelf" -h -l -S "$elf" > "$out_dir/littlefs-basic.elf.txt"
"$nm" -n "$elf" > "$out_dir/littlefs-basic.nm"

bytes="$(stat -c '%s' "$bin")"
words="$(((bytes + 3) / 4))"
digest="$(sha256sum "$bin" | awk '{print $1}')"

cat > "$out_dir/manifest.txt" <<EOF
source=littlefs@$revision
compiler=riscv64-unknown-elf-gcc
march=rv32im
mabi=ilp32
optimization=O2
lfs_no_malloc=1
lfs_no_assert=1
lfs_no_debug=1
lfs_no_warn=1
lfs_no_error=1
flash_block_size=256
flash_block_count=128
flash_bytes=32768
read_size=16
prog_size=16
cache_size=64
lookahead_size=16
inline_files=disabled
binary_bytes=$bytes
binary_words=$words
binary_sha256=$digest
EOF

cat "$out_dir/manifest.txt"
