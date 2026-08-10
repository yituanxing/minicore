#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"
source "${ROOT_DIR}/software/l32_real/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/real-programs"
DOWNLOAD_DIR="${CACHE_ROOT}/downloads"
SOURCE_DIR="${CACHE_ROOT}/sources"
BUILD_DIR="${ROOT_DIR}/build/l32-real-programs"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
MUSL_CC="${ROOT_DIR}/build/l32-busybox/l32-musl-real-gcc"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"
AR="${L32_USERSPACE_CROSS_COMPILE_PREFIX}ar"
RANLIB="${L32_USERSPACE_CROSS_COMPILE_PREFIX}ranlib"
JOBS="${L32_REAL_JOBS:-$(nproc)}"

mkdir -p "${DOWNLOAD_DIR}" "${SOURCE_DIR}" "${BUILD_DIR}" "${EVIDENCE_DIR}"
for tool in curl tar sha256sum file make python3 "${READELF}" "${AR}" "${RANLIB}"; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "ERROR: missing real-program build tool: ${tool}" >&2; exit 20; }
done
[[ -x "${MUSL_CC}" ]] || { echo "ERROR: qualified L32 musl compiler wrapper is missing" >&2; exit 21; }
grep -qx 'L32_BUSYBOX_BUILD_RESULT: status=PASS' "${ROOT_DIR}/build/l32-busybox/result.txt" || {
  echo "ERROR: qualified L32 musl/BusyBox build is required first" >&2
  exit 21
}

fetch_verified() {
  local url="$1" sha="$2" output="$3" tmp
  if [[ -s "${output}" ]] && printf '%s  %s\n' "${sha}" "${output}" | sha256sum -c - >/dev/null 2>&1; then return 0; fi
  tmp="${output}.tmp.$$"
  rm -f "${tmp}"
  curl -fL --retry 3 --retry-delay 2 "${url}" -o "${tmp}"
  printf '%s  %s\n' "${sha}" "${tmp}" | sha256sum -c -
  mv "${tmp}" "${output}"
}

check_elf() {
  local elf="$1"
  [[ -s "${elf}" ]] || { echo "ERROR: missing ELF ${elf}" >&2; exit 30; }
  file "${elf}" | grep -q 'ELF 32-bit.*RISC-V'
  file "${elf}" | grep -q 'statically linked'
  python3 - "${elf}" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
d = p.read_bytes()
if d[:4] != b'\x7fELF' or d[4] != 1 or d[5] != 1:
    raise SystemExit(f"ERROR: expected little-endian ELF32: {p}")
if int.from_bytes(d[18:20], 'little') != 243:
    raise SystemExit(f"ERROR: expected RISC-V ELF: {p}")
flags = int.from_bytes(d[36:40], 'little')
if flags & 0x0006 or flags & 0x0001:
    raise SystemExit(f"ERROR: expected soft-float non-RVC ELF flags=0x{flags:x}: {p}")
PY
  "${READELF}" -h -A "${elf}"
}

LUA_TARBALL="${DOWNLOAD_DIR}/lua-${LUA_VERSION}.tar.gz"
SQLITE_ZIP="${DOWNLOAD_DIR}/sqlite-amalgamation-${SQLITE_AMALGAMATION_ID}.zip"
BASH_TARBALL="${DOWNLOAD_DIR}/bash-${BASH_VERSION}.tar.gz"
BUSYBOX_REAL_TARBALL="${DOWNLOAD_DIR}/busybox-${BUSYBOX_VERSION}.tar.bz2"
fetch_verified "${LUA_ARCHIVE}" "${LUA_SHA256}" "${LUA_TARBALL}"
fetch_verified "${SQLITE_ARCHIVE}" "${SQLITE_SHA256}" "${SQLITE_ZIP}"
fetch_verified "${BASH_ARCHIVE}" "${BASH_SHA256}" "${BASH_TARBALL}"
fetch_verified "${BUSYBOX_ARCHIVE}" "${BUSYBOX_SHA256}" "${BUSYBOX_REAL_TARBALL}"

LUA_SRC="${SOURCE_DIR}/lua-${LUA_VERSION}"
if [[ ! -f "${LUA_SRC}/src/lua.c" ]]; then
  rm -rf "${LUA_SRC}"
  tar -xzf "${LUA_TARBALL}" -C "${SOURCE_DIR}"
