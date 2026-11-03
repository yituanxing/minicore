#!/usr/bin/env bash
# Experimental only: patch a pinned GSIM frontend, never the CPU RTL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="${OUT:-build/rv64-pa32-gsim-source-patch}"
REV=45a73d6afb9b1893781cfb57ccd54096987bb23d
GSIM_URL=https://github.com/OpenXiangShan/gsim.git
GSIM_SUPERNODE_MAX_SIZE="${GSIM_SUPERNODE_MAX_SIZE:-35}"
if ! [[ "$GSIM_SUPERNODE_MAX_SIZE" =~ ^[1-9][0-9]*$ ]] ||
   (( GSIM_SUPERNODE_MAX_SIZE > 128 )); then
  echo "invalid GSIM_SUPERNODE_MAX_SIZE=$GSIM_SUPERNODE_MAX_SIZE (1..128)" >&2
  exit 2
fi
mkdir -p "$OUT"
printf "gsim_revision=%s\nsupernode_max_size=%s\n" "$REV" "$GSIM_SUPERNODE_MAX_SIZE" > "$OUT/gsim-configuration.txt"
test -f ./mill || { echo "missing Mill launcher" >&2; exit 2; }
chmod +x ./mill
./mill aethercore.runMain aethercore.ElaborateV2VirtualFpgaBoardPA32CHIRRTL \
  "$ROOT/$OUT/pa32.fir" > "$OUT/chirrtl-export.log" 2>&1 || {
    tail -100 "$OUT/chirrtl-export.log" >&2; exit 3;
  }
test -s "$OUT/pa32.fir"
sha256sum "$OUT/pa32.fir" > "$OUT/chirrtl.sha256"
src="$OUT/upstream"
git -C "$OUT" init -q upstream
git -C "$src" remote add origin "$GSIM_URL"
git -C "$src" fetch --quiet --depth=1 origin "$REV"
git -C "$src" checkout -q --detach FETCH_HEAD
test "$(git -C "$src" rev-parse HEAD)" = "$REV"
python3 tools/ci/rv64_pa32_gsim_cast_patch.py "$src/src/splitNodes.cpp"
if [[ "${GSIM_HOST_BRIDGE_EXPERIMENT:-0}" == "1" ]]; then
  python3 tools/ci/rv64_pa32_gsim_host_bridge_patch.py "$src/src/cppEmitter.cpp"
  git -C "$src" diff -- src/cppEmitter.cpp > "$OUT/gsim-host-bridge.patch"
  test -s "$OUT/gsim-host-bridge.patch"
  sha256sum "$OUT/gsim-host-bridge.patch" > "$OUT/gsim-host-bridge.sha256"
fi
git -C "$src" diff -- src/splitNodes.cpp > "$OUT/gsim-scoped.patch"
test -s "$OUT/gsim-scoped.patch"
sha256sum "$OUT/gsim-scoped.patch" > "$OUT/gsim-patch.sha256"
clang++-19 --version | head -1 > "$OUT/compiler-version.txt"
make -C "$src" CXX=clang++-19 -j "${JOBS:-4}" build-gsim \
  > "$OUT/gsim-build.log" 2>&1 || {
    tail -100 "$OUT/gsim-build.log" >&2; exit 4;
  }
test -x "$src/build/gsim/gsim"
mkdir -p "$OUT/generated"
set +e
timeout --signal=TERM --kill-after=10s 900s \
  "$src/build/gsim/gsim" --supernode-max-size="$GSIM_SUPERNODE_MAX_SIZE" --dir "$OUT/generated" --log-level=1 "$OUT/pa32.fir" \
  > "$OUT/gsim-compile.log" 2>&1
rc=$?
set -e
echo "gsim-source-patch-exit=$rc" | tee "$OUT/result.txt"
if ((rc != 0)); then
  tail -100 "$OUT/gsim-compile.log" >&2
  echo "AETHERCORE_PA32_GSIM_PATCHED_FRONTEND_FAIL rc=$rc" >&2
  exit "$rc"
fi
find "$OUT/generated" -type f -name '*.cpp' -size +0c | sort > "$OUT/cpp-files.txt"
test -s "$OUT/cpp-files.txt"
echo "AETHERCORE_PA32_GSIM_PATCHED_FRONTEND_PASS"

