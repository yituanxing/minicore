#!/usr/bin/env bash
set -euo pipefail

revision="${NEMU_REVISION:-4cac5438bec9cabd98c9ab1dacfc8bb4e1ee2601}"
work_dir="${1:-build/rv32-nemu-probe}"
source_dir="$work_dir/nemu"
evidence_dir="$work_dir/evidence"
derived_config="riscv32-minicore-ref_defconfig"
expected_reg_bytes=132

rm -rf "$work_dir"
mkdir -p "$source_dir" "$evidence_dir"

git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/OpenXiangShan/NEMU.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout -q FETCH_HEAD
export NEMU_HOME="$(cd "$source_dir" && pwd)"

git -C "$source_dir" rev-parse HEAD | tee "$evidence_dir/revision.txt"
find "$source_dir/configs" -maxdepth 1 -type f -printf '%f\n' | sort \
  | tee "$evidence_dir/configs.txt"

grep -RIn --exclude-dir=.git -E \
  'riscv32|ISA_riscv32|RV32|difftest_regcpy|DIFFTEST_REG_SIZE|CPU_state|word_t|config SHARE|config PERF_OPT' \
  "$source_dir/Kconfig" "$source_dir/lib-include" "$source_dir/src" \
  "$source_dir/include" > "$evidence_dir/source-matches.txt" || true

for path in \
  src/isa/riscv32/include/isa-def.h \
  src/isa/riscv32/difftest/ref.c \
  src/cpu/difftest/ref.c \
  lib-include/difftest.h \
  configs/riscv32-pa_defconfig; do
  if [[ -f "$source_dir/$path" ]]; then
    cp "$source_dir/$path" "$evidence_dir/$(echo "$path" | tr '/' '-')"
  fi
done

mapfile -t candidates < <(
  find "$source_dir/configs" -maxdepth 1 -type f -printf '%f\n' \
    | grep -E '^riscv32.*ref_defconfig$' | sort
)

