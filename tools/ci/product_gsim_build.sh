#!/usr/bin/env bash
set -euo pipefail

# Product OpenSBI GSIM: reuse PA32 pinned GSIM generator + same-cycle RAM bridge.
# Builds ONE generated Product model, shared by G3 and any later Linux prefix.
# It never downloads/builds Debian or runs a guest by itself.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
REV=45a73d6afb9b1893781cfb57ccd54096987bb23d
GSIM_URL=https://github.com/OpenXiangShan/gsim.git
OUT="${PRODUCT_GSIM_OUT:-$ROOT/build/product-gsim-bridge}"
SRC="${PRODUCT_GSIM_SOURCE_CACHE:-$HOME/.cache/aethercore/gsim/product-$REV}"
FIR="$OUT/product.fir"
GENERATED="$OUT/generated"
BIN="$OUT/product-gsim-prefix"
SIZE="${GSIM_SUPERNODE_MAX_SIZE:-35}"
OPT="${GSIM_PREFIX_OPT:--O3}"
JOBS="${JOBS:-4}"
[[ "$SIZE" =~ ^[1-9][0-9]*$ ]] && (( SIZE <= 128 )) || { echo 'GSIM supernode size must be 1..128' >&2; exit 2; }
case "$OPT" in -O0|-O1|-O2|-O3) ;; *) echo 'Unsupported GSIM native optimization' >&2; exit 2 ;; esac
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] && (( JOBS <= 16 )) || exit 2
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'Product G3 GSIM refuses Debian SDHC/DTB media' >&2; exit 2;
}
command -v clang++-19 >/dev/null || { echo 'clang++-19 required' >&2; exit 2; }
[[ -f "$ROOT/sim/product_gsim_prefix_main.cpp" && -f "$ROOT/sim/product_gsim_port_adapter.h" ]] || {
  echo 'Product GSIM adapter/host pending; cannot claim a successful model build' >&2; exit 2;
}
[[ -f "$ROOT/mill" ]] || exit 2
mkdir -p "$OUT" "$(dirname "$SRC")"
chmod +x "$ROOT/mill"

# Chisel/Product firrtl is itself the model identity. The GSIM Product
# emitter is dedicated to this real Product OpenSBI top, not PA32 or Debian.
./mill aethercore.runMain aethercore.sim.ElaborateProductOpenSbiRV64GSIMCHIRRTL "$FIR" \
  > "$OUT/chirrtl-export.log" 2>&1 || { tail -80 "$OUT/chirrtl-export.log" >&2; exit 3; }
[[ -s "$FIR" ]] || { echo 'GSIM Product FIRRTL emission missing' >&2; exit 3; }

# Pin upstream at the exact historically validated PA32 revision and reuse its
# patched generator/compiler across re-invocations. No unreviewed latest HEAD.
if [[ ! -d "$SRC/.git" ]]; then
  [[ ! -e "$SRC" ]] || { echo "Unexpected GSIM cache contents: $SRC" >&2; exit 4; }
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin "$GSIM_URL"
  git -C "$SRC" fetch --quiet --depth=1 origin "$REV"
  git -C "$SRC" checkout -q --detach FETCH_HEAD
fi
[[ "$(git -C "$SRC" rev-parse HEAD)" == "$REV" ]] || {
  echo 'GSIM source revision mismatch' >&2; exit 4;
}
PATCH_MARKER="$SRC/.aethercore-product-gsim-patch.sha256"
PATCH_SHA="$(sha256sum tools/ci/product_gsim_frontend_patch.py | awk '{print $1}')"
if [[ ! -f "$PATCH_MARKER" ]]; then
  [[ -z "$(git -C "$SRC" status --porcelain --untracked-files=no)" ]] || {
    echo 'GSIM source was modified outside the versioned Product patch' >&2; exit 4;
  }
  python3 tools/ci/product_gsim_frontend_patch.py "$SRC/src/splitNodes.cpp" "$SRC/src/cppEmitter.cpp"
  printf '%s %s\n' "$PATCH_SHA" "$(git -C "$SRC" diff -- src/cppEmitter.cpp src/splitNodes.cpp | sha256sum | awk '{print $1}')" > "$PATCH_MARKER"
