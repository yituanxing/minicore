#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RV64_PAYLOAD_LINUX_BUILD_DIR="${ROOT_DIR}/build/rv64-debian-linux"
export RV64_PAYLOAD_BUILD_DIR="${ROOT_DIR}/build/rv64-debian-boot"
export RV64_PAYLOAD_LINUX_QUALIFIER='RV64_DEBIAN_LINUX_BUILD_RESULT'
export RV64_PAYLOAD_RESULT_TAG='RV64_DEBIAN_PAYLOAD_BUILD_RESULT'
export RV64_PAYLOAD_INPUT_TAG='RV64_DEBIAN_PAYLOAD_INPUT'
export RV64_PAYLOAD_ISA='rv64imafdc_zicsr_zifencei'
# Machine-mode OpenSBI uses the integer calling convention even when the
# hardware advertises F/D; only Debian S/U-mode ELFs use LP64D.
export RV64_PAYLOAD_ABI='lp64'

# Reuse the exact OpenSBI/DTS/link-address implementation of G4, changing
# only the chosen software ISA/ABI and the qualified RV64GC Linux Image.
bash "${ROOT_DIR}/tools/ci/rv64_minimal_init_payload_build.sh"
grep -qx 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' \
  "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
