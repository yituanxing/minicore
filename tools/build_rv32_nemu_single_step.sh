#!/usr/bin/env bash
set -euo pipefail

revision="${NEMU_REVISION:-8601834e4889e6bf3b6113eb5f824ba7689126f5}"
work_dir="${1:-build/rv32-nemu-single-step}"
source_dir="$work_dir/nemu"
evidence_dir="$work_dir/evidence"
config_name="riscv32-minicore-single-step-ref_defconfig"
expected_reg_bytes=132

rm -rf "$work_dir"
mkdir -p "$source_dir" "$evidence_dir"

git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/OpenXiangShan/NEMU.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout -q FETCH_HEAD
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
# CONFIG_PERF_OPT is not set
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
cp "$source_dir/configs/$config_name" "$evidence_dir/derived.defconfig"

# Historical SHARE excludes the full device subsystem although the reference
# initialization and physical-address fallback use the official MMIO map API.
# Include only those unchanged upstream implementations; ISA and CPU semantics
# remain untouched.
SOURCE_MAKEFILE="$source_dir/Makefile" python3 - <<'PY'
import os
from pathlib import Path

path = Path(os.environ["SOURCE_MAKEFILE"])
text = path.read_text(encoding="utf-8")
needle = "SRCS = $(SRCS-y)"
addition = (
    "# AetherCore RV32 single-step shared reference\n"
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

  rm -f "$source_dir/.config"
  rm -rf "$source_dir/build"

  make -C "$source_dir" "$config_name" \
    > "$evidence_dir/config-$label.log" 2>&1
  make -C "$source_dir/tools/fixdep" clean \
    > "$evidence_dir/fixdep-$label.log" 2>&1
  make -C "$source_dir/tools/fixdep" \
    >> "$evidence_dir/fixdep-$label.log" 2>&1
  make -C "$source_dir" -j2 \
    > "$evidence_dir/build-$label.log" 2>&1

  reference_so="$(find "$source_dir/build" -maxdepth 1 -type f \
    -name 'riscv32-nemu-interpreter-so*' -print -quit)"
  if [[ -z "$reference_so" ]]; then
    echo "ERROR: RV32 single-step reference was not produced in $label build" >&2
    return 2
  fi
  printf '%s\n' "$reference_so"
}

first_reference="$(build_reference first)"
cp "$first_reference" "$work_dir/riscv32-single-step-first.so"
first_sha="$(sha256sum "$first_reference" | awk '{print $1}')"

reference_so="$(build_reference second)"
second_sha="$(sha256sum "$reference_so" | awk '{print $1}')"

if ! cmp -s "$work_dir/riscv32-single-step-first.so" "$reference_so"; then
  cat > "$evidence_dir/reproducibility.txt" <<EOF
reproducible=false
first_sha256=$first_sha
second_sha256=$second_sha
EOF
  cat "$evidence_dir/reproducibility.txt" >&2
  exit 3
fi

cat > "$evidence_dir/reproducibility.txt" <<EOF
reproducible=true
mode=single-step
first_sha256=$first_sha
second_sha256=$second_sha
source_date_epoch=$SOURCE_DATE_EPOCH
build_id=disabled
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
exec_one = lib.difftest_exec
exec_one.argtypes = [ctypes.c_uint64]
exec_one.restype = None
memcpy_ref = lib.difftest_memcpy
memcpy_ref.argtypes = [ctypes.c_uint32, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_bool]
memcpy_ref.restype = None
init()

# Validate the exact 132-byte state footprint in both directions.
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

# Prove that difftest_exec(1) means one architectural instruction. The image
# contains `auipc sp, 0x100`, encoded as 0x00100117 at 0x80000000.
# Expected state: x2 = 0x80100000, x1 remains zero, pc = 0x80000004.
instruction = (ctypes.c_ubyte * 4)(0x17, 0x01, 0x10, 0x00)
memcpy_ref(0x80000000, instruction, 4, True)
state = (ctypes.c_uint32 * 33)()
state[32] = 0x80000000
regcpy(ctypes.byref(state), True)
exec_one(1)
after = (ctypes.c_uint32 * 33)()
regcpy(ctypes.byref(after), False)
single_step_matches = (
    after[1] == 0 and
    after[2] == 0x80100000 and
    after[32] == 0x80000004
)
other_gprs_zero = all(after[index] == 0 for index in range(32) if index != 2)

# Prove memory-copy compatibility at the AetherCore reset base.
pattern = (ctypes.c_ubyte * 16)(*range(16))
roundtrip = (ctypes.c_ubyte * 16)()
memcpy_ref(0x80000020, pattern, 16, True)
memcpy_ref(0x80000020, roundtrip, 16, False)
memory_matches = bytes(pattern) == bytes(roundtrip)

with (evidence / "single-step-probe.txt").open("w", encoding="utf-8") as output:
    output.write(f"revision={os.environ['NEMU_REVISION']}\n")
    output.write(f"expected_reg_bytes={expected}\n")
    output.write(f"prefix_matches={str(prefix_matches).lower()}\n")
    output.write(f"guard_matches={str(guard_matches).lower()}\n")
    output.write(f"single_step_matches={str(single_step_matches).lower()}\n")
    output.write(f"other_gprs_zero={str(other_gprs_zero).lower()}\n")
    output.write(f"after_x1=0x{after[1]:08x}\n")
    output.write(f"after_x2=0x{after[2]:08x}\n")
    output.write(f"after_pc=0x{after[32]:08x}\n")
    output.write(f"memory_roundtrip_matches={str(memory_matches).lower()}\n")
    output.write("layout=uint32_t gpr[32]; uint32_t pc\n")

if not all((prefix_matches, guard_matches, single_step_matches,
            other_gprs_zero, memory_matches)):
    raise SystemExit(
        "RV32 single-step reference validation failed: "
        f"prefix={prefix_matches} guard={guard_matches} "
        f"step={single_step_matches} other_gprs={other_gprs_zero} "
        f"memory={memory_matches}"
    )
PY

cat > "$evidence_dir/result.txt" <<EOF
status=PASS
revision=$revision
config=$config_name
mode=single-step
reference_so=$(basename "$reference_so")
reference_sha256=$second_sha
regcpy_bytes=$expected_reg_bytes
reproducible=true
EOF
cat "$evidence_dir/result.txt"
cat "$evidence_dir/single-step-probe.txt"