# GSIM emits one public S<ChiselTop> class with set_ and get_ methods.
# Capture the exact names, since these differ from Verilator's flattened
# member names and must be mapped explicitly in the host-memory adapter.
header="$OUT/generated/AetherCoreV2OpenSbiRV64SimTop.h"
test -s "$header" || { echo "missing generated GSIM top header" >&2; exit 21; }
python3 tools/ci/rv64_pa32_gsim_ports.py "$header" "$OUT/ports.tsv"
sha256sum "$header" > "$OUT/gsim-header.sha256"

# Compile actual generated native model, not just a C++ syntax fragment.
# -O0 is intentional: this tests correctness of compilation and execution,
# not yet performance, and avoids wasting CI on optimization before wiring.
timeout --signal=TERM --kill-after=10s 1200s \
  clang++-19 -std=gnu++20 -O0 -pthread -lz -I"$OUT/generated" \
  "$OUT/generated/"*.cpp "$ROOT/sim/pa32_gsim_native_smoke.cpp" \
  -o "$OUT/pa32-gsim-smoke" > "$OUT/native-build.log" 2>&1 || {
    tail -100 "$OUT/native-build.log" >&2
    echo "AETHERCORE_PA32_GSIM_NATIVE_BUILD_FAIL" >&2
    exit 22
  }
timeout --signal=TERM --kill-after=5s 120s \
  "$OUT/pa32-gsim-smoke" > "$OUT/native-smoke.log" 2>&1 || {
    tail -100 "$OUT/native-smoke.log" >&2
    echo "AETHERCORE_PA32_GSIM_NATIVE_SMOKE_FAIL" >&2
    exit 23
  }
grep -q '^AETHERCORE_PA32_GSIM_NATIVE_SMOKE_PASS ' "$OUT/native-smoke.log"
cat "$OUT/native-smoke.log"

# Engine-only A/B deliberately bypasses the still-unqualified host-RAM path.
# The main GSIM transport job never sets this flag and retains strict RV64I PASS.
if [[ "${GSIM_SKIP_BARE:-0}" == "1" ]]; then
  echo "AETHERCORE_PA32_GSIM_ENGINE_ONLY no_guest_linux_qualification=1"
  exit 0
fi

bridge_compile_flags=()
GSIM_PREFIX_OPT="${GSIM_PREFIX_OPT:--O0}"
if [[ "${GSIM_HOST_BRIDGE_EXPERIMENT:-0}" == "1" ]]; then
  bridge_compile_flags=(-DAETHERCORE_GSIM_HOST_BRIDGE)
fi

# Exercise the actual RV64I fetch/retire path with the exact shared host RAM.
# This is an unqualified prefix probe, not a replacement for Verilator.
timeout --signal=TERM --kill-after=10s 1200s \
  clang++-19 -std=gnu++20 "$GSIM_PREFIX_OPT" -march=native -pthread -lz \
  "${bridge_compile_flags[@]}" \
  -I"$OUT/generated" -I"$ROOT/sim" \
  "$OUT/generated/"*.cpp "$ROOT/sim/pa32_gsim_prefix_main.cpp" \
  -o "$OUT/pa32-gsim-prefix" > "$OUT/prefix-build.log" 2>&1 || {
    tail -100 "$OUT/prefix-build.log" >&2
    echo "AETHERCORE_PA32_GSIM_PREFIX_BUILD_FAIL" >&2
    exit 24
  }
timeout --signal=TERM --kill-after=10s 120s \
  "$OUT/pa32-gsim-prefix" --bare 20000 \
  > "$OUT/prefix-bare.log" 2>&1 || {
    tail -100 "$OUT/prefix-bare.log" >&2
    echo "AETHERCORE_PA32_GSIM_BARE_RUNTIME_FAIL" >&2
    exit 25
  }
grep -q '^AETHERCORE_PA32_GSIM_BARE_PASS$' "$OUT/prefix-bare.log"
cat "$OUT/prefix-bare.log"
echo 'GSIM RV64I qualification requires correct real instruction retirement and Verilator differential.'