fi
SQLITE_SRC="${SOURCE_DIR}/sqlite-amalgamation-${SQLITE_AMALGAMATION_ID}"
if [[ ! -f "${SQLITE_SRC}/sqlite3.c" ]]; then
  rm -rf "${SQLITE_SRC}"
  python3 - "${SQLITE_ZIP}" "${SOURCE_DIR}" <<'PY'
from pathlib import Path
import sys, zipfile
archive = Path(sys.argv[1])
out = Path(sys.argv[2])
with zipfile.ZipFile(archive) as zf:
    zf.extractall(out)
PY
fi
BASH_SRC="${SOURCE_DIR}/bash-${BASH_VERSION}"
if [[ ! -f "${BASH_SRC}/configure" ]]; then
  rm -rf "${BASH_SRC}"
  tar -xzf "${BASH_TARBALL}" -C "${SOURCE_DIR}"
fi
BUSYBOX_REAL_SRC="${SOURCE_DIR}/busybox-${BUSYBOX_VERSION}-real"
if [[ ! -f "${BUSYBOX_REAL_SRC}/Makefile" ]]; then
  rm -rf "${BUSYBOX_REAL_SRC}"
  mkdir -p "${BUSYBOX_REAL_SRC}"
  tar -xjf "${BUSYBOX_REAL_TARBALL}" -C "${BUSYBOX_REAL_SRC}" --strip-components=1
fi

# Build Lua unchanged from upstream source. Use the generic/POSIX path so the
# validation binary has no readline/dlopen dependency and remains fully static.
LUA_BUILD="${BUILD_DIR}/lua-src"
rm -rf "${LUA_BUILD}"
cp -a "${LUA_SRC}" "${LUA_BUILD}"
make -C "${LUA_BUILD}/src" clean >/dev/null 2>&1 || true
make -C "${LUA_BUILD}/src" -j"${JOBS}" \
  CC="${MUSL_CC}" AR="${AR} rcu" RANLIB="${RANLIB}" \
  MYCFLAGS="-Os -DLUA_USE_POSIX" MYLDFLAGS="-static" MYLIBS="-lm" all \
  2>&1 | tee "${BUILD_DIR}/lua-build.log"
cp "${LUA_BUILD}/src/lua" "${BUILD_DIR}/lua"
check_elf "${BUILD_DIR}/lua" > "${EVIDENCE_DIR}/lua-readelf.txt"

# SQLite's upstream amalgamation plus a deterministic transaction/VFS harness.
"${MUSL_CC}" -Os -static \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_MEMSTATUS=0 \
  -I"${SQLITE_SRC}" "${SQLITE_SRC}/sqlite3.c" "${ROOT_DIR}/software/l32_real/sqlite-smoke.c" \
  -lm -o "${BUILD_DIR}/sqlite-smoke" 2>&1 | tee "${BUILD_DIR}/sqlite-build.log"
check_elf "${BUILD_DIR}/sqlite-smoke" > "${EVIDENCE_DIR}/sqlite-readelf.txt"

# Bash is deliberately a separate real-program workload. Keep upstream sources
# unchanged and solve cross-build-only compatibility at the generated build
# boundary. -fcommon preserves traditional readline/termcap tentative globals.
BASH_BUILD="${BUILD_DIR}/bash-src"
rm -rf "${BASH_BUILD}"
cp -a "${BASH_SRC}" "${BASH_BUILD}"
(
  cd "${BASH_BUILD}"
  build_triplet="$(sh support/config.guess)"
  env CC="${MUSL_CC}" \
    bash_cv_getcwd_malloc=yes bash_cv_func_sigsetjmp=present bash_cv_printf_a_format=yes \
    ./configure --build="${build_triplet}" --host=riscv32-linux-musl \
      --disable-nls --without-bash-malloc --without-installed-readline --enable-static-link \
      CFLAGS='-Os -fcommon' LDFLAGS='-static'
  sed -i -E 's/(^|[[:space:]])-rdynamic([[:space:]]|$)/ /g' Makefile
  if grep -Eq '(^|[[:space:]])-rdynamic([[:space:]]|$)' Makefile; then
    echo "ERROR: generated Bash target Makefile still contains -rdynamic" >&2
    exit 32
  fi
  make -j"${JOBS}" bash
) 2>&1 | tee "${BUILD_DIR}/bash-build.log"
cp "${BASH_BUILD}/bash" "${BUILD_DIR}/bash"
check_elf "${BUILD_DIR}/bash" > "${EVIDENCE_DIR}/bash-readelf.txt"

