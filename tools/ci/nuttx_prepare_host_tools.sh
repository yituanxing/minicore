#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"

for command in bash python3; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "P0 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done

if [[ ! -x "${KCONFIGLIB_DIR}/bin/menuconfig" || \
      ! -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]]; then
  temporary="${KCONFIGLIB_DIR}.tmp.$$"
  mkdir -p "$(dirname "${KCONFIGLIB_DIR}")"
  rm -rf "${temporary}"
  echo "P0: install pinned kconfiglib ${KCONFIGLIB_VERSION}"
  python3 -m pip install \
    --disable-pip-version-check \
    --no-input \
    --no-deps \
    --target "${temporary}" \
    "kconfiglib==${KCONFIGLIB_VERSION}"
  [[ -x "${temporary}/bin/menuconfig" && \
     -x "${temporary}/bin/olddefconfig" ]] || {
    echo "P0 FAIL: pinned kconfiglib did not provide required frontends" >&2
    rm -rf "${temporary}"
    exit 3
  }
  rm -rf "${KCONFIGLIB_DIR}"
  mv "${temporary}" "${KCONFIGLIB_DIR}"
else
  echo "P0: reuse cached kconfiglib ${KCONFIGLIB_VERSION}"
fi

GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
[[ -x "${GENROMFS_BIN}" ]] || {
  echo "P0 FAIL: genromfs was not prepared" >&2
  exit 3
}

cat <<EOF
P0 PASS: protected NuttX host tools are ready
kconfiglib=${KCONFIGLIB_DIR}
genromfs=${GENROMFS_BIN}
EOF
