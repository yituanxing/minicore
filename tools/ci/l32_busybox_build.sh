#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/userspace"
DOWNLOAD_DIR="${CACHE_ROOT}/downloads"
SOURCE_ROOT="${CACHE_ROOT}/sources"
BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
MUSL_BUILD_DIR="${BUILD_DIR}/musl-src"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"
BUSYBOX_BUILD_DIR="${BUILD_DIR}/busybox-src"
JOBS="${L32_USERSPACE_JOBS:-$(nproc)}"

BASE_GCC="${L32_USERSPACE_CROSS_COMPILE_PREFIX}gcc"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"
OBJDUMP="${L32_USERSPACE_CROSS_COMPILE_PREFIX}objdump"
PROFILE_AUDIT="${ROOT_DIR}/tools/ci/riscv_elf_profile.py"
MUSL_LINK_WRAPPER_BUILDER="${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"

mkdir -p "${DOWNLOAD_DIR}" "${SOURCE_ROOT}" "${BUILD_DIR}" "${EVIDENCE_DIR}"

for tool in curl tar sha256sum file make python3 "${BASE_GCC}" "${READELF}" "${OBJDUMP}"; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "ERROR: missing required L32 userspace build tool: ${tool}" >&2
    exit 20
  }
done
[[ -f "${PROFILE_AUDIT}" ]] || {
  echo "ERROR: missing generic RISC-V ELF profile auditor: ${PROFILE_AUDIT}" >&2
  exit 20
}
[[ -x "${MUSL_LINK_WRAPPER_BUILDER}" ]] || {
  echo "ERROR: missing profile-owned musl link wrapper builder: ${MUSL_LINK_WRAPPER_BUILDER}" >&2
  exit 20
}

audit_riscv_profile() {
  local name="$1" elf="$2" output="$3"
  local c_policy=(--forbid-c)
  if [[ "${L32_USERSPACE_REQUIRE_C}" -eq 1 ]]; then
    c_policy=(--require-c)
  fi
  python3 "${PROFILE_AUDIT}" \
    --elf "${elf}" \
    --name "${name}" \
    --readelf "${READELF}" \
    --objdump "${OBJDUMP}" \
    "${c_policy[@]}" \
    --output "${output}"
}

# The runner's riscv64-unknown-elf toolchain is multilib and is already used by
# the frozen RV32 workload gates. Wrap it so musl sees one compiler command
# whose target contract is always the selected RV32 ISA with ILP32; this also
# avoids accidentally selecting an RV64 libgcc when musl probes the compiler
# runtime.
L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"
cat > "${L32_CC}" <<EOF
#!/usr/bin/env bash
exec "$(command -v "${BASE_GCC}")" -march="${L32_USERSPACE_EFFECTIVE_ISA}" -mabi="${L32_USERSPACE_ABI}" -mstrict-align "\$@"
EOF
chmod +x "${L32_CC}"

cat > "${BUILD_DIR}/toolchain-probe.c" <<'EOF'
#include <stdint.h>
uint32_t l32_toolchain_probe(uint32_t a, uint32_t b) {
  return (a * 33u) ^ b;
}
EOF
{
  echo "compiler=$(${BASE_GCC} --version | head -n 1)"
  echo "dumpmachine=$(${BASE_GCC} -dumpmachine)"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "abi=${L32_USERSPACE_ABI}"
  echo "require_c=${L32_USERSPACE_REQUIRE_C}"
  echo "multilib=$(${BASE_GCC} -march="${L32_USERSPACE_EFFECTIVE_ISA}" -mabi="${L32_USERSPACE_ABI}" -print-multi-directory)"
  echo "libgcc=$(${BASE_GCC} -march="${L32_USERSPACE_EFFECTIVE_ISA}" -mabi="${L32_USERSPACE_ABI}" -print-libgcc-file-name)"
  "${L32_CC}" -Os -ffreestanding -c "${BUILD_DIR}/toolchain-probe.c" -o "${BUILD_DIR}/toolchain-probe.o"
  "${READELF}" -h -A "${BUILD_DIR}/toolchain-probe.o"
} 2>&1 | tee "${BUILD_DIR}/toolchain-probe.log"
audit_riscv_profile toolchain-probe "${BUILD_DIR}/toolchain-probe.o" \
  "${EVIDENCE_DIR}/toolchain-probe-profile.txt" | tee -a "${BUILD_DIR}/toolchain-probe.log"

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

MUSL_TARBALL="${DOWNLOAD_DIR}/musl-${MUSL_VERSION}.tar.gz"
BUSYBOX_TARBALL="${DOWNLOAD_DIR}/busybox-${BUSYBOX_VERSION}.tar.bz2"
fetch_verified "${MUSL_ARCHIVE}" "${MUSL_SHA256}" "${MUSL_TARBALL}"
fetch_verified "${BUSYBOX_ARCHIVE}" "${BUSYBOX_SHA256}" "${BUSYBOX_TARBALL}"

MUSL_SOURCE_DIR="${SOURCE_ROOT}/musl-${MUSL_VERSION}"
BUSYBOX_SOURCE_DIR="${SOURCE_ROOT}/busybox-${BUSYBOX_VERSION}"
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
    --target=riscv32-linux-musl \
    --prefix="${MUSL_PREFIX}" \
    --disable-shared \
    --enable-static \
    --enable-gcc-wrapper \
    "CC=${L32_CC}" \
    "CROSS_COMPILE=${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
    "CFLAGS=-Os -pipe"
  make -j"${JOBS}"
  make install
) 2>&1 | tee "${BUILD_DIR}/musl-build.log"

