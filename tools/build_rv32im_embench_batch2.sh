#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-build/rv32im-embench-batch2/software}"
platform_dir="software/upstream/embench"
rv32_dir="software/rv32"
prefix="${RISCV_PREFIX:-riscv64-unknown-elf-}"
cc="${prefix}gcc"
objcopy="${prefix}objcopy"
objdump="${prefix}objdump"
readelf="${prefix}readelf"
benchmarks=(aha-mont64 huffbench slre wikisort)

for tool in "$cc" "$objcopy" "$objdump" "$readelf" git python3; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool is missing: $tool" >&2
    exit 1
  }
done

embench_dir="$(bash tools/fetch_embench.sh)"
revision="$(git -C "$embench_dir" rev-parse HEAD)"
work_src="$out_dir/scaled-src"

base_cflags=(
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
  -Wno-unused-variable
  -Wno-unused-but-set-variable
  -I"$platform_dir/include"
  -I"$embench_dir/support"
  -DWARMUP_HEAT=0
  -DGLOBAL_SCALE_FACTOR=1
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
mkdir -p "$out_dir" "$work_src"
printf '%s\n' "$revision" > "$out_dir/embench.revision"
cp "$embench_dir/COPYING" "$out_dir/Embench-COPYING"
"$cc" --version > "$out_dir/compiler-version.txt"
printf '%s\n' "${base_cflags[*]}" > "$out_dir/compiler-flags.txt"

manifest="$out_dir/manifest.tsv"
printf 'benchmark\tbytes\twords\tsha256\tsources\n' > "$manifest"
: > "$out_dir/correctness-scaling.patch"

for benchmark in "${benchmarks[@]}"; do
  source_dir="$embench_dir/src/$benchmark"
  scaled_dir="$work_src/$benchmark"
  object_dir="$out_dir/obj-$benchmark"
  elf="$out_dir/$benchmark.elf"
  bin="$out_dir/$benchmark.bin"

  test -d "$source_dir" || {
    echo "ERROR: pinned Embench revision has no benchmark: $benchmark" >&2
    exit 1
  }

  mkdir -p "$scaled_dir" "$object_dir"
  cp -R "$source_dir"/. "$scaled_dir"/

  # Embench local scale factors normalize timed runs to seconds. This is a
  # correctness corpus, so execute the algorithm body once while preserving
  # upstream inputs and verify_benchmark() exactly.
  python3 - "$scaled_dir" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
seen = 0
for path in sorted(root.glob("*.c")):
    text = path.read_text(encoding="utf-8")
    replaced, count = re.subn(
        r"^#define[ \t]+LOCAL_SCALE_FACTOR[ \t]+[0-9]+[ \t]*$",
        "#define LOCAL_SCALE_FACTOR 1",
        text,
        flags=re.MULTILINE,
    )
    if count:
        path.write_text(replaced, encoding="utf-8")
        seen += count
if seen != 1:
    raise SystemExit(f"expected exactly one LOCAL_SCALE_FACTOR in {root}, found {seen}")
PY

  diff -u -N "$source_dir" "$scaled_dir" >> "$out_dir/correctness-scaling.patch" || true

  objects=()
  while IFS= read -r source; do
    object="$object_dir/$(basename "${source%.c}").o"
    "$cc" "${base_cflags[@]}" -I"$scaled_dir" -c "$source" -o "$object"
    objects+=("$object")
  done < <(find "$scaled_dir" -maxdepth 1 -type f -name '*.c' | sort)

  for source in \
    "$embench_dir/support/main.c" \
    "$embench_dir/support/beebsc.c" \
    "$platform_dir/boardsupport.c" \
    "$platform_dir/runtime.c"; do
    stem="$(basename "${source%.c}")"
    object="$object_dir/$stem.o"
    "$cc" "${base_cflags[@]}" -I"$scaled_dir" -c "$source" -o "$object"
    objects+=("$object")
  done

  "$cc" "${base_cflags[@]}" \
    "$rv32_dir/crt0.S" "${objects[@]}" \
    "${ldflags[@]}" "-Wl,-Map,$out_dir/$benchmark.map" \
    -lgcc -o "$elf"

  "$objcopy" -O binary "$elf" "$bin"
  "$objdump" -d -S "$elf" > "$out_dir/$benchmark.dis"
  "$readelf" -h -l -S "$elf" > "$out_dir/$benchmark.elf.txt"

  bytes="$(stat -c '%s' "$bin")"
  words="$(((bytes + 3) / 4))"
  digest="$(sha256sum "$bin" | awk '{print $1}')"
  source_count="$(find "$scaled_dir" -maxdepth 1 -type f -name '*.c' | wc -l)"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$benchmark" "$bytes" "$words" "$digest" "$source_count" >> "$manifest"
  printf 'built %-12s %7s bytes sha256=%s\n' "$benchmark" "$bytes" "$digest"
done

cat > "$out_dir/batch.txt" <<EOF
source=embench-iot@$revision
march=rv32im
mabi=ilp32
optimization=O2
warmup_heat=0
global_scale_factor=1
local_scale_factor=1
benchmarks=${benchmarks[*]}
EOF

cat "$out_dir/batch.txt"
cat "$manifest"
