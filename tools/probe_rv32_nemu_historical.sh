#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
revision="${NEMU_REVISION:-8601834e4889e6bf3b6113eb5f824ba7689126f5}"
work_dir="${1:-build/rv32-nemu-probe}"
source_dir="$work_dir/nemu"
evidence_dir="$work_dir/evidence"
config_name="riscv32-minicore-ref_defconfig"
expected_reg_bytes=132
single_step="${NEMU_SINGLE_STEP:-0}"

if [[ "$single_step" != "0" && "$single_step" != "1" ]]; then
  echo "ERROR: NEMU_SINGLE_STEP must be 0 or 1" >&2
  exit 2
fi

rm -rf "$work_dir"
mkdir -p "$source_dir" "$evidence_dir"

git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/OpenXiangShan/NEMU.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout -q FETCH_HEAD

# NEMU's fixed commit otherwise performs an implicit, unpinned SoftFloat clone
# while Make parses scripts/softfloat.mk. Preseed the expected directory from a
# separately pinned persistent cache so the reference build has no hidden
# source drift and transient proxy failures do not restart the whole Full Gate.
softfloat_revision="$(
  bash "$ROOT/tools/ensure_berkeley_softfloat.sh" \
    "$source_dir/resource/softfloat/repo"
)"
printf '%s\n' "$softfloat_revision" | tee "$evidence_dir/softfloat-revision.txt"

export NEMU_HOME="$(cd "$source_dir" && pwd)"
export CFLAGS="${CFLAGS:-} -Wno-error=format -Wno-error=array-bounds"
export LDFLAGS="${LDFLAGS:-} -lreadline -Wl,--build-id=none"
export SOURCE_DATE_EPOCH="$(git -C "$source_dir" show -s --format=%ct HEAD)"
export LC_ALL=C
export TZ=UTC

git -C "$source_dir" rev-parse HEAD | tee "$evidence_dir/revision.txt"

cat > "$source_dir/configs/$config_name" <<'EOF'
CONFIG_ISA_riscv32=y
CONFIG_CC_GCC=y
CONFIG_CC_O2=y
CONFIG_SHARE=y
CONFIG_TIMER_GETTIMEOFDAY=y
CONFIG_MBASE=0x80000000
CONFIG_MSIZE=0x4000000
CONFIG_PC_RESET_OFFSET=0x0
# CONFIG_CC_LTO is not set
# CONFIG_CC_DEBUG is not set
# CONFIG_CC_ASAN is not set
# CONFIG_DEBUG is not set
# CONFIG_DIFFTEST is not set
# CONFIG_MEM_RANDOM is not set
EOF

# The historical PERF_OPT executor retires and accounts at basic-block
# boundaries. Even with instruction counting enabled, difftest_exec(1) can
# overshoot the requested architectural instruction. The exact DiffTest mode
# selects NEMU's own non-PERF_OPT interpreter loop, whose execute() decrements
# n once per decoded instruction. The default ABI probe keeps its original
# optimized configuration and hash.
if [[ "$single_step" == "1" ]]; then
  cat >> "$source_dir/configs/$config_name" <<'EOF'
# CONFIG_PERF_OPT is not set
EOF
else
  cat >> "$source_dir/configs/$config_name" <<'EOF'
CONFIG_PERF_OPT=y
EOF
fi

cp "$source_dir/configs/$config_name" "$evidence_dir/derived.defconfig"

# In this historical tree the shared-reference implementation calls the MMIO
# map primitives, but SHARE disables the full device subsystem. Include only
# the two official map implementation files required by difftest_init and
# paddr fallback; no device model or ISA source is changed.
SOURCE_MAKEFILE="$source_dir/Makefile" python3 - <<'PY'
import os
from pathlib import Path

