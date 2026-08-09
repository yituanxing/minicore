#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32_busybox/manifest.env"

BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-busybox"
BUILD_DIR="${ROOT_DIR}/build/l32-runtime-probe"
SOURCE="${ROOT_DIR}/software/l32_busybox/runtime_probe.c"
REAL_GCC="${BUSYBOX_BUILD_DIR}/l32-musl-real-gcc"
OUTPUT="${BUILD_DIR}/l32-runtime-probe"
READELF="${L32_USERSPACE_CROSS_COMPILE_PREFIX}readelf"

mkdir -p "${BUILD_DIR}"

[[ -s "${SOURCE}" ]] || { echo "ERROR: missing L32 runtime probe source" >&2; exit 20; }
[[ -x "${REAL_GCC}" ]] || { echo "ERROR: qualified musl link wrapper is missing" >&2; exit 21; }
grep -qx 'L32_BUSYBOX_BUILD_RESULT: status=PASS' "${BUSYBOX_BUILD_DIR}/result.txt" || {
  echo "ERROR: qualified L32 musl/BusyBox build is required first" >&2
  exit 21
}
command -v "${READELF}" >/dev/null 2>&1 || { echo "ERROR: missing ${READELF}" >&2; exit 22; }

GITHUB_WORKSPACE="${ROOT_DIR}" "${REAL_GCC}" \
  -Os -pipe -fno-pie -no-pie \
  -Wall -Wextra -Werror \
  "${SOURCE}" -o "${OUTPUT}"

[[ -s "${OUTPUT}" ]] || { echo "ERROR: runtime probe was not produced" >&2; exit 23; }
"${READELF}" -h -l -A "${OUTPUT}" | tee "${BUILD_DIR}/readelf.txt"
file "${OUTPUT}" | tee "${BUILD_DIR}/file.txt"
file "${OUTPUT}" | grep -q 'statically linked'

python3 - "${OUTPUT}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
if len(data) < 40 or data[:4] != b'\x7fELF':
    raise SystemExit(f"ERROR: not ELF: {path}")
if data[4] != 1 or data[5] != 1:
    raise SystemExit(f"ERROR: expected ELF32 little-endian: {path}")
if int.from_bytes(data[18:20], 'little') != 243:
    raise SystemExit(f"ERROR: expected EM_RISCV: {path}")
flags = int.from_bytes(data[36:40], 'little')
if flags & 0x6:
    raise SystemExit(f"ERROR: runtime probe unexpectedly uses hard/soft FP ABI flags: 0x{flags:x}")
if flags & 0x1:
    raise SystemExit(f"ERROR: runtime probe unexpectedly uses RVC: 0x{flags:x}")
print(f"L32_RUNTIME_PROBE_ABI_OK e_flags=0x{flags:x}")
PY

{
  echo "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS"
  echo "probe=${OUTPUT}"
  echo "probe_sha256=$(sha256sum "${OUTPUT}" | awk '{print $1}')"
  echo "source_sha256=$(sha256sum "${SOURCE}" | awk '{print $1}')"
} | tee "${BUILD_DIR}/result.txt"
