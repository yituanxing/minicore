#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"
source "${ROOT_DIR}/software/l32_real/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/real-programs"
DOWNLOAD_DIR="${CACHE_ROOT}/downloads"
SOURCE_DIR="${CACHE_ROOT}/sources"
BUILD_DIR="${ROOT_DIR}/build/l32-real-programs"
BUSYBOX_REAL_BUILD="${BUILD_DIR}/busybox-real-src"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
MUSL_CC="${ROOT_DIR}/build/l32-busybox/l32-musl-real-gcc"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"
AR="${L32_USERSPACE_CROSS_COMPILE_PREFIX}ar"
RANLIB="${L32_USERSPACE_CROSS_COMPILE_PREFIX}ranlib"
JOBS="${L32_REAL_JOBS:-$(nproc)}"

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

ensure_environment() {
  mkdir -p "${DOWNLOAD_DIR}" "${SOURCE_DIR}" "${BUILD_DIR}" "${EVIDENCE_DIR}"
  for tool in curl tar sha256sum file make python3 "${READELF}" "${AR}" "${RANLIB}"; do
    command -v "${tool}" >/dev/null 2>&1 || { echo "ERROR: missing real-program build tool: ${tool}" >&2; exit 20; }
  done
  [[ -x "${MUSL_CC}" ]] || { echo "ERROR: qualified L32 musl compiler wrapper is missing" >&2; exit 21; }
  grep -qx 'L32_BUSYBOX_BUILD_RESULT: status=PASS' "${ROOT_DIR}/build/l32-busybox/result.txt" || {
    echo "ERROR: qualified L32 musl/BusyBox build is required first" >&2
    exit 21
  }
}

build_lua() {
  local tarball="${DOWNLOAD_DIR}/lua-${LUA_VERSION}.tar.gz"
  local src="${SOURCE_DIR}/lua-${LUA_VERSION}"
  local build="${BUILD_DIR}/lua-src"
  fetch_verified "${LUA_ARCHIVE}" "${LUA_SHA256}" "${tarball}"
  if [[ ! -f "${src}/src/lua.c" ]]; then
    rm -rf "${src}"
    tar -xzf "${tarball}" -C "${SOURCE_DIR}"
  fi
  rm -rf "${build}"
  cp -a "${src}" "${build}"
  make -C "${build}/src" clean >/dev/null 2>&1 || true
  make -C "${build}/src" -j"${JOBS}" \
    CC="${MUSL_CC}" AR="${AR} rcu" RANLIB="${RANLIB}" \
    MYCFLAGS="-Os -DLUA_USE_POSIX" MYLDFLAGS="-static" MYLIBS="-lm" all \
    2>&1 | tee "${BUILD_DIR}/lua-build.log"
  cp "${build}/src/lua" "${BUILD_DIR}/lua"
  cp "${ROOT_DIR}/software/l32_real/lua-smoke.lua" "${BUILD_DIR}/lua-smoke.lua"
  check_elf "${BUILD_DIR}/lua" > "${EVIDENCE_DIR}/lua-readelf.txt"
}

build_sqlite() {
  local archive="${DOWNLOAD_DIR}/sqlite-amalgamation-${SQLITE_AMALGAMATION_ID}.zip"
  local src="${SOURCE_DIR}/sqlite-amalgamation-${SQLITE_AMALGAMATION_ID}"
  fetch_verified "${SQLITE_ARCHIVE}" "${SQLITE_SHA256}" "${archive}"
  if [[ ! -f "${src}/sqlite3.c" ]]; then
    rm -rf "${src}"
    python3 - "${archive}" "${SOURCE_DIR}" <<'PY'
from pathlib import Path
import sys, zipfile
archive = Path(sys.argv[1])
out = Path(sys.argv[2])
with zipfile.ZipFile(archive) as zf:
    zf.extractall(out)
PY
  fi
  "${MUSL_CC}" -Os -static \
    -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_MEMSTATUS=0 \
    -I"${src}" "${src}/sqlite3.c" "${ROOT_DIR}/software/l32_real/sqlite-smoke.c" \
    -lm -o "${BUILD_DIR}/sqlite-smoke" 2>&1 | tee "${BUILD_DIR}/sqlite-build.log"
  check_elf "${BUILD_DIR}/sqlite-smoke" > "${EVIDENCE_DIR}/sqlite-readelf.txt"
}

