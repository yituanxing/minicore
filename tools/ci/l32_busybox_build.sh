#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/userspace"
DOWNLOAD_DIR="${CACHE_ROOT}/downloads"
SOURCE_ROOT="${CACHE_ROOT}/sources"
BUILD_DIR="${ROOT_DIR}/build/l32-busybox"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
MUSL_BUILD_DIR="${BUILD_DIR}/musl-src"
MUSL_PREFIX="${BUILD_DIR}/musl-prefix"
BUSYBOX_BUILD_DIR="${BUILD_DIR}/busybox-src"
JOBS="${L32_USERSPACE_JOBS:-$(nproc)}"

BASE_GCC="${L32_USERSPACE_CROSS_COMPILE_PREFIX}gcc"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"

mkdir -p "${DOWNLOAD_DIR}" "${SOURCE_ROOT}" "${BUILD_DIR}" "${EVIDENCE_DIR}"

for tool in curl tar sha256sum file make python3 "${BASE_GCC}" "${READELF}"; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "ERROR: missing required L32 userspace build tool: ${tool}" >&2
    exit 20
  }
done

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

MUSL_CC="${BASE_GCC} -march=${L32_USERSPACE_ISA} -mabi=${L32_USERSPACE_ABI}"
(
  cd "${MUSL_BUILD_DIR}"
  ./configure \
    --target=riscv32-linux-musl \
    --prefix="${MUSL_PREFIX}" \
    --disable-shared \
    --enable-static \
    --enable-gcc-wrapper \
    "CC=${MUSL_CC}" \
    "CROSS_COMPILE=${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
    "CFLAGS=-Os -pipe"
  make -j"${JOBS}"
  make install
) 2>&1 | tee "${BUILD_DIR}/musl-build.log"

MUSL_GCC="${MUSL_PREFIX}/bin/musl-gcc"
[[ -x "${MUSL_GCC}" ]] || {
  echo "ERROR: musl build did not install the gcc wrapper" >&2
  exit 21
}

cat > "${BUILD_DIR}/musl-probe.c" <<'EOF'
#include <unistd.h>
int main(void) {
  static const char message[] = "L32 musl probe\n";
  return write(1, message, sizeof(message) - 1) < 0;
}
EOF
"${MUSL_GCC}" \
  -march="${L32_USERSPACE_ISA}" -mabi="${L32_USERSPACE_ABI}" \
  -Os -static "${BUILD_DIR}/musl-probe.c" -o "${BUILD_DIR}/musl-probe"

"${READELF}" -h -A "${BUILD_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-readelf.txt"
file "${BUILD_DIR}/musl-probe" | tee "${EVIDENCE_DIR}/musl-probe-file.txt"
file "${BUILD_DIR}/musl-probe" | grep -q 'statically linked'
"${READELF}" -h "${BUILD_DIR}/musl-probe" | grep -q 'Class:[[:space:]]*ELF32'
"${READELF}" -h "${BUILD_DIR}/musl-probe" | grep -q 'Machine:[[:space:]]*RISC-V'
"${READELF}" -h "${BUILD_DIR}/musl-probe" | grep -q 'soft-float ABI'

(
  cd "${BUSYBOX_BUILD_DIR}"
  make ARCH=riscv defconfig
  python3 - <<'PY'
from pathlib import Path
path = Path('.config')
text = path.read_text()
if '# CONFIG_STATIC is not set' in text:
    text = text.replace('# CONFIG_STATIC is not set', 'CONFIG_STATIC=y')
elif 'CONFIG_STATIC=n' in text:
    text = text.replace('CONFIG_STATIC=n', 'CONFIG_STATIC=y')
elif 'CONFIG_STATIC=y' not in text:
    raise SystemExit('BusyBox defconfig does not expose CONFIG_STATIC')
if 'CONFIG_ASH=y' not in text:
    raise SystemExit('BusyBox defconfig unexpectedly disabled ash')
path.write_text(text)
PY
  make ARCH=riscv \
    CROSS_COMPILE="${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
    CC="${MUSL_GCC} -march=${L32_USERSPACE_ISA} -mabi=${L32_USERSPACE_ABI}" \
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
"${READELF}" -h "${BUSYBOX_ELF}" | grep -q 'Class:[[:space:]]*ELF32'
"${READELF}" -h "${BUSYBOX_ELF}" | grep -q 'Machine:[[:space:]]*RISC-V'
"${READELF}" -h "${BUSYBOX_ELF}" | grep -q 'soft-float ABI'

busybox_arch="$(${READELF} -A "${BUSYBOX_ELF}" | sed -n 's/.*Tag_RISCV_arch: "\([^"]*\)".*/\1/p' | head -n 1)"
[[ "${busybox_arch}" == rv32i* && "${busybox_arch}" == *"_m"* && "${busybox_arch}" == *"_a"* ]] || {
  echo "ERROR: BusyBox lost required RV32IMA ISA: ${busybox_arch}" >&2
  exit 23
}
if [[ "${busybox_arch}" =~ _f[0-9] || "${busybox_arch}" =~ _d[0-9] || "${busybox_arch}" =~ _c[0-9] ]]; then
  echo "ERROR: BusyBox retained unsupported F/D/C extension: ${busybox_arch}" >&2
  exit 23
fi

sha256sum \
  "${MUSL_TARBALL}" \
  "${BUSYBOX_TARBALL}" \
  "${BUILD_DIR}/musl-probe" \
  "${BUSYBOX_ELF}" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "L32_BUSYBOX_BUILD_RESULT: status=PASS"
  echo "musl_version=${MUSL_VERSION}"
  echo "busybox_version=${BUSYBOX_VERSION}"
  echo "isa=${L32_USERSPACE_ISA}"
  echo "abi=${L32_USERSPACE_ABI}"
  echo "busybox_arch=${busybox_arch}"
  echo "busybox=${BUSYBOX_ELF}"
  echo "busybox_sha256=$(sha256sum "${BUSYBOX_ELF}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
