#!/usr/bin/env bash
set -euo pipefail

revision="${NEMU_REVISION:-ad6bfde6241f2fc1e864b1efb2bed99b3670eb73}"
work_dir="${1:-build/rv32-nemu-probe}"
source_dir="$work_dir/nemu"
evidence_dir="$work_dir/evidence"

rm -rf "$work_dir"
mkdir -p "$source_dir" "$evidence_dir"

git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/OpenXiangShan/NEMU.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout -q FETCH_HEAD

git -C "$source_dir" rev-parse HEAD | tee "$evidence_dir/revision.txt"

find "$source_dir/configs" -maxdepth 1 -type f -printf '%f\n' | sort \
  | tee "$evidence_dir/configs.txt"

grep -RIn --exclude-dir=.git -E 'riscv32|ISA_riscv32|RV32' \
  "$source_dir/configs" "$source_dir/src" "$source_dir/include" \
  > "$evidence_dir/riscv32-source-matches.txt" || true

grep -RIn --exclude-dir=.git -E \
  'difftest_regcpy|DIFFTEST.*REG|difftestStateEnd|CPU_state|word_t|CONFIG_ISA_riscv32' \
  "$source_dir/src" "$source_dir/include" \
  > "$evidence_dir/difftest-layout-matches.txt" || true

mapfile -t candidates < <(
  find "$source_dir/configs" -maxdepth 1 -type f -printf '%f\n' \
    | grep -E '^riscv32.*ref_defconfig$' \
    | sort
)

if ((${#candidates[@]} == 0)); then
  mapfile -t candidates < <(
    grep -l 'CONFIG_ISA_riscv32=y' "$source_dir"/configs/*ref_defconfig 2>/dev/null \
      | xargs -r -n1 basename \
      | sort -u
  )
fi

printf '%s\n' "${candidates[@]}" | tee "$evidence_dir/candidates.txt"

if ((${#candidates[@]} == 0)); then
  echo "ERROR: pinned NEMU revision exposes no RV32 reference defconfig" \
    | tee "$evidence_dir/result.txt" >&2
  exit 2
fi

selected=""
reference_so=""
for candidate in "${candidates[@]}"; do
  echo "== try NEMU configuration: $candidate ==" \
    | tee -a "$evidence_dir/build-attempts.txt"
  rm -f "$source_dir/.config"
  rm -rf "$source_dir/build"
  if make -C "$source_dir" "$candidate" \
      > "$evidence_dir/${candidate}.config.log" 2>&1 && \
     make -C "$source_dir" -j2 \
      > "$evidence_dir/${candidate}.build.log" 2>&1; then
    reference_so="$(find "$source_dir/build" -maxdepth 1 -type f \
      -name '*riscv32*interpreter-so' -print -quit)"
    if [[ -n "$reference_so" ]]; then
      selected="$candidate"
      break
    fi
  fi
done

if [[ -z "$selected" || -z "$reference_so" ]]; then
  echo "ERROR: no discovered RV32 reference configuration produced a shared object" \
    | tee "$evidence_dir/result.txt" >&2
  exit 3
fi

printf '%s\n' "$selected" | tee "$evidence_dir/selected-config.txt"
cp "$source_dir/.config" "$evidence_dir/generated.config"
sha256sum "$reference_so" | tee "$evidence_dir/reference-so.sha256"
file "$reference_so" | tee "$evidence_dir/reference-so.file.txt"
nm -D "$reference_so" | sort > "$evidence_dir/reference-so.symbols.txt"
readelf -h -S -s "$reference_so" > "$evidence_dir/reference-so.elf.txt"

REFERENCE_SO="$reference_so" EVIDENCE_DIR="$evidence_dir" python3 - <<'PY'
import ctypes
import os
from pathlib import Path

so_path = os.environ["REFERENCE_SO"]
evidence = Path(os.environ["EVIDENCE_DIR"])
lib = ctypes.CDLL(so_path)

set_ram_size = lib.difftest_set_ramsize
set_ram_size.argtypes = [ctypes.c_size_t]
set_ram_size.restype = None

init = lib.difftest_init
init.argtypes = []
init.restype = None

regcpy = lib.difftest_regcpy
regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
regcpy.restype = None

set_ram_size(64 * 1024 * 1024)
init()

size = 4096
sentinel = 0xA5
buffer = (ctypes.c_ubyte * size)(*([sentinel] * size))
regcpy(ctypes.byref(buffer), False)
raw = bytes(buffer)
changed = [index for index, value in enumerate(raw) if value != sentinel]

with (evidence / "regcpy-probe.txt").open("w", encoding="utf-8") as output:
    output.write(f"shared_object={so_path}\n")
    output.write(f"buffer_bytes={size}\n")
    output.write(f"changed_bytes={len(changed)}\n")
    if changed:
        output.write(f"first_changed={changed[0]}\n")
        output.write(f"last_changed={changed[-1]}\n")
        output.write(f"changed_prefix_bytes={changed[-1] + 1}\n")
    else:
        output.write("changed_prefix_bytes=0\n")
    output.write("first_512_bytes=\n")
    for offset in range(0, 512, 16):
        chunk = raw[offset:offset + 16]
        output.write(f"{offset:04x}: {' '.join(f'{byte:02x}' for byte in chunk)}\n")
PY

cat > "$evidence_dir/result.txt" <<EOF
status=PASS
revision=$revision
selected_config=$selected
reference_so=$(basename "$reference_so")
EOF

cat "$evidence_dir/result.txt"
cat "$evidence_dir/regcpy-probe.txt"
