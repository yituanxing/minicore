#!/usr/bin/env bash
set -euo pipefail

CACHE_ROOT="${1:?usage: ensure_genromfs.sh CACHE_ROOT}"
GENROMFS_COMMIT="e4225b49a7be0ae9d39e98f2175dd674c0d6b1ea"
TOOL_ROOT="${CACHE_ROOT}/host-tools/genromfs-${GENROMFS_COMMIT}"
TOOL_BIN="${TOOL_ROOT}/bin/genromfs"
ARCHIVE_DIR="${CACHE_ROOT}/archives"
ARCHIVE="${ARCHIVE_DIR}/genromfs-${GENROMFS_COMMIT}.tar.gz"
URL="https://codeload.github.com/chexum/genromfs/tar.gz/${GENROMFS_COMMIT}"

if [[ -x "${TOOL_BIN}" ]]; then
  echo "N1: reuse cached genromfs 0.5.7 (${GENROMFS_COMMIT})" >&2
  printf '%s\n' "${TOOL_BIN}"
  exit 0
fi

for command in curl tar make gcc; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N1 FAIL: required genromfs bootstrap command not found: ${command}" >&2
    exit 2
  }
done

mkdir -p "${ARCHIVE_DIR}" "$(dirname "${TOOL_ROOT}")"
if [[ -f "${ARCHIVE}" ]] && ! tar -tzf "${ARCHIVE}" >/dev/null 2>&1; then
  echo "N1: drop corrupt cached genromfs archive" >&2
  rm -f "${ARCHIVE}"
fi

if [[ ! -f "${ARCHIVE}" ]]; then
  partial="${ARCHIVE}.tmp"
  fetched=0
  for attempt in 1 2 3 4 5 6; do
    bytes=0
    [[ -f "${partial}" ]] && bytes="$(stat -c %s "${partial}")"
    echo "N1: fetch pinned genromfs attempt ${attempt}/6 from byte ${bytes}" >&2
    if curl --fail --location --show-error --silent \
        --http1.1 --continue-at - \
        --connect-timeout 20 --max-time 180 \
        --speed-time 60 --speed-limit 1024 \
        --output "${partial}" "${URL}" && \
       tar -tzf "${partial}" >/dev/null 2>&1; then
      mv "${partial}" "${ARCHIVE}"
      fetched=1
      break
    fi
    sleep $((attempt * 2))
  done
  if [[ "${fetched}" -ne 1 ]]; then
    echo "N1 FAIL: unable to fetch pinned genromfs source" >&2
    exit 3
  fi
fi

work="${TOOL_ROOT}.build.$$"
install_root="${TOOL_ROOT}.install.$$"
rm -rf "${work}" "${install_root}"
mkdir -p "${work}" "${install_root}/bin"
tar -xzf "${ARCHIVE}" --strip-components=1 -C "${work}"
make -C "${work}" -j2
install -m 0755 "${work}/genromfs" "${install_root}/bin/genromfs"

[[ -x "${install_root}/bin/genromfs" ]] || {
  echo "N1 FAIL: genromfs bootstrap did not produce an executable" >&2
  rm -rf "${work}" "${install_root}"
  exit 4
}

rm -rf "${TOOL_ROOT}"
mv "${install_root}" "${TOOL_ROOT}"
rm -rf "${work}"
echo "N1: cached pinned genromfs 0.5.7 (${GENROMFS_COMMIT})" >&2
printf '%s\n' "${TOOL_BIN}"
