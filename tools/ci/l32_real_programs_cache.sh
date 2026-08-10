#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/l32-real-programs"
MARKER="${BUILD_DIR}/software-cache.txt"
RESULT="${BUILD_DIR}/result.txt"

inputs=(
  "${ROOT_DIR}/software/l32_real/manifest.env"
  "${ROOT_DIR}/software/l32_real/lua-smoke.lua"
  "${ROOT_DIR}/software/l32_real/sqlite-smoke.c"
  "${ROOT_DIR}/software/l32_real/bash-smoke.sh"
  "${ROOT_DIR}/tools/ci/l32_real_programs_build.sh"
)
outputs=(
  "${BUILD_DIR}/lua"
  "${BUILD_DIR}/lua-smoke.lua"
  "${BUILD_DIR}/sqlite-smoke"
  "${BUILD_DIR}/bash"
  "${BUILD_DIR}/bash-smoke.sh"
  "${BUILD_DIR}/busybox-real"
)

hash_or_missing() {
  if [[ -f "$1" ]]; then sha256sum "$1"; else printf 'missing  %s\n' "$1"; fi
}

key="$({
  for f in "${inputs[@]}"; do hash_or_missing "$f"; done
  hash_or_missing "${ROOT_DIR}/build/l32-busybox/l32-musl-real-gcc"
  hash_or_missing "${ROOT_DIR}/build/l32-busybox/musl-prefix/lib/libc.a"
} | sha256sum | awk '{print $1}')"

hit=1
[[ -f "${RESULT}" ]] && grep -qx 'L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS' "${RESULT}" || hit=0
[[ -f "${MARKER}" ]] && [[ "$(awk '$1=="input_key" {print $2; exit}' "${MARKER}" 2>/dev/null)" == "${key}" ]] || hit=0
if (( hit )); then
  for f in "${outputs[@]}"; do
    rel="${f#${ROOT_DIR}/}"
    expected="$(awk -v p="${rel}" '$1=="sha256" && $3==p {print $2; exit}' "${MARKER}")"
    [[ -n "${expected}" && -s "${f}" ]] || { hit=0; break; }
    [[ "$(sha256sum "${f}" | awk '{print $1}')" == "${expected}" ]] || { hit=0; break; }
  done
fi

if (( hit )); then
  echo "L32_REAL_PROGRAMS_CACHE_HIT key=${key}"
  exit 0
fi

echo "L32_REAL_PROGRAMS_CACHE_MISS key=${key}"
"${ROOT_DIR}/tools/ci/l32_real_programs_build.sh"
grep -qx 'L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS' "${RESULT}"
for f in "${outputs[@]}"; do [[ -s "${f}" ]]; done

mkdir -p "${BUILD_DIR}"
tmp="${MARKER}.tmp.$$"
{
  echo "input_key ${key}"
  for f in "${outputs[@]}"; do
    echo "sha256 $(sha256sum "${f}" | awk '{print $1}') ${f#${ROOT_DIR}/}"
  done
} > "${tmp}"
mv "${tmp}" "${MARKER}"
echo "L32_REAL_PROGRAMS_CACHE_MARK key=${key} outputs=${#outputs[@]}"