# Keep musl's upstream gcc wrapper as an install-completeness artifact, but do
# not use its specs with the runner's bare-metal GCC. Those specs reintroduce
# newlib/libgloss start/end files (for example crtbeginS.o and -lgloss), which
# are not Linux-musl ownership. Generate one canonical profile-owned driver and
# use it for every linked userspace artifact from this point onward.
MUSL_UPSTREAM_GCC="${MUSL_PREFIX}/bin/musl-gcc"
[[ -x "${MUSL_UPSTREAM_GCC}" ]] || {
  echo "ERROR: musl build did not install the upstream gcc wrapper" >&2
  exit 21
}
"${MUSL_LINK_WRAPPER_BUILDER}"
MUSL_GCC="${L32_USERSPACE_MUSL_WRAPPER}"
[[ -x "${MUSL_GCC}" ]] || {
  echo "ERROR: profile-owned musl compiler/link driver was not generated" >&2
  exit 21
}

cat > "${BUILD_DIR}/musl-probe.c" <<'EOF'
#include <unistd.h>
int main(void) {
  static const char message[] = "L32 musl probe\n";
  return write(1, message, sizeof(message) - 1) < 0;
}
EOF
"${MUSL_GCC}" -Os -static "${BUILD_DIR}/musl-probe.c" -o "${BUILD_DIR}/musl-probe"

"${READELF}" -h -A "${BUILD_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-readelf.txt"
file "${BUILD_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-file.txt"
file "${BUILD_DIR}/musl-probe" | grep -q 'statically linked'
audit_riscv_profile musl-probe "${BUILD_DIR}/musl-probe" \
  "${EVIDENCE_DIR}/musl-probe-profile.txt"

(
  cd "${BUSYBOX_BUILD_DIR}"

  # This milestone is Linux /bin/sh bring-up, not a full rescue userspace.
  # BusyBox defconfig pulls in console/network/storage applets that require a
  # complete installed Linux UAPI header tree (for example linux/kd.h). Keep
  # the first real userspace intentionally narrow: static ash plus a handful
  # of header-light shell utilities. Expand applets only after shell boot is
  # proven on AetherCore.
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

# SH_IS_* is a Kconfig choice. allnoconfig selects NONE; switch the choice
# explicitly so oldconfig/silentoldconfig cannot revert /bin/sh away from ash.
set_symbol('CONFIG_SH_IS_HUSH', False)
set_symbol('CONFIG_SH_IS_NONE', False)

path.write_text('\n'.join(lines) + '\n')
PY
  make ARCH=riscv oldconfig </dev/null
  grep -E '^(CONFIG_STATIC|CONFIG_LFS|CONFIG_ASH|CONFIG_SH_IS_ASH|CONFIG_ECHO|CONFIG_PRINTF|CONFIG_TEST|CONFIG_TRUE|CONFIG_FALSE|CONFIG_UNAME)=y$' .config
  if grep -q '^CONFIG_KBD_MODE=y$' .config; then
    echo "ERROR: minimal shell config unexpectedly enabled kbd_mode" >&2
    exit 25
  fi

  make ARCH=riscv \
    CROSS_COMPILE="${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
    CC="${MUSL_GCC}" \
    HOSTCC="${HOSTCC:-cc}" \
    -j"${JOBS}" busybox
) 2>&1 | tee "${BUILD_DIR}/busybox-build.log"

BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox"
[[ -s "${BUSYBOX_ELF}" ]] || {
  echo "ERROR: BusyBox build did not produce a binary" >&2
  exit 22
}

"${READELF}" -h -l -A "${BUSYBOX_ELF}" | tee "${EVIDENCE_DIR}/busybox-readelf.txt"
file "${BUSYBOX_ELF}" | tee "${EVIDENCE_DIR}/busybox-file.txt"
file "${BUSYBOX_ELF}" | grep -q 'statically linked'
audit_riscv_profile busybox "${BUSYBOX_ELF}" "${EVIDENCE_DIR}/busybox-profile.txt"

busybox_arch="$(${READELF} -A "${BUSYBOX_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
busybox_compressed="$(awk -F= '$1 == "compressed_instructions" {print $2; exit}' "${EVIDENCE_DIR}/busybox-profile.txt")"
[[ -n "${busybox_compressed}" ]] || {
  echo "ERROR: generic BusyBox profile audit did not record compressed count" >&2
  exit 23
}

sha256sum \
  "${MUSL_TARBALL}" \
  "${BUSYBOX_TARBALL}" \
  "${BUILD_DIR}/musl-probe" \
  "${BUSYBOX_ELF}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "L32_BUSYBOX_BUILD_RESULT: status=PASS"
  echo "bootstrap_compiler=$(${BASE_GCC} --version | head -n 1)"
  echo "profile=${L32_USERSPACE_PROFILE}"
  echo "musl_version=${MUSL_VERSION}"
  echo "busybox_version=${BUSYBOX_VERSION}"
  echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "abi=${L32_USERSPACE_ABI}"
  echo "require_c=${L32_USERSPACE_REQUIRE_C}"
  echo "musl_wrapper=${MUSL_GCC}"
  echo "musl_wrapper_sha256=$(sha256sum "${MUSL_GCC}" | awk '{print $1}')"
  echo "busybox_arch=${busybox_arch}"
  echo "busybox_compressed_instructions=${busybox_compressed}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
