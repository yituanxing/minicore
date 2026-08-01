#!/usr/bin/env bash
set -euo pipefail

revision="${NEMU_REVISION:-ad6bfde6241f2fc1e864b1efb2bed99b3670eb73}"
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

git -C "$source_dir" rev-parse HEAD | tee "$evidence_dir/revision.txt"

find "$source_dir/configs" -maxdepth 1 -type f -printf '%f\n' | sort \
  | tee "$evidence_dir/configs.txt"

grep -RIn --exclude-dir=.git -E 'riscv32|ISA_riscv32|RV32' \
  "$source_dir/configs" "$source_dir/src" "$source_dir/include" \
  > "$evidence_dir/riscv32-source-matches.txt" || true

grep -RIn --exclude-dir=.git -E \
  'difftest_regcpy|DIFFTEST_REG_SIZE|CPU_state|word_t|CONFIG_ISA_riscv32|config SHARE|config SHARE_REF' \
  "$source_dir/Kconfig" "$source_dir/lib-include" \
  "$source_dir/src" "$source_dir/include" \
  > "$evidence_dir/difftest-layout-matches.txt" || true

cp "$source_dir/src/isa/riscv32/include/isa-def.h" \
  "$evidence_dir/riscv32-isa-def.h"
cp "$source_dir/src/isa/riscv32/difftest/ref.c" \
  "$evidence_dir/riscv32-difftest-ref.c"
cp "$source_dir/lib-include/difftest.h" \
  "$evidence_dir/difftest-public-layout.h"
cp "$source_dir/configs/riscv32-pa_defconfig" \
  "$evidence_dir/upstream-riscv32-pa_defconfig"

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

if ((${#candidates[@]} == 0)); then
  echo "No upstream RV32 reference defconfig exists; derive one from riscv32-pa_defconfig."
  SOURCE_CONFIG="$source_dir/configs/riscv32-pa_defconfig" \
  OUTPUT_CONFIG="$source_dir/configs/$derived_config" python3 - <<'PY'
import os
import re
from pathlib import Path

source = Path(os.environ["SOURCE_CONFIG"])
output = Path(os.environ["OUTPUT_CONFIG"])
lines = source.read_text(encoding="utf-8").splitlines()

settings = {
    "CC_GCC": "CONFIG_CC_GCC=y",
    "CC_GPP": "# CONFIG_CC_GPP is not set",
    "CC_CLANG": "# CONFIG_CC_CLANG is not set",
    "CC": 'CONFIG_CC="gcc"',
    "CXX": 'CONFIG_CXX="g++"',
    "CC_O0": "# CONFIG_CC_O0 is not set",
    "CC_O1": "# CONFIG_CC_O1 is not set",
    "CC_O2": "CONFIG_CC_O2=y",
    "CC_O3": "# CONFIG_CC_O3 is not set",
    "CC_OPT": 'CONFIG_CC_OPT="-O2"',
    "CC_LTO": "# CONFIG_CC_LTO is not set",
    "CC_AGGRESSIVE_INLINE": "CONFIG_CC_AGGRESSIVE_INLINE=y",
    "CC_DEBUG": "# CONFIG_CC_DEBUG is not set",
    "SHARE": "CONFIG_SHARE=y",
    "SHARE_REF": "CONFIG_SHARE_REF=y",
    "SHARE_CTRL": "# CONFIG_SHARE_CTRL is not set",
    "QUERY_REF": "CONFIG_QUERY_REF=y",
    "LARGE_COPY": "CONFIG_LARGE_COPY=y",
    "FPU_HOST": "# CONFIG_FPU_HOST is not set",
    "FPU_SOFT": "# CONFIG_FPU_SOFT is not set",
    "FPU_NONE": "CONFIG_FPU_NONE=y",
    "DIFFTEST_CHECK_FCSR": "# CONFIG_DIFFTEST_CHECK_FCSR is not set",
    "DEVICE": "# CONFIG_DEVICE is not set",
    "MEM_RANDOM": "# CONFIG_MEM_RANDOM is not set",
    "MBASE": "CONFIG_MBASE=0x80000000",
    "MSIZE": "CONFIG_MSIZE=0x4000000",
    "PADDRBITS": "CONFIG_PADDRBITS=32",
    "RESET_FROM_MMIO": "# CONFIG_RESET_FROM_MMIO is not set",
    "PC_RESET_OFFSET": "CONFIG_PC_RESET_OFFSET=0x0",
    "DETERMINISTIC": "CONFIG_DETERMINISTIC=y",
    "PERF_OPT": "# CONFIG_PERF_OPT is not set",
}

patterns = {
    name: re.compile(rf"^(?:CONFIG_{re.escape(name)}=.*|# CONFIG_{re.escape(name)} is not set)$")
    for name in settings
}
filtered = [
    line for line in lines
    if not any(pattern.match(line) for pattern in patterns.values())
]
filtered.extend(["", "# AetherCore-derived RV32 reference settings"])
filtered.extend(settings.values())
output.write_text("\n".join(filtered) + "\n", encoding="utf-8")
PY
  cp "$source_dir/configs/$derived_config" \
    "$evidence_dir/derived-riscv32-reference.defconfig"
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
  if make -C "$source_dir" "$candidate" \
      > "$evidence_dir/${candidate}.config.log" 2>&1 && \
     make -C "$source_dir" -j2 \
      > "$evidence_dir/${candidate}.build.log" 2>&1; then
    reference_so="$(find "$source_dir/build" -type f \
      -name '*riscv32*interpreter-so*' -print -quit)"
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