build_bash() {
  local tarball="${DOWNLOAD_DIR}/bash-${BASH_VERSION}.tar.gz"
  local src="${SOURCE_DIR}/bash-${BASH_VERSION}"
  local build="${BUILD_DIR}/bash-src"
  fetch_verified "${BASH_ARCHIVE}" "${BASH_SHA256}" "${tarball}"
  if [[ ! -f "${src}/configure" ]]; then
    rm -rf "${src}"
    tar -xzf "${tarball}" -C "${SOURCE_DIR}"
  fi
  rm -rf "${build}"
  cp -a "${src}" "${build}"
  (
    cd "${build}"
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
  cp "${build}/bash" "${BUILD_DIR}/bash"
  cp "${ROOT_DIR}/software/l32_real/bash-smoke.sh" "${BUILD_DIR}/bash-smoke.sh"
  chmod 0755 "${BUILD_DIR}/bash-smoke.sh"
  check_elf "${BUILD_DIR}/bash" > "${EVIDENCE_DIR}/bash-readelf.txt"
}

build_busybox() {
  local tarball="${DOWNLOAD_DIR}/busybox-${BUSYBOX_VERSION}.tar.bz2"
  local src="${SOURCE_DIR}/busybox-${BUSYBOX_VERSION}-real"
  local build="${BUSYBOX_REAL_BUILD}"
  fetch_verified "${BUSYBOX_ARCHIVE}" "${BUSYBOX_SHA256}" "${tarball}"
  if [[ ! -f "${src}/Makefile" ]]; then
    rm -rf "${src}"
    mkdir -p "${src}"
    tar -xjf "${tarball}" -C "${src}" --strip-components=1
  fi
  rm -rf "${build}"
  cp -a "${src}" "${build}"
  (
    cd "${build}"
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

def set_value(symbol: str, value: str) -> None:
    no = f"# {symbol} is not set"
    wanted = f"{symbol}={value}"
    for i, line in enumerate(lines):
        if line.startswith(f"{symbol}=") or line == no:
            lines[i] = wanted
            return
    lines.append(wanted)

for symbol in (
    'CONFIG_STATIC', 'CONFIG_LFS',
    'CONFIG_AWK', 'CONFIG_GZIP', 'CONFIG_GUNZIP', 'CONFIG_TAR', 'CONFIG_FEATURE_TAR_CREATE',
    'CONFIG_ED', 'CONFIG_VI', 'CONFIG_FEATURE_VI_COLON', 'CONFIG_FEATURE_VI_SEARCH',
    'CONFIG_CAT', 'CONFIG_CMP', 'CONFIG_MKDIR', 'CONFIG_RM',
):
    set_symbol(symbol)
set_value('CONFIG_FEATURE_VI_MAX_LEN', '4096')
path.write_text('\n'.join(lines) + '\n')
PY
    make ARCH=riscv oldconfig </dev/null
    for symbol in CONFIG_STATIC CONFIG_AWK CONFIG_GZIP CONFIG_GUNZIP CONFIG_TAR CONFIG_FEATURE_TAR_CREATE CONFIG_ED CONFIG_VI CONFIG_FEATURE_VI_COLON CONFIG_FEATURE_VI_SEARCH CONFIG_CAT CONFIG_CMP CONFIG_MKDIR CONFIG_RM; do
      grep -qx "${symbol}=y" .config || { echo "ERROR: workload BusyBox lost ${symbol}" >&2; exit 33; }
    done
    grep -qx 'CONFIG_FEATURE_VI_MAX_LEN=4096' .config || {
      echo "ERROR: workload BusyBox lost CONFIG_FEATURE_VI_MAX_LEN=4096" >&2
      exit 33
    }
    make ARCH=riscv CROSS_COMPILE="${L32_USERSPACE_CROSS_COMPILE_PREFIX}" \
      CC="${MUSL_CC}" HOSTCC="${HOSTCC:-cc}" -j"${JOBS}" busybox
  ) 2>&1 | tee "${BUILD_DIR}/busybox-real-build.log"
  cp "${BUSYBOX_REAL_BUILD}/busybox" "${BUILD_DIR}/busybox-real"
  check_elf "${BUILD_DIR}/busybox-real" > "${EVIDENCE_DIR}/busybox-real-readelf.txt"
}

build_zlib() {
  local tarball="${DOWNLOAD_DIR}/zlib-${ZLIB_VERSION}.tar.gz"
  local src="${SOURCE_DIR}/zlib-${ZLIB_VERSION}"
  local build="${BUILD_DIR}/zlib-src"
  fetch_verified "${ZLIB_ARCHIVE}" "${ZLIB_SHA256}" "${tarball}"
  if [[ ! -f "${src}/zlib.h" ]]; then
    rm -rf "${src}"
    tar -xzf "${tarball}" -C "${SOURCE_DIR}"
  fi
  rm -rf "${build}"
  cp -a "${src}" "${build}"
  (
    cd "${build}"
    env CC="${MUSL_CC}" AR="${AR}" RANLIB="${RANLIB}" CFLAGS='-Os' ./configure --static
    make -j"${JOBS}" libz.a
  ) 2>&1 | tee "${BUILD_DIR}/zlib-build.log"
  [[ -s "${build}/libz.a" ]] || { echo "ERROR: zlib build did not produce libz.a" >&2; exit 34; }
  "${MUSL_CC}" -Os -static \
    -I"${build}" \
    "${ROOT_DIR}/software/l32_real/zlib-smoke.c" \
    "${build}/libz.a" \
    -o "${BUILD_DIR}/zlib-smoke" \
    2>&1 | tee "${BUILD_DIR}/zlib-smoke-build.log"
  check_elf "${BUILD_DIR}/zlib-smoke" > "${EVIDENCE_DIR}/zlib-readelf.txt"
}

recipe_hash() {
  local component="$1"
  {
    declare -f fetch_verified
    declare -f check_elf
    case "${component}" in
      lua) declare -f build_lua ;;
      sqlite) declare -f build_sqlite ;;
      bash) declare -f build_bash ;;
      busybox) declare -f build_busybox ;;
      zlib) declare -f build_zlib ;;
      *) echo "ERROR: unknown real-program component: ${component}" >&2; return 2 ;;
    esac
  } | sha256sum | awk '{print $1}'
}

