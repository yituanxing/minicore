#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"
WORK_DIR="${AETHERCORE_RV32_SPIKE_WORK_DIR:-$ROOT/build/rv32-spike-reference-probe}"
SOURCE_DIR="$WORK_DIR/riscv-isa-sim"
BUILD_DIR="$WORK_DIR/build"
EVIDENCE_DIR="$WORK_DIR/evidence"
HOST_TOOLS="$WORK_DIR/host-tools"
REFERENCE_SO="$WORK_DIR/rv32imc-spike-reference.so"

for tool in git make g++ strings; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf 'ERROR: Spike RV32C reference requires host tool: %s\n' "$tool" >&2
    exit 1
  }
done

rm -rf "$WORK_DIR"
mkdir -p "$SOURCE_DIR" "$BUILD_DIR" "$EVIDENCE_DIR" "$HOST_TOOLS"

# Spike v1.1.0's configure script requires a dtc path even when only the
# processor/MMU library is used. The bare reference never builds or executes
# Spike's DTS/DTB platform path. Provide a fail-fast configure sentinel instead
# of mutating pinned Spike source or requiring an unused host package. Any real
# invocation proves this assumption wrong and fails the probe.
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

make -C "$BUILD_DIR" -j2 libriscv.a libsoftfloat.a libfdt.a \
  > "$EVIDENCE_DIR/build.log" 2>&1
[[ ! -e "$AETHERCORE_DTC_SENTINEL" ]] || {
  printf 'ERROR: selected Spike library build unexpectedly executed dtc\n' >&2
  exit 1
}

for archive in libriscv.a libsoftfloat.a libfdt.a; do
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
  "$ROOT/tools/spike_rv32_reference_shim.cpp" \
  -Wl,--build-id=none -Wl,--no-undefined \
  -Wl,--start-group \
    "$BUILD_DIR/libriscv.a" \
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
sha256sum "$REFERENCE_SO" | tee "$EVIDENCE_DIR/reference.sha256" >&2
nm -D --defined-only "$REFERENCE_SO" | sort > "$EVIDENCE_DIR/reference.symbols.txt"

for symbol in difftest_init difftest_memcpy difftest_regcpy difftest_exec new_space add_mmio_map; do
  grep -Eq "[[:space:]]${symbol}$" "$EVIDENCE_DIR/reference.symbols.txt" || {
    printf 'ERROR: Spike reference is missing ABI symbol %s\n' "$symbol" >&2
    exit 1
  }
done

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
revision=$REVISION
isa=RV32IMC_Zicsr
priv=M
ram_base=0x80000000
ram_size=67108864
dtc_runtime_dependency=none
reference_so=$REFERENCE_SO
EOF
cat "$EVIDENCE_DIR/result.txt" >&2
printf '%s\n' "$REFERENCE_SO"
