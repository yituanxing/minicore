#!/usr/bin/env bash
set -euo pipefail

# One ownership point for the Linux userspace qualification profile. The
# historical RV32IMA lane remains the default; RV32IMAC is an explicit peer
# namespace and must never reuse or overwrite historical build outputs.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "${ROOT_DIR}/software/l32/manifest.env"
# shellcheck disable=SC1091
source "${ROOT_DIR}/software/l32_busybox/manifest.env"

L32_USERSPACE_PROFILE="${AETHERCORE_L32_USERSPACE_PROFILE:-rv32ima}"

case "${L32_USERSPACE_PROFILE}" in
  rv32ima)
    L32_USERSPACE_EFFECTIVE_ISA="${L32_USERSPACE_ISA}"
    L32_USERSPACE_BUILD_SUFFIX=""
    L32_USERSPACE_REQUIRE_C=0
    L32_USERSPACE_OPENSBI_ISA="${OPENSBI_RV32_ISA}"
    L32_USERSPACE_DTB_ISA="rv32ima_zicsr_zifencei_sstc"
    ;;
  rv32imac)
    L32_USERSPACE_EFFECTIVE_ISA="rv32imac_zicsr_zifencei"
    L32_USERSPACE_BUILD_SUFFIX="-rv32imac"
    L32_USERSPACE_REQUIRE_C=1
    L32_USERSPACE_OPENSBI_ISA="rv32imac_zicsr_zifencei"
    L32_USERSPACE_DTB_ISA="rv32imac_zicsr_zifencei_sstc"
    ;;
  *)
    echo "ERROR: unsupported L32 userspace profile: ${L32_USERSPACE_PROFILE}" >&2
    return 2 2>/dev/null || exit 2
    ;;
esac

L32_USERSPACE_BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-busybox${L32_USERSPACE_BUILD_SUFFIX}"
L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR="${ROOT_DIR}/build/l32-runtime-probe${L32_USERSPACE_BUILD_SUFFIX}"
L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR="${ROOT_DIR}/build/l32-real-programs${L32_USERSPACE_BUILD_SUFFIX}"
L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox${L32_USERSPACE_BUILD_SUFFIX}"
L32_USERSPACE_PAYLOAD_BUILD_DIR="${ROOT_DIR}/build/l32-busybox-shell-boot${L32_USERSPACE_BUILD_SUFFIX}"
L32_USERSPACE_MUSL_WRAPPER="${L32_USERSPACE_BUSYBOX_BUILD_DIR}/l32-musl-real-gcc"

export \
  L32_USERSPACE_PROFILE \
  L32_USERSPACE_EFFECTIVE_ISA \
  L32_USERSPACE_BUILD_SUFFIX \
  L32_USERSPACE_REQUIRE_C \
  L32_USERSPACE_OPENSBI_ISA \
  L32_USERSPACE_DTB_ISA \
  L32_USERSPACE_BUSYBOX_BUILD_DIR \
  L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR \
  L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR \
  L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR \
  L32_USERSPACE_PAYLOAD_BUILD_DIR \
  L32_USERSPACE_MUSL_WRAPPER

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  cat <<EOF
profile=${L32_USERSPACE_PROFILE}
isa=${L32_USERSPACE_EFFECTIVE_ISA}
abi=${L32_USERSPACE_ABI}
require_c=${L32_USERSPACE_REQUIRE_C}
build_suffix=${L32_USERSPACE_BUILD_SUFFIX}
opensbi_isa=${L32_USERSPACE_OPENSBI_ISA}
dtb_isa=${L32_USERSPACE_DTB_ISA}
busybox_build_dir=${L32_USERSPACE_BUSYBOX_BUILD_DIR}
runtime_probe_build_dir=${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}
real_programs_build_dir=${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}
linux_busybox_build_dir=${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}
payload_build_dir=${L32_USERSPACE_PAYLOAD_BUILD_DIR}
EOF
fi