fi
[[ "${PATCH_SHA} $(git -C "$SRC" diff -- src/cppEmitter.cpp src/splitNodes.cpp | sha256sum | awk '{print $1}')" == "$(< "$PATCH_MARKER")" ]] || {
  echo 'Cached GSIM source uses an outdated Product patch; use a fresh dedicated source cache' >&2; exit 4;
}
[[ "$(git -C "$SRC" diff --name-only | sort)" == $'src/cppEmitter.cpp\nsrc/splitNodes.cpp' ]] || {
  echo 'Unexpected changed upstream GSIM source paths' >&2; exit 4;
}
git -C "$SRC" diff -- src/cppEmitter.cpp src/splitNodes.cpp > "$OUT/gsim-product-scoped.patch"
[[ -s "$OUT/gsim-product-scoped.patch" ]] || exit 4

if [[ ! -x "$SRC/build/gsim/gsim" ]]; then
  make -C "$SRC" CXX=clang++-19 -j "$JOBS" build-gsim > "$OUT/gsim-build.log" 2>&1 || {
    tail -100 "$OUT/gsim-build.log" >&2; exit 5;
  }
fi

FIR_SHA="$(sha256sum "$FIR" | awk '{print $1}')"
GSIM_PATCH_SHA="$(sha256sum "$OUT/gsim-product-scoped.patch" | awk '{print $1}')"
COMPILER_SHA="$(sha256sum "$SRC/build/gsim/gsim" | awk '{print $1}')"
GEN_ID="$(printf '%s\n' "$FIR_SHA" "$GSIM_PATCH_SHA" "$COMPILER_SHA" "$SIZE" | sha256sum | awk '{print $1}')"
if [[ ! -s "$GENERATED/.aethercore-product-generator-id" || "$(< "$GENERATED/.aethercore-product-generator-id")" != "$GEN_ID" ]]; then
  mkdir -p "$GENERATED"
  timeout --signal=TERM --kill-after=10s 1800s \
    "$SRC/build/gsim/gsim" --supernode-max-size="$SIZE" --dir "$GENERATED" --log-level=1 "$FIR" \
    > "$OUT/gsim-compile.log" 2>&1 || { tail -100 "$OUT/gsim-compile.log" >&2; exit 6; }
  printf '%s\n' "$GEN_ID" > "$GENERATED/.aethercore-product-generator-id"
fi
[[ -s "$GENERATED/AetherCoreProductOpenSbiRV64SimTop.h" ]] || {
  echo 'GSIM Product top header missing: generator/port name mismatch' >&2; exit 6;
}
native_inputs=("$ROOT/sim/product_gsim_prefix_main.cpp" "$ROOT/sim/product_gsim_port_adapter.h" "$ROOT/sim/l32_opensbi_runtime.h")
NATIVE_ID="$(printf '%s\n' "$GEN_ID" "$OPT" "$(clang++-19 --version | head -1)" "$(sha256sum "${native_inputs[@]}" | sha256sum | awk '{print $1}')" | sha256sum | awk '{print $1}')"
if [[ ! -x "$BIN" || ! -s "$OUT/product-gsim-native-id" || "$(< "$OUT/product-gsim-native-id")" != "$NATIVE_ID" ]]; then
  timeout --signal=TERM --kill-after=10s 2400s \
    clang++-19 -std=gnu++20 "$OPT" -march=native \
    -DAETHERCORE_PRODUCT_GSIM_HOST_BRIDGE \
    -I"$GENERATED" -I"$ROOT/sim" "$GENERATED/"*.cpp \
    "$ROOT/sim/product_gsim_prefix_main.cpp" -pthread -lz -o "$BIN" \
    > "$OUT/native-build.log" 2>&1 || { tail -100 "$OUT/native-build.log" >&2; exit 7; }
  printf '%s\n' "$NATIVE_ID" > "$OUT/product-gsim-native-id"
fi
[[ -x "$BIN" ]] || exit 7
printf 'gsim_revision=%s\nfir_sha256=%s\npatch_sha256=%s\ngenerator_sha256=%s\ngenerator_id=%s\nnative_id=%s\nmodel_sha256=%s\n' \
  "$REV" "$FIR_SHA" "$GSIM_PATCH_SHA" "$COMPILER_SHA" "$GEN_ID" "$NATIVE_ID" \
  "$(sha256sum "$BIN" | awk '{print $1}')" > "$OUT/build-identity.txt"
echo "PRODUCT_GSIM_MODEL_READY path=$BIN generator_id=$GEN_ID native_id=$NATIVE_ID"
