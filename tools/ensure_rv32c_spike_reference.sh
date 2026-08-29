#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"
EXPECTED_SHA256="22dd74006570af19495c6b5449eec908d246c0c6d700f7eabda16001a0ca62df"
WORK_DIR="${AETHERCORE_RV32_SPIKE_WORK_DIR:-$ROOT/build/rv32c-spike-reference}"
SOURCE_DIR="$WORK_DIR/riscv-isa-sim"
BUILD_DIR="$WORK_DIR/build"
EVIDENCE_DIR="$WORK_DIR/evidence"
HOST_TOOLS="$WORK_DIR/host-tools"
REFERENCE_SO="$WORK_DIR/rv32imc-spike-reference.so"

for tool in git make g++ strings file sha256sum nm python3; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf 'ERROR: RV32C Spike reference requires host tool: %s\n' "$tool" >&2
    exit 1
  }
done

# This is the reproducibility builder, not the consumer cache resolver. Every
# invocation starts from an empty work directory by design.
rm -rf "$WORK_DIR"
mkdir -p "$SOURCE_DIR" "$BUILD_DIR" "$EVIDENCE_DIR" "$HOST_TOOLS"

cat > "$HOST_TOOLS/dtc" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'ERROR: configure-only dtc sentinel was invoked\n' >&2
: "${AETHERCORE_DTC_SENTINEL:?}"
touch "$AETHERCORE_DTC_SENTINEL"
exit 99
EOF
chmod +x "$HOST_TOOLS/dtc"
export AETHERCORE_DTC_SENTINEL="$EVIDENCE_DIR/dtc-invoked.txt"
export PATH="$HOST_TOOLS:$PATH"

git -C "$SOURCE_DIR" init -q
git -C "$SOURCE_DIR" remote add origin https://github.com/riscv-software-src/riscv-isa-sim.git
git -C "$SOURCE_DIR" fetch --depth=1 origin "$REVISION" >&2
git -C "$SOURCE_DIR" checkout -q FETCH_HEAD
actual_revision="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
[[ "$actual_revision" == "$REVISION" ]] || {
  printf 'ERROR: Spike revision drift: expected=%s actual=%s\n' "$REVISION" "$actual_revision" >&2
  exit 1
}
printf '%s\n' "$actual_revision" > "$EVIDENCE_DIR/revision.txt"

git -C "$SOURCE_DIR" status --porcelain=v1 > "$EVIDENCE_DIR/source-status.txt"
[[ ! -s "$EVIDENCE_DIR/source-status.txt" ]] || {
  printf 'ERROR: pinned Spike source is unexpectedly dirty\n' >&2
  cat "$EVIDENCE_DIR/source-status.txt" >&2
  exit 1
}

export LC_ALL=C
export TZ=UTC
export SOURCE_DATE_EPOCH="$(git -C "$SOURCE_DIR" show -s --format=%ct HEAD)"
map_flags="-ffile-prefix-map=$SOURCE_DIR=/aethercore/spike-src -fdebug-prefix-map=$SOURCE_DIR=/aethercore/spike-src -ffile-prefix-map=$BUILD_DIR=/aethercore/spike-build -fdebug-prefix-map=$BUILD_DIR=/aethercore/spike-build"

(
  cd "$BUILD_DIR"
  CFLAGS="-O2 -g0 -fPIC $map_flags" \
  CXXFLAGS="-O2 -g0 -fPIC $map_flags" \
    "$SOURCE_DIR/configure" --prefix="$WORK_DIR/install" \
      > "$EVIDENCE_DIR/configure.log" 2>&1
)
[[ ! -e "$AETHERCORE_DTC_SENTINEL" ]] || {
  printf 'ERROR: Spike configure unexpectedly executed dtc\n' >&2
  exit 1
}

make -C "$BUILD_DIR" -j2 libriscv.a libsoftfloat.a libfdt.a libdisasm.a \
  > "$EVIDENCE_DIR/build.log" 2>&1
[[ ! -e "$AETHERCORE_DTC_SENTINEL" ]] || {
  printf 'ERROR: selected Spike library build unexpectedly executed dtc\n' >&2
  exit 1
}

for archive in libriscv.a libsoftfloat.a libfdt.a libdisasm.a; do
  [[ -f "$BUILD_DIR/$archive" ]] || {
    printf 'ERROR: Spike build did not produce %s\n' "$archive" >&2
    tail -n 80 "$EVIDENCE_DIR/build.log" >&2 || true
    exit 1
  }
done

