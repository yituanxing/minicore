#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"

BUILD_DIR="${1:-${ROOT_DIR}/build/rv64-opensbi-first-exec}"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
DTB="${BUILD_DIR}/aethercore-rv64-sv39.dtb"
PAYLOAD_ELF="${BUILD_DIR}/smode-payload.elf"
PAYLOAD_BIN="${BUILD_DIR}/smode-payload.bin"
OPENSBI_OUT="${BUILD_DIR}/opensbi"
CACHE_BASE="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
RV64_SOURCE_DIR="${CACHE_BASE}/rv64-opensbi/opensbi/${RV64_OPENSBI_COMMIT}"
L32_SOURCE_DIR="${CACHE_BASE}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
JOBS="${RV64_OPENSBI_JOBS:-$(nproc)}"
CROSS_COMPILE="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}"

mkdir -p "${BUILD_DIR}" "${EVIDENCE_DIR}" "$(dirname "${RV64_SOURCE_DIR}")"

[[ -n "${CROSS_COMPILE}" ]] || {
  echo "ERROR: AETHERCORE_RV64_LINUX_CROSS_COMPILE is not set; run tools/ensure_riscv64_linux_gcc_13_3.sh" >&2
  exit 20
}
for tool in git make python3 sha256sum; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "ERROR: missing required RV64 OpenSBI first-exec tool: ${tool}" >&2
    exit 20
  }
done
for tool in gcc ld ar objcopy objdump readelf nm; do
  [[ -x "${CROSS_COMPILE}${tool}" ]] || {
    echo "ERROR: missing pinned RV64 Linux-target tool: ${CROSS_COMPILE}${tool}" >&2
    exit 20
  }
done

# Keep a local first-exec proof that the exact compiler/linker selected for
# OpenSBI can emit RV64 PIE. OpenSBI itself requires a PIE-capable toolchain;
# bare-metal GNU binutils are intentionally rejected by the provisioner.
PIE_PROBE="${BUILD_DIR}/toolchain-pie-probe.elf"
cat > "${BUILD_DIR}/toolchain-pie-probe.c" <<'EOF'
void _start(void)
{
    __asm__ volatile ("wfi");
    for (;;) { }
}
EOF
if ! "${CROSS_COMPILE}gcc" -march="${RV64_OPENSBI_ISA}" -mabi="${RV64_OPENSBI_ABI}" \
    -nostdlib -nostartfiles -ffreestanding -fPIE -pie -Wl,-e,_start \
    "${BUILD_DIR}/toolchain-pie-probe.c" -o "${PIE_PROBE}" \
    2>"${EVIDENCE_DIR}/toolchain-pie-probe.log"; then
  echo "ERROR: pinned RV64 Linux-target GCC cannot compile/link the OpenSBI PIE probe" >&2
  cat "${EVIDENCE_DIR}/toolchain-pie-probe.log" >&2
  exit 20
fi
"${CROSS_COMPILE}readelf" -h "${PIE_PROBE}" > "${EVIDENCE_DIR}/toolchain-pie-readelf.txt"
grep -q 'Class:[[:space:]]*ELF64' "${EVIDENCE_DIR}/toolchain-pie-readelf.txt"
grep -q 'Machine:[[:space:]]*RISC-V' "${EVIDENCE_DIR}/toolchain-pie-readelf.txt"
grep -q 'Type:[[:space:]]*DYN' "${EVIDENCE_DIR}/toolchain-pie-readelf.txt"

validate_source_tree() {
  local source="$1"
  [[ -d "${source}/.git" ]] || return 1
  [[ "$(git -C "${source}" rev-parse HEAD 2>/dev/null)" == "${RV64_OPENSBI_COMMIT}" ]] || return 1
  [[ -z "$(git -C "${source}" status --porcelain --untracked-files=all)" ]] || return 1
}

if validate_source_tree "${L32_SOURCE_DIR}"; then
  SOURCE_DIR="${L32_SOURCE_DIR}"
elif validate_source_tree "${RV64_SOURCE_DIR}"; then
  SOURCE_DIR="${RV64_SOURCE_DIR}"