path = Path(os.environ["SOURCE_MAKEFILE"])
text = path.read_text(encoding="utf-8")
needle = "SRCS = $(SRCS-y)"
addition = (
    "# AetherCore RV32 reference: self-contained historical shared object\n"
    "SRCS-y += src/device/io/map.c src/device/io/mmio.c\n\n"
    + needle
)
if needle not in text:
    raise SystemExit("historical NEMU Makefile layout changed")
path.write_text(text.replace(needle, addition, 1), encoding="utf-8")
PY

git -C "$source_dir" diff -- Makefile configs/$config_name \
  > "$evidence_dir/build-composition.patch" || true
cp "$source_dir/src/isa/riscv32/include/isa-def.h" "$evidence_dir/isa-def.h"
cp "$source_dir/src/isa/riscv32/difftest/ref.c" "$evidence_dir/isa-difftest-ref.c"
cp "$source_dir/src/cpu/difftest/ref.c" "$evidence_dir/cpu-difftest-ref.c"
cp "$source_dir/lib-include/difftest.h" "$evidence_dir/difftest-layout.h"
cp "$source_dir/src/cpu/cpu-exec.c" "$evidence_dir/cpu-exec.c"

build_reference() {
  local label="$1"
  local reference_so
  local config_log="$evidence_dir/config-$label.log"

  rm -f "$source_dir/.config"
  rm -rf "$source_dir/build"

  # The selected defconfig is a shared DiffTest reference. Before .config
  # exists, this historical top-level Makefile otherwise assumes a standalone
  # device executable and appends -lSDL2/-lreadline/-ldl/-pie. Those target
  # runtime flags then leak into the recursively built Kconfig host tool.
  # Seed only the already-selected CONFIG_SHARE mode for this bootstrap make;
  # the generated .config remains wholly owned by the pinned defconfig below.
  if ! make -C "$source_dir" CONFIG_SHARE=y "$config_name" > "$config_log" 2>&1; then
    echo "ERROR: exact RV32 NEMU config generation failed in $label build" >&2
    cat "$config_log" >&2
    return 6
  fi
  if [[ ! -f "$source_dir/.config" ]]; then
    echo "ERROR: exact RV32 NEMU config target succeeded without producing .config in $label build" >&2
    cat "$config_log" >&2
    return 7
  fi

  if [[ "$single_step" == "1" ]]; then
    if ! grep -q '^CONFIG_ENABLE_INSTR_CNT=y$' "$source_dir/.config"; then
      echo "ERROR: exact reference did not enable instruction counting" >&2
      cp "$source_dir/.config" "$evidence_dir/failed-single-step.config"
      return 4
    fi
    if grep -q '^CONFIG_PERF_OPT=y$' "$source_dir/.config"; then
      echo "ERROR: exact reference still uses the basic-block PERF_OPT executor" >&2
      cp "$source_dir/.config" "$evidence_dir/failed-single-step.config"
      return 5
    fi
  fi

  make -C "$source_dir/tools/fixdep" clean \
    > "$evidence_dir/fixdep-$label.log" 2>&1
  make -C "$source_dir/tools/fixdep" \
    >> "$evidence_dir/fixdep-$label.log" 2>&1
  make -C "$source_dir" -j2 \
    > "$evidence_dir/build-$label.log" 2>&1

  reference_so="$(find "$source_dir/build" -maxdepth 1 -type f \
    -name 'riscv32-nemu-interpreter-so*' -print -quit)"
  if [[ -z "$reference_so" ]]; then
    echo "ERROR: RV32 reference shared object was not produced in $label build" >&2
    return 2
  fi
  printf '%s\n' "$reference_so"
}

first_reference="$(build_reference first)"
cp "$first_reference" "$work_dir/riscv32-reference-first.so"
first_sha="$(sha256sum "$first_reference" | awk '{print $1}')"

reference_so="$(build_reference second)"
second_sha="$(sha256sum "$reference_so" | awk '{print $1}')"

if ! cmp -s "$work_dir/riscv32-reference-first.so" "$reference_so"; then
  cat > "$evidence_dir/reproducibility.txt" <<EOF
