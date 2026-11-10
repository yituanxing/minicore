#!/usr/bin/env bash
set -euo pipefail

# Independent pin-level SDHC host-state save/restore, with a deterministic
# synthetic 64MiB sparse MBR/ext4-signature disk. No RTL build, emulator,
# Linux kernel, genuine Debian filesystem or long Product guest execution.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf -- "${BUILD}"' EXIT

CXX="${CXX:-c++}"
"${CXX}" -std=c++20 -O2 -Wall -Wextra -Werror \
  -I"${ROOT}/sim" \
  "${ROOT}/tools/ci/product_sd1_card_checkpoint_selftest.cpp" \
  -o "${BUILD}/sd1-card-checkpoint-selftest"
"${BUILD}/sd1-card-checkpoint-selftest" "${BUILD}/disk.img" "${BUILD}/altered.img"

# Run the pre-existing SD1 pin-level CRC/CMD12/CMD25 regression against the
# same synthetic image, ensuring the checkpoint API did not alter protocol.
"${CXX}" -std=c++20 -O2 -Wall -Wextra -Werror \
  -I"${ROOT}/sim" \
  "${ROOT}/tools/ci/product_sim_sd1_card_probe.cpp" \
  -o "${BUILD}/sd1-original-pin-probe"
"${BUILD}/sd1-original-pin-probe" "${BUILD}/disk.img"

echo 'PRODUCT_SD1_CARD_CHECKPOINT_HOST_TEST_PASS original_pin_probe=PASS no_rtl=1 no_linux=1 no_debian=1'