else
  rm -rf "${RV64_SOURCE_DIR}"
  mkdir -p "${RV64_SOURCE_DIR}"
  git -C "${RV64_SOURCE_DIR}" init -q
  git -C "${RV64_SOURCE_DIR}" remote add origin "${RV64_OPENSBI_REPOSITORY}"
  git -C "${RV64_SOURCE_DIR}" -c http.version=HTTP/1.1 \
    fetch --depth=1 origin "${RV64_OPENSBI_COMMIT}"
  git -C "${RV64_SOURCE_DIR}" checkout -q --detach FETCH_HEAD
  validate_source_tree "${RV64_SOURCE_DIR}" || {
    echo "ERROR: fetched OpenSBI source failed exact clean-tree validation" >&2
    exit 21
  }
  SOURCE_DIR="${RV64_SOURCE_DIR}"
fi

python3 "${ROOT_DIR}/tools/ci/make_l32_dtb.py" \
  --isa "${RV64_OPENSBI_ISA}" \
  --mmu "${RV64_OPENSBI_MMU}" \
  --output "${DTB}" \
  --summary "${EVIDENCE_DIR}/dtb.txt"
grep -qx "isa=${RV64_OPENSBI_ISA}" "${EVIDENCE_DIR}/dtb.txt"
grep -qx "mmu=${RV64_OPENSBI_MMU}" "${EVIDENCE_DIR}/dtb.txt"

payload_cflags=(
  -march="${RV64_OPENSBI_ISA}"
  -mabi="${RV64_OPENSBI_ABI}"
  -mcmodel=medany
  -mno-relax
  -msmall-data-limit=0
  -ffreestanding
  -fno-stack-protector
  -fno-pic
  -fno-pie
  -fno-plt
  -fno-unwind-tables
  -fno-asynchronous-unwind-tables
)
payload_ldflags=(
  -nostdlib
  -nostartfiles
  -static
  -no-pie
  -Wl,-T,"${ROOT_DIR}/software/rv64/opensbi_smode_payload.ld"
  -Wl,--build-id=none
  -Wl,--no-relax
  -Wl,--gc-sections
)

"${CROSS_COMPILE}gcc" "${payload_cflags[@]}" \
  "${ROOT_DIR}/software/rv64/opensbi_smode_payload.S" \
  "${payload_ldflags[@]}" -o "${PAYLOAD_ELF}"
"${CROSS_COMPILE}objcopy" -O binary "${PAYLOAD_ELF}" "${PAYLOAD_BIN}"
"${CROSS_COMPILE}objdump" -d "${PAYLOAD_ELF}" > "${EVIDENCE_DIR}/payload.dis"
"${CROSS_COMPILE}readelf" -h -l -A "${PAYLOAD_ELF}" > "${EVIDENCE_DIR}/payload-readelf.txt"
"${CROSS_COMPILE}nm" -n "${PAYLOAD_ELF}" > "${EVIDENCE_DIR}/payload.nm"

grep -q 'Class:[[:space:]]*ELF64' "${EVIDENCE_DIR}/payload-readelf.txt"
grep -q 'Type:[[:space:]]*EXEC' "${EVIDENCE_DIR}/payload-readelf.txt"
grep -Eq '[[:space:]]ecall([[:space:]]|$)' "${EVIDENCE_DIR}/payload.dis"
grep -Eq '[[:space:]]wfi([[:space:]]|$)' "${EVIDENCE_DIR}/payload.dis"
payload_entry="$(awk '$3 == "_start" { print "0x" $1; exit }' "${EVIDENCE_DIR}/payload.nm")"
[[ "$((payload_entry))" -eq "$((RV64_OPENSBI_PAYLOAD_ADDR))" ]] || {
  echo "ERROR: S-mode payload entry ${payload_entry} != ${RV64_OPENSBI_PAYLOAD_ADDR}" >&2
  exit 22
}

rm -rf "${OPENSBI_OUT}"
mkdir -p "${OPENSBI_OUT}"