reproducible=false
first_sha256=$first_sha
second_sha256=$second_sha
softfloat_revision=$softfloat_revision
single_step=$single_step
EOF
  cat "$evidence_dir/reproducibility.txt" >&2
  exit 3
fi

cat > "$evidence_dir/reproducibility.txt" <<EOF
reproducible=true
first_sha256=$first_sha
second_sha256=$second_sha
source_date_epoch=$SOURCE_DATE_EPOCH
build_id=disabled
softfloat_revision=$softfloat_revision
single_step=$single_step
EOF
cat "$evidence_dir/reproducibility.txt"

sha256sum "$reference_so" | tee "$evidence_dir/reference-so.sha256"
file "$reference_so" | tee "$evidence_dir/reference-so.file.txt"
nm -D "$reference_so" | sort > "$evidence_dir/reference-so.symbols.txt"
readelf -d -h -S -s "$reference_so" > "$evidence_dir/reference-so.elf.txt"
ldd "$reference_so" > "$evidence_dir/reference-so.ldd.txt"
cp "$source_dir/.config" "$evidence_dir/generated.config"

REFERENCE_SO="$reference_so" EVIDENCE_DIR="$evidence_dir" \
EXPECTED_REG_BYTES="$expected_reg_bytes" NEMU_REVISION="$revision" python3 - <<'PY'
import ctypes
import os
from pathlib import Path

so_path = os.environ["REFERENCE_SO"]
evidence = Path(os.environ["EVIDENCE_DIR"])
expected = int(os.environ["EXPECTED_REG_BYTES"])
lib = ctypes.CDLL(so_path, mode=os.RTLD_NOW | os.RTLD_LOCAL)

init = lib.difftest_init
init.argtypes = []
init.restype = None
regcpy = lib.difftest_regcpy
regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
regcpy.restype = None
memcpy_ref = lib.difftest_memcpy
memcpy_ref.argtypes = [ctypes.c_uint32, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_bool]
memcpy_ref.restype = None
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
prefix_matches = raw[:expected] == bytes(source[:expected])
guard_matches = all(value == guard for value in raw[expected:])

pattern = (ctypes.c_ubyte * 16)(*range(16))
roundtrip = (ctypes.c_ubyte * 16)()
memcpy_ref(0x80000000, pattern, 16, True)
memcpy_ref(0x80000000, roundtrip, 16, False)
memory_matches = bytes(pattern) == bytes(roundtrip)

with (evidence / "abi-probe.txt").open("w", encoding="utf-8") as output:
    output.write(f"revision={os.environ['NEMU_REVISION']}\n")
    output.write(f"expected_reg_bytes={expected}\n")
    output.write(f"prefix_matches={str(prefix_matches).lower()}\n")
    output.write(f"guard_matches={str(guard_matches).lower()}\n")
    output.write(f"memory_roundtrip_matches={str(memory_matches).lower()}\n")
    output.write("layout=uint32_t gpr[32]; uint32_t pc\n")
    output.write("first_160_bytes=\n")
    for offset in range(0, 160, 16):
        chunk = raw[offset:offset + 16]
        output.write(f"{offset:04x}: {' '.join(f'{byte:02x}' for byte in chunk)}\n")

if not prefix_matches or not guard_matches or not memory_matches:
    raise SystemExit(
        "RV32 NEMU ABI mismatch: "
        f"prefix={prefix_matches} guard={guard_matches} memory={memory_matches}"
    )
PY

cat > "$evidence_dir/result.txt" <<EOF
status=PASS
revision=$revision
softfloat_revision=$softfloat_revision
config=$config_name
reference_so=$(basename "$reference_so")
reference_sha256=$second_sha
regcpy_bytes=$expected_reg_bytes
reproducible=true
single_step=$single_step
perf_opt=$([[ "$single_step" == "1" ]] && echo false || echo true)
EOF
cat "$evidence_dir/result.txt"
cat "$evidence_dir/abi-probe.txt"