# Build a second, workload-only BusyBox from the same frozen upstream release.
# /bin/busybox remains the tiny PID1/shell bootstrap. This independent binary
# intentionally exercises larger unchanged editor/archive/text-processing paths
# without changing the boot contract.
BUSYBOX_REAL_BUILD="${BUILD_DIR}/busybox-real-src"
rm -rf "${BUSYBOX_REAL_BUILD}"
cp -a "${BUSYBOX_REAL_SRC}" "${BUSYBOX_REAL_BUILD}"
(
  cd "${BUSYBOX_REAL_BUILD}"
  make ARCH=riscv allnoconfig
  python3 - <<'PY'
from pathlib import Path
path = Path('.config')
lines = path.read_text().splitlines()
def set_symbol(symbol: str, enabled: bool = True) -> None:
    yes = f"{symbol}=y"
    no = f"# {symbol} is not set"
    for i, line in enumerate(lines):
        if line.startswith(f"{symbol}=") or line == no:
            lines[i] = yes if enabled else no
            return
    lines.append(yes if enabled else no)
for symbol in (
    'CONFIG_STATIC', 'CONFIG_LFS',
    'CONFIG_AWK', 'CONFIG_GZIP', 'CONFIG_GUNZIP', 'CONFIG_TAR',
    'CONFIG_ED', 'CONFIG_VI', 'CONFIG_FEATURE_VI_COLON',
    'CONFIG_CAT', 'CONFIG_CMP', 'CONFIG_MKDIR', 'CONFIG_RM',
):
    set_symbol(symbol)
path.write_text('\n'.join(lines) + '\n')
PY
  make ARCH=riscv oldconfig </dev/null
  for symbol in CONFIG_STATIC CONFIG_AWK CONFIG_GZIP CONFIG_GUNZIP CONFIG_TAR CONFIG_ED CONFIG_VI CONFIG_FEATURE_VI_COLON CONFIG_CAT CONFIG_CMP CONFIG_MKDIR CONFIG_RM; do
    grep -qx "${symbol}=y" .config || { echo "ERROR: workload BusyBox lost ${symbol}" >&2; exit 33; }
  done
  make ARCH=riscv CROSS_COMPILE="${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
    CC="${MUSL_CC}" HOSTCC="${HOSTCC:-cc}" -j"${JOBS}" busybox
) 2>&1 | tee "${BUILD_DIR}/busybox-real-build.log"
cp "${BUSYBOX_REAL_BUILD}/busybox" "${BUILD_DIR}/busybox-real"
check_elf "${BUILD_DIR}/busybox-real" > "${EVIDENCE_DIR}/busybox-real-readelf.txt"

cp "${ROOT_DIR}/software/l32_real/lua-smoke.lua" "${BUILD_DIR}/lua-smoke.lua"
cp "${ROOT_DIR}/software/l32_real/bash-smoke.sh" "${BUILD_DIR}/bash-smoke.sh"
chmod 0755 "${BUILD_DIR}/bash-smoke.sh"

sha256sum \
  "${LUA_TARBALL}" "${SQLITE_ZIP}" "${BASH_TARBALL}" "${BUSYBOX_REAL_TARBALL}" \
  "${BUILD_DIR}/lua" "${BUILD_DIR}/sqlite-smoke" "${BUILD_DIR}/bash" "${BUILD_DIR}/busybox-real" \
  "${BUILD_DIR}/lua-smoke.lua" "${BUILD_DIR}/bash-smoke.sh" \
  | tee "${EVIDENCE_DIR}/sha256.txt"

{
  echo "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS"
  echo "lua_version=${LUA_VERSION}"
  echo "sqlite_version=${SQLITE_VERSION}"
  echo "bash_version=${BASH_VERSION}"
  echo "busybox_real_version=${BUSYBOX_VERSION}"
  echo "lua=${BUILD_DIR}/lua"
  echo "sqlite_smoke=${BUILD_DIR}/sqlite-smoke"
  echo "bash=${BUILD_DIR}/bash"
  echo "busybox_real=${BUILD_DIR}/busybox-real"
} | tee "${BUILD_DIR}/result.txt"