make -C "${SOURCE_DIR}" \
  O="${OPENSBI_OUT}" \
  PLATFORM="${RV64_OPENSBI_PLATFORM}" \
  PLATFORM_RISCV_XLEN="${RV64_OPENSBI_XLEN}" \
  PLATFORM_RISCV_ISA="${RV64_OPENSBI_ISA}" \
  PLATFORM_RISCV_ABI="${RV64_OPENSBI_ABI}" \
  FW_TEXT_START="${RV64_OPENSBI_FW_TEXT_START}" \
  FW_FDT_PATH="${DTB}" \
  FW_PAYLOAD_PATH="${PAYLOAD_BIN}" \
  FW_PAYLOAD_OFFSET="${RV64_OPENSBI_PAYLOAD_OFFSET}" \
  FW_PAYLOAD_FDT_ADDR="${RV64_OPENSBI_FDT_ADDR}" \
  CROSS_COMPILE="${CROSS_COMPILE}" \
  -j"${JOBS}" 2>&1 | tee "${BUILD_DIR}/opensbi-build.log"

FW_ELF="${OPENSBI_OUT}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${OPENSBI_OUT}/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: unchanged RV64 OpenSBI 1.6 did not produce fw_payload outputs" >&2
  exit 23
}

READELF="${CROSS_COMPILE}readelf"
"${READELF}" -h -l -A "${FW_ELF}" | tee "${EVIDENCE_DIR}/fw_payload-readelf.txt"
"${READELF}" -h "${FW_ELF}" | grep -q 'Class:[[:space:]]*ELF64'
"${READELF}" -h "${FW_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
fw_entry="$(${READELF} -h "${FW_ELF}" | awk '/Entry point address:/{print $4; exit}')"
[[ "$((fw_entry))" -eq "$((RV64_OPENSBI_FW_TEXT_START))" ]] || {
  echo "ERROR: OpenSBI entry ${fw_entry} != ${RV64_OPENSBI_FW_TEXT_START}" >&2
  exit 24
}

arch="$(${READELF} -A "${FW_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
[[ "${arch}" == rv64i* && "${arch}" == *"_m"* && "${arch}" == *"_a"* ]] || {
  echo "ERROR: unexpected RV64 OpenSBI ISA attributes: ${arch}" >&2
  exit 25
}
[[ "${arch}" == *"zicsr"* && "${arch}" == *"zifencei"* ]] || {
  echo "ERROR: RV64 OpenSBI lost Zicsr/Zifencei: ${arch}" >&2
  exit 25
}
if [[ "${arch}" =~ _c[0-9] || "${arch}" =~ _f[0-9] || "${arch}" =~ _d[0-9] || "${arch}" =~ _v[0-9] ]]; then
  echo "ERROR: RV64 OpenSBI retained unsupported C/F/D/V extension: ${arch}" >&2
  exit 25
fi

sha256sum "${FW_ELF}" "${FW_BIN}" "${PAYLOAD_ELF}" "${PAYLOAD_BIN}" "${DTB}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"
{
  echo "RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS"
  echo "opensbi_version=${RV64_OPENSBI_VERSION}"
  echo "opensbi_commit=${RV64_OPENSBI_COMMIT}"
  echo "opensbi_source=${SOURCE_DIR}"
  echo "xlen=${RV64_OPENSBI_XLEN}"
  echo "isa=${RV64_OPENSBI_ISA}"
  echo "abi=${RV64_OPENSBI_ABI}"
  echo "mmu=${RV64_OPENSBI_MMU}"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
  echo "payload=${PAYLOAD_ELF}"
  echo "payload_bin=${PAYLOAD_BIN}"
  echo "payload_entry=${payload_entry}"
  echo "fdt=${DTB}"
  echo "fdt_addr=${RV64_OPENSBI_FDT_ADDR}"
  echo "milestone=${RV64_OPENSBI_MILESTONE}"
  echo "cross_compile=${CROSS_COMPILE}"
  "${CROSS_COMPILE}gcc" --version | head -n 1
  "${CROSS_COMPILE}gcc" -dumpmachine
  "${CROSS_COMPILE}ld" --version | head -n 1
} | tee "${BUILD_DIR}/result.txt"
