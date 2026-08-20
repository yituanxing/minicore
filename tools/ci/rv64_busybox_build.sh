#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/linux_early.env"
source "${ROOT_DIR}/software/rv64_busybox/manifest.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/rv64/userspace"
DOWNLOAD_DIR="${CACHE_ROOT}/downloads"
SOURCE_ROOT="${CACHE_ROOT}/sources"
BUILD_DIR="${ROOT_DIR}/build/rv64-busybox"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
MUSL_BUILD_DIR="${BUILD_DIR}/musl-src"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"
BUSYBOX_BUILD_DIR="${BUILD_DIR}/busybox-src"
RV64_CC="${BUILD_DIR}/rv64-linux-gcc"
MUSL_GCC="${BUILD_DIR}/rv64-musl-gcc"
JOBS="${RV64_USERSPACE_JOBS:-$(nproc)}"

BASE_GCC="${CROSS}gcc"
READELF="${CROSS}readelf"
OBJDUMP="${CROSS}objdump"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

mkdir -p "${DOWNLOAD_DIR}" "${SOURCE_ROOT}" "${BUILD_DIR}" "${EVIDENCE_DIR}"
for tool in curl tar sha256sum file make python3 "${BASE_GCC}" "${READELF}" "${OBJDUMP}"; do
  command -v "${tool}" >/dev/null 2>&1 || fail "missing RV64 userspace build tool: ${tool}"
done
[[ "$(${BASE_GCC} -dumpmachine)" == riscv64*linux* ]] || fail "unexpected RV64 Linux target"
[[ "$(${BASE_GCC} -dumpfullversion)" == "13.3.0" ]] || fail "unexpected GCC version"

fetch_verified() {
  local url="$1" sha="$2" output="$3" tmp
  if [[ -s "${output}" ]] && printf '%s  %s\n' "${sha}" "${output}" | sha256sum -c - >/dev/null 2>&1; then
    return 0
  fi
  tmp="${output}.tmp.$$"
  rm -f "${tmp}"
  curl -fL --retry 3 --retry-delay 2 "${url}" -o "${tmp}"
  printf '%s  %s\n' "${sha}" "${tmp}" | sha256sum -c -
  mv "${tmp}" "${output}"
}

audit_rv64_softfloat_no_c() {
  local name="$1" elf="$2" output="$3" arch flags compressed
  "${READELF}" -h -l -A "${elf}" > "${output}.readelf"
  file "${elf}" > "${output}.file"
  grep -q 'Class:[[:space:]]*ELF64' "${output}.readelf" || fail "${name} is not ELF64"
  grep -q 'Machine:[[:space:]]*RISC-V' "${output}.readelf" || fail "${name} is not RISC-V"
  arch="$(sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' "${output}.readelf" | head -n 1)"
  [[ "${arch}" == rv64i* && "${arch}" == *"_m"* && "${arch}" == *"_a"* ]] || \
    fail "${name} lost RV64IMA architecture: ${arch}"
  [[ "${arch}" == *"_zicsr"* && "${arch}" == *"_zifencei"* ]] || \
    fail "${name} lost Zicsr/Zifencei: ${arch}"
  if [[ "${arch}" =~ _c[0-9] || "${arch}" =~ _f[0-9] || "${arch}" =~ _d[0-9] || "${arch}" =~ _v[0-9] ]]; then
    fail "${name} retained unsupported C/F/D/V extension: ${arch}"
  fi
  flags="$(python3 - "${elf}" <<'PY'
from pathlib import Path
import sys
data = Path(sys.argv[1]).read_bytes()
if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
    raise SystemExit("invalid little-endian ELF64")
if int.from_bytes(data[18:20], "little") != 243:
    raise SystemExit("not EM_RISCV")
print(int.from_bytes(data[48:52], "little"))
PY
)"
  (( (flags & 0x7) == 0 )) || fail "${name} retained RVC/float ELF flags: 0x$(printf '%x' "${flags}")"
  compressed="$("${OBJDUMP}" -d "${elf}" | python3 -c 'import re,sys; print(sum(bool(re.match(r"^\s*[0-9a-f]+:\s+[0-9a-f]{4}\s", line, re.I)) for line in sys.stdin))')"
  {
    echo "RV64_ELF_PROFILE_PASS"
    echo "name=${name}"
    echo "arch=${arch}"
    printf 'elf_flags=0x%x\n' "${flags}"
    echo "compressed_disassembly_lines=${compressed}"
  } > "${output}"
}

