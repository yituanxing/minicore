#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32"
SOURCE_DIR="${CACHE_ROOT}/opensbi/${OPENSBI_COMMIT}"
BUILD_DIR="${ROOT_DIR}/build/l32-opensbi"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv32.dtb"
JOBS="${L32_JOBS:-$(nproc)}"
FW_TEXT_START="0x80000000"
TOOLCHAIN_MODE="${L32_TOOLCHAIN_MODE:-llvm}"

mkdir -p "${CACHE_ROOT}/opensbi" "${BUILD_DIR}" "${EVIDENCE_DIR}"

probe_gcc_prefix() {
  local prefix="$1"
  local compiler="${prefix}gcc"
  command -v "${compiler}" >/dev/null 2>&1 || return 1

  if ! printf 'int l32_toolchain_probe;\n' | \
    "${compiler}" -x c -c -o "${BUILD_DIR}/toolchain-probe.o" - \
      -march=rv32ima_zicsr_zifencei -mabi=ilp32 -fPIE >/dev/null 2>&1; then
    return 1
  fi

  # Match OpenSBI v1.6's mandatory linker capability check. An explicitly
  # pinned bare-metal toolchain is acceptable only when its linker can really
  # create the RV32 PIE firmware; target naming alone is not the contract.
  if ! "${compiler}" \
    -march=rv32ima_zicsr_zifencei -mabi=ilp32 \
    -fPIE -nostdlib -Wl,-pie -x c /dev/null \
    -o "${BUILD_DIR}/toolchain-pie-probe.elf" >/dev/null 2>&1; then
    return 1
  fi

  return 0
}

select_pie_cross_compile() {
  local prefix machine

  # Explicit prefixes are trusted only after the exact PIE link probe above.
  # L32 uses this for the repository-pinned xPack GCC installed by CI.
  if [[ -n "${L32_CROSS_COMPILE:-}" ]]; then
    prefix="${L32_CROSS_COMPILE}"
    if probe_gcc_prefix "${prefix}"; then
      printf '%s\n' "${prefix}"
      return 0
    fi
    echo "L32_EXPLICIT_GCC_PIE_FAILED: ${prefix}gcc cannot build RV32 ILP32 PIE" >&2
    return 20
  fi

  # Automatic discovery remains Linux-target only. Never silently fall back to
  # whatever system unknown-elf compiler happens to be installed.
  for prefix in riscv64-linux-gnu- riscv32-linux-gnu- riscv64-linux-musl- riscv32-linux-musl-; do
    command -v "${prefix}gcc" >/dev/null 2>&1 || continue
    machine="$(${prefix}gcc -dumpmachine 2>/dev/null || true)"
    [[ "${machine}" == *linux* ]] || continue
    if probe_gcc_prefix "${prefix}"; then
      printf '%s\n' "${prefix}"
      return 0
    fi
  done

  echo "L32_PIE_GCC_MISSING: need a RISC-V GCC that can compile and link RV32 ILP32 PIE" >&2
  return 20
}

MAKE_TOOLCHAIN_ARGS=()
TOOLCHAIN_SUMMARY=""
if [[ "${TOOLCHAIN_MODE}" == "llvm" ]]; then
  for tool in clang ld.lld llvm-ar llvm-objcopy; do
    command -v "${tool}" >/dev/null 2>&1 || {
      echo "L32_LLVM_MISSING: ${tool} is required for LLVM=1 OpenSBI builds" >&2
      exit 20
    }
  done
  if ! printf 'int l32_toolchain_probe;\n' | \
    clang --target=riscv32-unknown-elf -fuse-ld=lld -x c -nostdlib -fPIE -Wl,-pie \
      -march=rv32ima_zicsr_zifencei -mabi=ilp32 -o "${BUILD_DIR}/toolchain-pie-probe.elf" - \
      >/dev/null 2>&1; then
    echo "L32_LLVM_RV32_PIE_MISSING: clang/lld cannot link the frozen RV32 PIE probe" >&2
    exit 20
  fi
  MAKE_TOOLCHAIN_ARGS+=(LLVM=1)
  TOOLCHAIN_SUMMARY="llvm"
elif [[ "${TOOLCHAIN_MODE}" == "gcc" ]]; then
  CROSS_COMPILE="$(select_pie_cross_compile)"
  COMPILER="${CROSS_COMPILE}gcc"
  MAKE_TOOLCHAIN_ARGS+=("CROSS_COMPILE=${CROSS_COMPILE}")
  TOOLCHAIN_SUMMARY="${CROSS_COMPILE}"