if ((${#candidates[@]} == 0)); then
  echo "No upstream RV32 reference defconfig exists; derive one using only symbols present in this revision."
  SOURCE_ROOT="$source_dir" \
  SOURCE_CONFIG="$source_dir/configs/riscv32-pa_defconfig" \
  OUTPUT_CONFIG="$source_dir/configs/$derived_config" python3 - <<'PY'
import os
import re
from pathlib import Path

root = Path(os.environ["SOURCE_ROOT"])
source = Path(os.environ["SOURCE_CONFIG"])
output = Path(os.environ["OUTPUT_CONFIG"])

symbols = set()
for kconfig in root.rglob("Kconfig"):
    text = kconfig.read_text(encoding="utf-8", errors="replace")
    symbols.update(re.findall(r"^(?:menu)?config\s+([A-Za-z0-9_]+)", text, re.M))

requested = {
    "ISA_riscv32": "CONFIG_ISA_riscv32=y",
    "ISA_riscv64": "# CONFIG_ISA_riscv64 is not set",
    "CC_GCC": "CONFIG_CC_GCC=y",
    "CC_CLANG": "# CONFIG_CC_CLANG is not set",
    "CC_O0": "# CONFIG_CC_O0 is not set",
    "CC_O1": "# CONFIG_CC_O1 is not set",
    "CC_O2": "CONFIG_CC_O2=y",
    "CC_O3": "# CONFIG_CC_O3 is not set",
    "CC_LTO": "# CONFIG_CC_LTO is not set",
    "CC_DEBUG": "# CONFIG_CC_DEBUG is not set",
    "CC_ASAN": "# CONFIG_CC_ASAN is not set",
    "SHARE": "CONFIG_SHARE=y",
    "SHARE_REF": "CONFIG_SHARE_REF=y",
    "SHARE_CTRL": "# CONFIG_SHARE_CTRL is not set",
    "DEBUG": "# CONFIG_DEBUG is not set",
    "DIFFTEST": "# CONFIG_DIFFTEST is not set",
    "QUERY_REF": "# CONFIG_QUERY_REF is not set",
    "LARGE_COPY": "# CONFIG_LARGE_COPY is not set",
    "DEVICE": "# CONFIG_DEVICE is not set",
    "MEM_RANDOM": "# CONFIG_MEM_RANDOM is not set",
    "PERF_OPT": "CONFIG_PERF_OPT=y",
    "DISABLE_INSTR_CNT": "# CONFIG_DISABLE_INSTR_CNT is not set",
    "TIMER_GETTIMEOFDAY": "CONFIG_TIMER_GETTIMEOFDAY=y",
    "TIMER_CLOCK_GETTIME": "# CONFIG_TIMER_CLOCK_GETTIME is not set",
    "MBASE": "CONFIG_MBASE=0x80000000",
    "MSIZE": "CONFIG_MSIZE=0x4000000",
    "PADDRBITS": "CONFIG_PADDRBITS=32",
    "PC_RESET_OFFSET": "CONFIG_PC_RESET_OFFSET=0x0",
}
requested = {name: line for name, line in requested.items() if name in symbols}
patterns = {
    name: re.compile(rf"^(?:CONFIG_{re.escape(name)}=.*|# CONFIG_{re.escape(name)} is not set)$")
    for name in requested
}
base_lines = source.read_text(encoding="utf-8").splitlines()
filtered = [
    line for line in base_lines
    if not any(pattern.match(line) for pattern in patterns.values())
]
filtered.extend(["", "# AetherCore-derived RV32 shared-reference settings"])
filtered.extend(requested.values())
output.write_text("\n".join(filtered) + "\n", encoding="utf-8")
(root / "available-kconfig-symbols.txt").write_text(
    "\n".join(sorted(symbols)) + "\n", encoding="utf-8"
)
PY
  cp "$source_dir/configs/$derived_config" \
    "$evidence_dir/derived-riscv32-reference.defconfig"
  cp "$source_dir/available-kconfig-symbols.txt" \
    "$evidence_dir/available-kconfig-symbols.txt"
  candidates=("$derived_config")
fi

printf '%s\n' "${candidates[@]}" | tee "$evidence_dir/candidates.txt"

selected=""
reference_so=""
for candidate in "${candidates[@]}"; do
  echo "== try NEMU configuration: $candidate ==" \
    | tee -a "$evidence_dir/build-attempts.txt"
  rm -f "$source_dir/.config"
  rm -rf "$source_dir/build"

  if ! make -C "$source_dir" "$candidate" \
      > "$evidence_dir/${candidate}.config.log" 2>&1; then
    continue
  fi

  if [[ -d "$source_dir/tools/fixdep" ]]; then
    make -C "$source_dir/tools/fixdep" \
      > "$evidence_dir/${candidate}.fixdep.log" 2>&1
  fi

  if make -C "$source_dir" -j2 \
      > "$evidence_dir/${candidate}.build.log" 2>&1; then
    reference_so="$(find "$source_dir/build" -type f \
      \( -name '*riscv32*interpreter-so*' -o -name 'riscv32-nemu-interpreter-so*' \) \
      -print -quit)"
    if [[ -n "$reference_so" ]]; then
      selected="$candidate"
      break
    fi
  fi
done

if [[ -z "$selected" || -z "$reference_so" ]]; then
  echo "ERROR: no RV32 reference configuration produced a shared object" \
    | tee "$evidence_dir/result.txt" >&2
  exit 3
fi

printf '%s\n' "$selected" | tee "$evidence_dir/selected-config.txt"
cp "$source_dir/.config" "$evidence_dir/generated.config"
sha256sum "$reference_so" | tee "$evidence_dir/reference-so.sha256"
file "$reference_so" | tee "$evidence_dir/reference-so.file.txt"
nm -D "$reference_so" | sort > "$evidence_dir/reference-so.symbols.txt"
readelf -h -S -s "$reference_so" > "$evidence_dir/reference-so.elf.txt"

REFERENCE_SO="$reference_so" EVIDENCE_DIR="$evidence_dir" \
EXPECTED_REG_BYTES="$expected_reg_bytes" python3 - <<'PY'
import ctypes
import os
from pathlib import Path

so_path = os.environ["REFERENCE_SO"]
evidence = Path(os.environ["EVIDENCE_DIR"])
expected = int(os.environ["EXPECTED_REG_BYTES"])
lib = ctypes.CDLL(so_path, mode=os.RTLD_NOW | os.RTLD_LOCAL)

set_ram_size = getattr(lib, "difftest_set_ramsize", None)
if set_ram_size is not None:
    set_ram_size.argtypes = [ctypes.c_size_t]
    set_ram_size.restype = None
    set_ram_size(64 * 1024 * 1024)

init = lib.difftest_init
init.argtypes = []
init.restype = None
regcpy = lib.difftest_regcpy
regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
regcpy.restype = None
init()

size = 4096
guard = 0xA5
source = (ctypes.c_ubyte * size)(*([guard] * size))
for index in range(expected):
    source[index] = (index * 37 + 11) & 0xFF
regcpy(ctypes.byref(source), True)

destination = (ctypes.c_ubyte * size)(*([guard] * size))
regcpy(ctypes.byref(destination), False)
raw = bytes(destination)
expected_bytes = bytes(source[:expected])
prefix_matches = raw[:expected] == expected_bytes
guard_matches = all(value == guard for value in raw[expected:])
changed = [index for index, value in enumerate(raw) if value != guard]

with (evidence / "regcpy-probe.txt").open("w", encoding="utf-8") as output:
    output.write(f"shared_object={so_path}\n")
    output.write(f"buffer_bytes={size}\n")
    output.write(f"expected_reg_bytes={expected}\n")
    output.write(f"prefix_matches={str(prefix_matches).lower()}\n")
    output.write(f"guard_matches={str(guard_matches).lower()}\n")
    output.write(f"changed_bytes={len(changed)}\n")
    output.write(f"first_changed={changed[0] if changed else -1}\n")
    output.write(f"last_changed={changed[-1] if changed else -1}\n")
    output.write("first_160_bytes=\n")
    for offset in range(0, 160, 16):
        chunk = raw[offset:offset + 16]
        output.write(f"{offset:04x}: {' '.join(f'{byte:02x}' for byte in chunk)}\n")

if not prefix_matches or not guard_matches:
    raise SystemExit(
        f"regcpy ABI mismatch: expected exactly {expected} bytes; "
        f"prefix_matches={prefix_matches} guard_matches={guard_matches}"
    )
PY

cat > "$evidence_dir/result.txt" <<EOF
status=PASS
revision=$revision
selected_config=$selected
reference_so=$(basename "$reference_so")
regcpy_bytes=$expected_reg_bytes
EOF

cat "$evidence_dir/result.txt"
cat "$evidence_dir/regcpy-probe.txt"