cat > "${RV64_CC}" <<EOF
#!/usr/bin/env bash
exec "$(command -v "${BASE_GCC}")" -march="${RV64_USERSPACE_ISA}" -mabi="${RV64_USERSPACE_ABI}" -mstrict-align "\$@"
EOF
chmod +x "${RV64_CC}"

cat > "${BUILD_DIR}/toolchain-probe.c" <<'EOF'
#include <stdint.h>
uint64_t rv64_toolchain_probe(uint64_t a, uint64_t b) {
    return (a * 33u) ^ b;
}
EOF
"${RV64_CC}" -Os -ffreestanding -c "${BUILD_DIR}/toolchain-probe.c" -o "${BUILD_DIR}/toolchain-probe.o"
audit_rv64_softfloat_no_c toolchain-probe "${BUILD_DIR}/toolchain-probe.o" \
  "${EVIDENCE_DIR}/toolchain-probe-profile.txt"

MUSL_TARBALL="${DOWNLOAD_DIR}/musl-${RV64_MUSL_VERSION}.tar.gz"
BUSYBOX_TARBALL="${DOWNLOAD_DIR}/busybox-${RV64_BUSYBOX_VERSION}.tar.bz2"
fetch_verified "${RV64_MUSL_ARCHIVE}" "${RV64_MUSL_SHA256}" "${MUSL_TARBALL}"
fetch_verified "${RV64_BUSYBOX_ARCHIVE}" "${RV64_BUSYBOX_SHA256}" "${BUSYBOX_TARBALL}"

MUSL_SOURCE_DIR="${SOURCE_ROOT}/musl-${RV64_MUSL_VERSION}"
BUSYBOX_SOURCE_DIR="${SOURCE_ROOT}/busybox-${RV64_BUSYBOX_VERSION}"
if [[ ! -f "${MUSL_SOURCE_DIR}/configure" ]]; then
  rm -rf "${MUSL_SOURCE_DIR}"
  tar -xzf "${MUSL_TARBALL}" -C "${SOURCE_ROOT}"
fi
if [[ ! -f "${BUSYBOX_SOURCE_DIR}/Makefile" ]]; then
  rm -rf "${BUSYBOX_SOURCE_DIR}"
  tar -xjf "${BUSYBOX_TARBALL}" -C "${SOURCE_ROOT}"
fi

rm -rf "${MUSL_BUILD_DIR}" "${MUSL_PREFIX}" "${BUSYBOX_BUILD_DIR}"
cp -a "${MUSL_SOURCE_DIR}" "${MUSL_BUILD_DIR}"
cp -a "${BUSYBOX_SOURCE_DIR}" "${BUSYBOX_BUILD_DIR}"

(
  cd "${MUSL_BUILD_DIR}"
  ./configure \
    --target=riscv64-linux-musl \
    --prefix="${MUSL_PREFIX}" \
    --disable-shared \
    --enable-static \
    "CC=${RV64_CC}" \
    "CROSS_COMPILE=${CROSS}" \
    "CFLAGS=-Os -pipe"
  make -j"${JOBS}"
  make install
) 2>&1 | tee "${BUILD_DIR}/musl-build.log"

for crt in crt1.o crti.o crtn.o; do
  [[ -s "${MUSL_PREFIX}/lib/${crt}" ]] || fail "musl prefix is missing ${crt}"
done
[[ -d "${MUSL_PREFIX}/include" ]] || fail "musl headers are missing"

cat > "${MUSL_GCC}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
source "${ROOT_DIR}/software/rv64_busybox/manifest.env"
: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?missing pinned RV64 Linux toolchain}"
BUILD_DIR="${ROOT_DIR}/build/rv64-busybox"
RV64_CC="${BUILD_DIR}/rv64-linux-gcc"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"

filtered=()
skip_next=0
for arg in "$@"; do
  if (( skip_next )); then
    skip_next=0
    continue
  fi
  case "${arg}" in
    -specs) skip_next=1 ;;
    -specs=*) ;;
    *) filtered+=("${arg}") ;;
  esac
done

compile_only=0
relocatable=0
for arg in "${filtered[@]}"; do
  case "${arg}" in
    -c|-S|-E|-M|-MM) compile_only=1 ;;
    -r|-Wl,-r|-Wl,--relocatable) relocatable=1 ;;
  esac
done