else
  echo "ERROR: unsupported L32_TOOLCHAIN_MODE=${TOOLCHAIN_MODE}; expected llvm or gcc" >&2
  exit 20
fi

python3 -m py_compile "${ROOT_DIR}/tools/ci/make_l32_dtb.py"
python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/aethercore-rv32-dtb.txt"

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  rm -rf "${SOURCE_DIR}"
  mkdir -p "${SOURCE_DIR}"
  git -C "${SOURCE_DIR}" init -q
  git -C "${SOURCE_DIR}" remote add origin "${OPENSBI_REPOSITORY}"
  git -C "${SOURCE_DIR}" fetch --depth=1 origin "${OPENSBI_COMMIT}"
  git -C "${SOURCE_DIR}" checkout -q --detach FETCH_HEAD
fi

observed_commit="$(git -C "${SOURCE_DIR}" rev-parse HEAD)"
[[ "${observed_commit}" == "${OPENSBI_COMMIT}" ]] || {
  echo "ERROR: cached OpenSBI commit ${observed_commit} != ${OPENSBI_COMMIT}" >&2
  exit 21
}
git -C "${SOURCE_DIR}" diff --quiet --ignore-submodules -- || {
  echo "ERROR: cached OpenSBI source tree is dirty" >&2
  exit 22
}

rm -rf "${BUILD_DIR}/build"
mkdir -p "${BUILD_DIR}/build"
{
  echo "LINUX_VERSION=${LINUX_VERSION}"
  echo "OPENSBI_VERSION=${OPENSBI_VERSION}"
  echo "OPENSBI_COMMIT=${OPENSBI_COMMIT}"
  echo "PLATFORM=${L32_PLATFORM}"
  echo "PLATFORM_RISCV_XLEN=${L32_XLEN}"
  echo "FW_TEXT_START=${FW_TEXT_START}"
  echo "FW_FDT_PATH=${DTB}"
  echo "TOOLCHAIN_MODE=${TOOLCHAIN_MODE}"
  echo "TOOLCHAIN=${TOOLCHAIN_SUMMARY}"
  if [[ "${TOOLCHAIN_MODE}" == "llvm" ]]; then
    clang --version | head -n 1
    ld.lld --version | head -n 1
  else
    "${COMPILER}" --version | head -n 1
    "${COMPILER}" -dumpmachine
    "${CROSS_COMPILE}ld" --version | head -n 1
  fi
  python3 --version
} | tee "${EVIDENCE_DIR}/inputs.txt"

make -C "${SOURCE_DIR}" \
  O="${BUILD_DIR}/build" \
  PLATFORM="${L32_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${L32_XLEN}" \
  FW_TEXT_START="${FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  "${MAKE_TOOLCHAIN_ARGS[@]}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-build.log"

FW_ELF="${BUILD_DIR}/build/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${BUILD_DIR}/build/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || { echo "ERROR: missing OpenSBI payload outputs" >&2; exit 23; }

if [[ "${TOOLCHAIN_MODE}" == "gcc" ]]; then
  READELF="${CROSS_COMPILE}readelf"
elif command -v llvm-readelf >/dev/null 2>&1; then
  READELF="llvm-readelf"
else
  READELF="readelf"
fi
"${READELF}" -h -l -A "${FW_ELF}" | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
file "${FW_ELF}" | tee "${EVIDENCE_DIR}/fw_payload-file.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${DTB}" | tee "${EVIDENCE_DIR}/sha256.txt"
"${READELF}" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF32'
"${READELF}" -h "${FW_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
entry="$(${READELF} -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((entry))" -eq "$((FW_TEXT_START))" ]] || { echo "ERROR: OpenSBI entry ${entry} != ${FW_TEXT_START}" >&2; exit 25; }

{
  echo "L32_OPENSBI_RESULT: status=PASS"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "fdt=${DTB}"
  echo "entry=${entry}"
  echo "commit=${OPENSBI_COMMIT}"
  echo "xlen=${L32_XLEN}"
  echo "platform=${L32_PLATFORM}"
  echo "toolchain=${TOOLCHAIN_SUMMARY}"
} | tee "${BUILD_DIR}/result.txt"
