#!/usr/bin/env bash
set -euo pipefail

# Mock accessor counter: a host-only, zero-RTL-cycle biased negative test.
# Real 30M strict/fast same-model terminal state is verified separately by
# product_gsim_fast_ab.sh and must NOT be inferred from this selftest.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT
"${CXX:-c++}" -std=gnu++20 -Wall -Wextra -Werror -O2 \
  -I"$ROOT/tools/ci/fixtures/product_gsim_sparse" -I"$ROOT/sim" \
  "$ROOT/tools/ci/product_gsim_sparse_request_selftest.cpp" \
  -o "$TMP_DIR/product-gsim-sparse-request-selftest"
"$TMP_DIR/product-gsim-sparse-request-selftest"