finalize() {
  for output in lua lua-smoke.lua sqlite-smoke bash bash-smoke.sh busybox-real zlib-smoke; do
    [[ -s "${BUILD_DIR}/${output}" ]] || { echo "ERROR: missing real-program output ${output}" >&2; exit 35; }
  done

  {
    printf 'lua %s %s\n' "${LUA_SHA256}" "${LUA_ARCHIVE}"
    printf 'sqlite %s %s\n' "${SQLITE_SHA256}" "${SQLITE_ARCHIVE}"
    printf 'bash %s %s\n' "${BASH_SHA256}" "${BASH_ARCHIVE}"
    printf 'busybox %s %s\n' "${BUSYBOX_SHA256}" "${BUSYBOX_ARCHIVE}"
    printf 'zlib %s %s\n' "${ZLIB_SHA256}" "${ZLIB_ARCHIVE}"
  } | tee "${EVIDENCE_DIR}/source-sha256.txt"

  sha256sum \
    "${BUILD_DIR}/lua" "${BUILD_DIR}/sqlite-smoke" "${BUILD_DIR}/bash" "${BUILD_DIR}/busybox-real" "${BUILD_DIR}/zlib-smoke" \
    "${BUILD_DIR}/lua-smoke.lua" "${BUILD_DIR}/bash-smoke.sh" "${ROOT_DIR}/software/l32_real/zlib-smoke.c" \
    | tee "${EVIDENCE_DIR}/sha256.txt"

  {
    echo "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS"
    echo "lua_version=${LUA_VERSION}"
    echo "sqlite_version=${SQLITE_VERSION}"
    echo "bash_version=${BASH_VERSION}"
    echo "busybox_real_version=${BUSYBOX_VERSION}"
    echo "zlib_version=${ZLIB_VERSION}"
    echo "lua=${BUILD_DIR}/lua"
    echo "sqlite_smoke=${BUILD_DIR}/sqlite-smoke"
    echo "bash=${BUILD_DIR}/bash"
    echo "busybox_real=${BUILD_DIR}/busybox-real"
    echo "zlib_smoke=${BUILD_DIR}/zlib-smoke"
  } | tee "${BUILD_DIR}/result.txt"
}

main() {
  local mode="${1:-all}"
  if [[ "${mode}" == "recipe-hash" ]]; then
    [[ $# -eq 2 ]] || { echo "usage: $0 recipe-hash lua|sqlite|bash|busybox|zlib" >&2; return 2; }
    recipe_hash "$2"
    return
  fi

  case "${mode}" in
    all|lua|sqlite|bash|busybox|zlib|finalize) ;;
    *) echo "usage: $0 [all|lua|sqlite|bash|busybox|zlib|finalize|recipe-hash COMPONENT]" >&2; return 2 ;;
  esac

  ensure_environment
  case "${mode}" in
    all)
      build_lua
      build_sqlite
      build_bash
      build_busybox
      build_zlib
      finalize
      ;;
    lua) build_lua ;;
    sqlite) build_sqlite ;;
    bash) build_bash ;;
    busybox) build_busybox ;;
    zlib) build_zlib ;;
    finalize) finalize ;;
  esac
}

main "$@"