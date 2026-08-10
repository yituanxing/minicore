#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"
source "${ROOT_DIR}/software/l32_real/manifest.env"

BUILD_DIR="${ROOT_DIR}/build/l32-real-programs"
COMPONENT_CACHE_DIR="${BUILD_DIR}/component-cache"
MARKER="${BUILD_DIR}/software-cache.txt"
RESULT="${BUILD_DIR}/result.txt"
BUILD_SCRIPT="${ROOT_DIR}/tools/ci/l32_real_programs_build.sh"
MUSL_WRAPPER="${ROOT_DIR}/build/l32-busybox/l32-musl-real-gcc"
MUSL_LIBC="${ROOT_DIR}/build/l32-busybox/musl-prefix/lib/libc.a"
components=(lua sqlite bash busybox zlib)

mkdir -p "${COMPONENT_CACHE_DIR}"

hash_or_missing() {
  if [[ -f "$1" ]]; then
    sha256sum "$1"
  else
    printf 'missing  %s\n' "$1"
  fi
}

component_outputs() {
  case "$1" in
    lua) printf '%s\n' "${BUILD_DIR}/lua" "${BUILD_DIR}/lua-smoke.lua" ;;
    sqlite) printf '%s\n' "${BUILD_DIR}/sqlite-smoke" ;;
    bash) printf '%s\n' "${BUILD_DIR}/bash" "${BUILD_DIR}/bash-smoke.sh" ;;
    busybox) printf '%s\n' "${BUILD_DIR}/busybox-real" ;;
    zlib) printf '%s\n' "${BUILD_DIR}/zlib-smoke" ;;
    *) echo "ERROR: unknown real-program component: $1" >&2; return 2 ;;
  esac
}

component_identity() {
  local component="$1"
  printf 'userspace_prefix=%s\n' "${L32_USERSPACE_CROSS_COMPILE_PREFIX}"
  printf 'userspace_isa=%s\n' "${L32_USERSPACE_ISA}"
  printf 'userspace_abi=%s\n' "${L32_USERSPACE_ABI}"
  printf 'musl_version=%s\n' "${MUSL_VERSION}"
  printf 'musl_sha256=%s\n' "${MUSL_SHA256}"
  hash_or_missing "${MUSL_WRAPPER}"
  hash_or_missing "${MUSL_LIBC}"
  printf 'recipe_hash=%s\n' "$("${BUILD_SCRIPT}" recipe-hash "${component}")"

  case "${component}" in
    lua)
      printf 'version=%s\narchive=%s\nsha256=%s\n' "${LUA_VERSION}" "${LUA_ARCHIVE}" "${LUA_SHA256}"
      hash_or_missing "${ROOT_DIR}/software/l32_real/lua-smoke.lua"
      ;;
    sqlite)
      printf 'version=%s\namalgamation=%s\narchive=%s\nsha256=%s\n' \
        "${SQLITE_VERSION}" "${SQLITE_AMALGAMATION_ID}" "${SQLITE_ARCHIVE}" "${SQLITE_SHA256}"
      hash_or_missing "${ROOT_DIR}/software/l32_real/sqlite-smoke.c"
      ;;
    bash)
      printf 'version=%s\narchive=%s\nsha256=%s\n' "${BASH_VERSION}" "${BASH_ARCHIVE}" "${BASH_SHA256}"
      hash_or_missing "${ROOT_DIR}/software/l32_real/bash-smoke.sh"
      ;;
    busybox)
      printf 'version=%s\narchive=%s\nsha256=%s\n' "${BUSYBOX_VERSION}" "${BUSYBOX_ARCHIVE}" "${BUSYBOX_SHA256}"
      ;;
    zlib)
      printf 'version=%s\narchive=%s\nsha256=%s\n' "${ZLIB_VERSION}" "${ZLIB_ARCHIVE}" "${ZLIB_SHA256}"
      hash_or_missing "${ROOT_DIR}/software/l32_real/zlib-smoke.c"
      ;;
  esac
}

component_key() {
  component_identity "$1" | sha256sum | awk '{print $1}'
}

component_hit() {
  local component="$1" key="$2" marker="${COMPONENT_CACHE_DIR}/${component}.txt"
  [[ -f "${marker}" ]] || return 1
  [[ "$(awk '$1=="input_key" {print $2; exit}' "${marker}" 2>/dev/null)" == "${key}" ]] || return 1
  while IFS= read -r output; do
    local rel expected actual
    rel="${output#${ROOT_DIR}/}"
    expected="$(awk -v p="${rel}" '$1=="sha256" && $3==p {print $2; exit}' "${marker}")"
    [[ -n "${expected}" && -s "${output}" ]] || return 1
    actual="$(sha256sum "${output}" | awk '{print $1}')"
    [[ "${actual}" == "${expected}" ]] || return 1
  done < <(component_outputs "${component}")
}

mark_component() {
  local component="$1" key="$2" marker="${COMPONENT_CACHE_DIR}/${component}.txt" tmp="${marker}.tmp.$$"
  {
    echo "input_key ${key}"
    echo "component ${component}"
    while IFS= read -r output; do
      [[ -s "${output}" ]] || { echo "ERROR: component ${component} did not produce ${output}" >&2; exit 40; }
      echo "sha256 $(sha256sum "${output}" | awk '{print $1}') ${output#${ROOT_DIR}/}"
    done < <(component_outputs "${component}")
  } > "${tmp}"
  mv "${tmp}" "${marker}"
  echo "L32_REAL_PROGRAM_COMPONENT_CACHE_MARK component=${component} key=${key}"
}

declare -A keys
all_hits=1
for component in "${components[@]}"; do
  key="$(component_key "${component}")"
  keys["${component}"]="${key}"
  if component_hit "${component}" "${key}"; then
    echo "L32_REAL_PROGRAM_COMPONENT_CACHE_HIT component=${component} key=${key}"
    continue
  fi

  all_hits=0
  echo "L32_REAL_PROGRAM_COMPONENT_CACHE_MISS component=${component} key=${key}"
  "${BUILD_SCRIPT}" "${component}"
  mark_component "${component}" "${key}"
done

"${BUILD_SCRIPT}" finalize
grep -qx 'L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS' "${RESULT}"

aggregate_key="$({ for component in "${components[@]}"; do printf '%s %s\n' "${component}" "${keys[${component}]}"; done; } | sha256sum | awk '{print $1}')"
tmp="${MARKER}.tmp.$$"
{
  echo "format component-v1"
  echo "input_key ${aggregate_key}"
  for component in "${components[@]}"; do
    echo "component ${component} ${keys[${component}]}"
  done
  for component in "${components[@]}"; do
    while IFS= read -r output; do
      echo "sha256 $(sha256sum "${output}" | awk '{print $1}') ${output#${ROOT_DIR}/}"
    done < <(component_outputs "${component}")
  done
} > "${tmp}"
mv "${tmp}" "${MARKER}"

if (( all_hits )); then
  echo "L32_REAL_PROGRAMS_CACHE_HIT key=${aggregate_key} components=${#components[@]}"
else
  echo "L32_REAL_PROGRAMS_CACHE_MARK key=${aggregate_key} components=${#components[@]}"
fi