gcc_include="$("${RV64_CC}" -print-file-name=include)"
common=(-nostdinc -isystem "${MUSL_PREFIX}/include" -isystem "${gcc_include}")
if (( compile_only )); then
  exec "${RV64_CC}" "${common[@]}" "${filtered[@]}"
fi
if (( relocatable )); then
  exec "${RV64_CC}" -nostdlib "${filtered[@]}"
fi
libgcc="$("${RV64_CC}" -print-libgcc-file-name)"
exec "${RV64_CC}" "${common[@]}" -nostdlib -static -L"${MUSL_PREFIX}/lib" \
  "${MUSL_PREFIX}/lib/crt1.o" "${MUSL_PREFIX}/lib/crti.o" "${filtered[@]}" \
  -Wl,--start-group -lc "${libgcc}" -Wl,--end-group "${MUSL_PREFIX}/lib/crtn.o"
EOF
chmod +x "${MUSL_GCC}"

cat > "${BUILD_DIR}/musl-probe.c" <<'EOF'
#include <unistd.h>
int main(void) {
    static const char message[] = "RV64 musl probe\n";
    return write(1, message, sizeof(message) - 1) < 0;
}
EOF
"${MUSL_GCC}" -Os -static "${BUILD_DIR}/musl-probe.c" -o "${BUILD_DIR}/musl-probe"
file "${BUILD_DIR}/musl-probe" | grep -q 'statically linked' || fail "musl probe is not static"
audit_rv64_softfloat_no_c musl-probe "${BUILD_DIR}/musl-probe" \
  "${EVIDENCE_DIR}/musl-probe-profile.txt"

(
  cd "${BUSYBOX_BUILD_DIR}"
  make ARCH=riscv allnoconfig
  python3 - <<'PY'
from pathlib import Path

path = Path('.config')
lines = path.read_text().splitlines()


def set_symbol(symbol: str, enabled: bool) -> None:
    assignment = f"{symbol}=y"
    disabled = f"# {symbol} is not set"
    for index, line in enumerate(lines):
        if line.startswith(f"{symbol}=") or line == disabled:
            lines[index] = assignment if enabled else disabled
            return
    lines.append(assignment if enabled else disabled)


for symbol in (
    'CONFIG_STATIC',
    'CONFIG_LFS',
    'CONFIG_ASH',
    'CONFIG_SH_IS_ASH',
    'CONFIG_ECHO',
    'CONFIG_PRINTF',
    'CONFIG_TEST',
    'CONFIG_TRUE',
    'CONFIG_FALSE',
    'CONFIG_UNAME',
):
    set_symbol(symbol, True)
set_symbol('CONFIG_SH_IS_HUSH', False)
set_symbol('CONFIG_SH_IS_NONE', False)
path.write_text('\n'.join(lines) + '\n')
PY
  make ARCH=riscv oldconfig </dev/null
  grep -E '^(CONFIG_STATIC|CONFIG_ASH|CONFIG_SH_IS_ASH|CONFIG_ECHO|CONFIG_PRINTF|CONFIG_TEST|CONFIG_TRUE|CONFIG_FALSE|CONFIG_UNAME)=y$' .config
  make ARCH=riscv \
    CROSS_COMPILE="${CROSS}" \
    CC="${MUSL_GCC}" \
    HOSTCC="${HOSTCC:-cc}" \
    -j"${JOBS}" busybox
) 2>&1 | tee "${BUILD_DIR}/busybox-build.log"

BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox"
[[ -s "${BUSYBOX_ELF}" ]] || fail "BusyBox build did not produce a binary"
file "${BUSYBOX_ELF}" | grep -q 'statically linked' || fail "BusyBox is not static"
audit_rv64_softfloat_no_c busybox "${BUSYBOX_ELF}" "${EVIDENCE_DIR}/busybox-profile.txt"

sha256sum "${MUSL_TARBALL}" "${BUSYBOX_TARBALL}" "${BUILD_DIR}/musl-probe" "${BUSYBOX_ELF}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "RV64_BUSYBOX_BUILD_RESULT: status=PASS"
  echo "compiler=$(${BASE_GCC} --version | head -n 1)"
  echo "isa=${RV64_USERSPACE_ISA}"
  echo "abi=${RV64_USERSPACE_ABI}"
  echo "musl_version=${RV64_MUSL_VERSION}"
  echo "busybox_version=${RV64_BUSYBOX_VERSION}"
  echo "musl_wrapper=${MUSL_GCC}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