g++ -std=c++17 -O2 -g0 -fPIC -shared \
  "$map_flags" \
  -I"$BUILD_DIR" \
  -I"$SOURCE_DIR/riscv" \
  -I"$SOURCE_DIR/softfloat" \
  -I"$SOURCE_DIR/fdt" \
  -I"$SOURCE_DIR/disasm" \
  "$ROOT/tools/spike_rv32_reference_shim.cpp" \
  -Wl,--build-id=none -Wl,--no-undefined \
  -Wl,--start-group \
    "$BUILD_DIR/libriscv.a" \
    "$BUILD_DIR/libdisasm.a" \
    "$BUILD_DIR/libsoftfloat.a" \
    "$BUILD_DIR/libfdt.a" \
  -Wl,--end-group \
  -lpthread -ldl \
  -o "$REFERENCE_SO" \
  > "$EVIDENCE_DIR/link.log" 2>&1 || {
    cat "$EVIDENCE_DIR/link.log" >&2
    exit 1
  }

[[ ! -e "$AETHERCORE_DTC_SENTINEL" ]] || {
  printf 'ERROR: Spike reference link unexpectedly executed dtc\n' >&2
  exit 1
}
if strings "$REFERENCE_SO" | grep -Fq "$HOST_TOOLS/dtc"; then
  printf 'ERROR: unused dtc platform path leaked into the bare reference shared object\n' >&2
  exit 1
fi

file "$REFERENCE_SO" | tee "$EVIDENCE_DIR/reference.file.txt" >&2
actual_sha="$(sha256sum "$REFERENCE_SO" | awk '{print $1}')"
printf '%s  %s\n' "$actual_sha" "$REFERENCE_SO" | tee "$EVIDENCE_DIR/reference.sha256" >&2
[[ "$actual_sha" == "$EXPECTED_SHA256" ]] || {
  printf 'ERROR: Spike reference SHA drift: expected=%s actual=%s\n' "$EXPECTED_SHA256" "$actual_sha" >&2
  exit 1
}

nm -D --defined-only "$REFERENCE_SO" | sort > "$EVIDENCE_DIR/reference.symbols.txt"
for symbol in difftest_init difftest_memcpy difftest_regcpy difftest_exec new_space add_mmio_map; do
  grep -Eq "[[:space:]]${symbol}$" "$EVIDENCE_DIR/reference.symbols.txt" || {
    printf 'ERROR: Spike reference is missing ABI symbol %s\n' "$symbol" >&2
    exit 1
  }
done

# Standalone semantic proof for raw 0x1141 = C.ADDI16SP -16.
python3 - "$REFERENCE_SO" > "$EVIDENCE_DIR/semantic-smoke.txt" <<'PY'
import ctypes
import sys

so = sys.argv[1]
lib = ctypes.CDLL(so)
lib.difftest_init.argtypes = []
lib.difftest_init.restype = None
lib.difftest_memcpy.argtypes = [ctypes.c_uint32, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_bool]
lib.difftest_memcpy.restype = None
lib.difftest_regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
lib.difftest_regcpy.restype = None
lib.difftest_exec.argtypes = [ctypes.c_uint64]
lib.difftest_exec.restype = None

State = ctypes.c_uint32 * 33
image = (ctypes.c_uint8 * 2)(0x41, 0x11)
initial = State()
initial[2] = 0x80100000
initial[32] = 0x80000000

lib.difftest_init()
lib.difftest_memcpy(0x80000000, ctypes.cast(image, ctypes.c_void_p), 2, True)
lib.difftest_regcpy(ctypes.byref(initial), True)
lib.difftest_exec(1)

result = State()
lib.difftest_regcpy(ctypes.byref(result), False)
assert result[32] == 0x80000002, hex(result[32])
assert result[2] == 0x800FFFF0, hex(result[2])
print('semantic_smoke=PASS')
print('raw=0x1141')
print('pc_before=0x80000000')
print('pc_after=0x80000002')
print('sp_before=0x80100000')
print('sp_after=0x800ffff0')
PY
cat "$EVIDENCE_DIR/semantic-smoke.txt" >&2

python3 "$ROOT/tools/probe_rv32_reference_abi.py" "$REFERENCE_SO" \
  > "$EVIDENCE_DIR/abi-smoke.txt"
cat "$EVIDENCE_DIR/abi-smoke.txt" >&2

grep -q '^status=PASS$' "$EVIDENCE_DIR/abi-smoke.txt"
grep -q '^mixed_provenance_bne=fallthrough$' "$EVIDENCE_DIR/abi-smoke.txt"
grep -q '^passive_mmio_maps=2$' "$EVIDENCE_DIR/abi-smoke.txt"
grep -q '^passive_mmio_independent=PASS$' "$EVIDENCE_DIR/abi-smoke.txt"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
revision=$REVISION
sha256=$actual_sha
isa=RV32IMC_Zicsr
priv=M
ram_base=0x80000000
ram_size=67108864
dtc_runtime_dependency=none
semantic_smoke=PASS
abi_smoke=PASS
reference_so=$REFERENCE_SO
EOF
cat "$EVIDENCE_DIR/result.txt" >&2
printf '%s\n' "$REFERENCE_SO"
