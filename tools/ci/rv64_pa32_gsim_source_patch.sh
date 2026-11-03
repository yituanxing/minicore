#!/usr/bin/env bash
# Experimental only: patch a pinned GSIM frontend, never the CPU RTL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="${OUT:-build/rv64-pa32-gsim-source-patch}"
REV=45a73d6afb9b1893781cfb57ccd54096987bb23d
GSIM_URL=https://github.com/OpenXiangShan/gsim.git
mkdir -p "$OUT"
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
  "$src/build/gsim/gsim" --dir "$OUT/generated" --log-level=1 "$OUT/pa32.fir" \
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
echo "Only reset and native model stepping verified. Linux/RAM equivalence still pending."
