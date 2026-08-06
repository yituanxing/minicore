#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-n1"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
ARCHIVE_DIR="${CACHE_ROOT}/archives"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"

source "${MANIFEST}"
chmod +x "${ROOT_DIR}/tools/ci/kconfig-tweak"

for command in curl tar make python3 riscv64-unknown-elf-gcc \
  riscv64-unknown-elf-readelf riscv64-unknown-elf-size sha256sum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N1 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done

if [[ ! -x "${KCONFIGLIB_DIR}/bin/menuconfig" || \
      ! -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]]; then
  temporary="${KCONFIGLIB_DIR}.tmp.$$"
  mkdir -p "$(dirname "${KCONFIGLIB_DIR}")"
  rm -rf "${temporary}"
  echo "N1: install pinned kconfiglib ${KCONFIGLIB_VERSION} into persistent cache"
  python3 -m pip install \
    --disable-pip-version-check \
    --no-input \
    --no-deps \
    --target "${temporary}" \
    "kconfiglib==${KCONFIGLIB_VERSION}"
  [[ -x "${temporary}/bin/menuconfig" && \
     -x "${temporary}/bin/olddefconfig" ]] || {
    echo "N1 FAIL: pinned kconfiglib did not provide required frontends" >&2
    rm -rf "${temporary}"
    exit 2
  }
  rm -rf "${KCONFIGLIB_DIR}"
  mv "${temporary}" "${KCONFIGLIB_DIR}"
else
  echo "N1: reuse cached kconfiglib ${KCONFIGLIB_VERSION}"
fi

export PATH="${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence" "${ARCHIVE_DIR}" "${SOURCE_DIR}"

{
  echo "NUTTX_VERSION=${NUTTX_VERSION}"
  echo "NUTTX_TAG=${NUTTX_TAG}"
  echo "NUTTX_COMMIT=${NUTTX_COMMIT}"
  echo "NUTTX_APPS_COMMIT=${NUTTX_APPS_COMMIT}"
  echo "NUTTX_BASE_CONFIG=${NUTTX_BASE_CONFIG}"
  echo "NUTTX_PROFILE=${NUTTX_PROFILE}"
  echo "KCONFIGLIB_VERSION=${KCONFIGLIB_VERSION}"
  echo "CROSSDEV=riscv64-unknown-elf-"
  riscv64-unknown-elf-gcc --version | head -n 1
} | tee "${OUT_DIR}/evidence/manifest.txt"

prepare_source_archive() {
  local name="$1"
  local slug="$2"
  local commit="$3"
  local destination="$4"
  local archive="${ARCHIVE_DIR}/${name}-${commit}.tar.gz"
  local temporary="${archive}.tmp"
  local url="https://codeload.github.com/${slug}/tar.gz/${commit}"

  if [[ -f "${archive}" ]] && ! tar -tzf "${archive}" >/dev/null 2>&1; then
    echo "N1: dropping corrupt cached archive ${archive}" | tee -a "${OUT_DIR}/evidence/source-fetch.log"
    rm -f "${archive}"
  fi

  if [[ ! -f "${archive}" ]]; then
    local fetched=0
    for attempt in 1 2 3 4 5 6; do
      local partial_bytes=0
      [[ -f "${temporary}" ]] && partial_bytes="$(stat -c %s "${temporary}")"
      echo "N1: fetch ${name} attempt ${attempt}/6 from byte ${partial_bytes} over HTTP/1.1" \
        | tee -a "${OUT_DIR}/evidence/source-fetch.log"

      if curl --fail --location --show-error --silent \
          --http1.1 --continue-at - \
          --connect-timeout 20 --max-time 360 \
          --speed-time 60 --speed-limit 1024 \
          --output "${temporary}" "${url}"; then
        if tar -tzf "${temporary}" >/dev/null 2>&1; then
          mv "${temporary}" "${archive}"
          fetched=1
          break
        fi
        echo "N1: ${name} transfer completed but archive is incomplete; retaining bytes for resume" \
          | tee -a "${OUT_DIR}/evidence/source-fetch.log"
      fi
      sleep $((attempt * 2))
    done
    if [[ "${fetched}" -ne 1 ]]; then
      echo "N1 FAIL: unable to fetch valid ${name} archive after resumable HTTP/1.1 attempts" >&2
      exit 3
    fi
  else
    echo "N1: reuse cached ${archive}" | tee -a "${OUT_DIR}/evidence/source-fetch.log"
  fi

  local staging="${destination}.extract.$$"
  rm -rf "${staging}" "${destination}"
  mkdir -p "${staging}"
  tar -xzf "${archive}" --strip-components=1 -C "${staging}"
  mv "${staging}" "${destination}"
  sha256sum "${archive}" >> "${OUT_DIR}/evidence/source-archives.sha256"
}

set_bool_config() {
  local config_file="$1"
  local symbol="$2"
  local value="$3"

  python3 - "${config_file}" "${symbol}" "${value}" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
symbol = sys.argv[2]
value = sys.argv[3]
lines = path.read_text().splitlines()
pattern = re.compile(rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$")
replacement = f"{symbol}=y" if value == "y" else f"# {symbol} is not set"
filtered = [line for line in lines if not pattern.match(line)]
filtered.append(replacement)
path.write_text("\n".join(filtered) + "\n")
PY
}

NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}"
prepare_source_archive nuttx apache/nuttx "${NUTTX_COMMIT}" "${NUTTX_DIR}"
prepare_source_archive nuttx-apps apache/nuttx-apps "${NUTTX_APPS_COMMIT}" "${APPS_DIR}"

pushd "${NUTTX_DIR}" >/dev/null
./tools/configure.sh -E -l -a "../$(basename "${APPS_DIR}")" "${NUTTX_BASE_CONFIG}"

set_bool_config .config CONFIG_ARCH_CHIP_QEMU_RV_ISA_M y
set_bool_config .config CONFIG_ARCH_CHIP_QEMU_RV_ISA_A n
set_bool_config .config CONFIG_ARCH_CHIP_QEMU_RV_ISA_C n
set_bool_config .config CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI y
set_bool_config .config CONFIG_ARCH_RV_ISA_V n
set_bool_config .config CONFIG_ARCH_FPU n
set_bool_config .config CONFIG_ARCH_DPFPU n
set_bool_config .config CONFIG_ARCH_QPFPU n
set_bool_config .config CONFIG_FS_HOSTFS n
set_bool_config .config CONFIG_RISCV_SEMIHOSTING_HOSTFS n
set_bool_config .config CONFIG_RISCV_TOOLCHAIN_GNU_RV64 y
set_bool_config .config CONFIG_RISCV_TOOLCHAIN_GNU_RV32 n
set_bool_config .config CONFIG_RISCV_TOOLCHAIN_CLANG n

make olddefconfig CROSSDEV=riscv64-unknown-elf-

required_lines=(
  'CONFIG_ARCH_CHIP_QEMU_RV_ISA_M=y'
  '# CONFIG_ARCH_CHIP_QEMU_RV_ISA_A is not set'
  '# CONFIG_ARCH_CHIP_QEMU_RV_ISA_C is not set'
  'CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI=y'
  '# CONFIG_ARCH_RV_ISA_V is not set'
  '# CONFIG_FS_HOSTFS is not set'
  '# CONFIG_RISCV_SEMIHOSTING_HOSTFS is not set'
  'CONFIG_RISCV_TOOLCHAIN_GNU_RV64=y'
)
for line in "${required_lines[@]}"; do
  grep -Fqx "${line}" .config || {
    echo "N1 FAIL: resolved configuration is missing: ${line}" >&2
    exit 4
  }
done

cp .config "${OUT_DIR}/nuttx.config"
JOBS="${NUTTX_JOBS:-$(nproc)}"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- 2>&1 | tee "${OUT_DIR}/build.log"

[[ -s nuttx ]] || {
  echo "N1 FAIL: NuttX ELF was not produced" >&2
  exit 5
}

cp nuttx "${OUT_DIR}/nuttx.elf"
for optional in nuttx.bin nuttx.hex nuttx.map; do
  [[ -f "${optional}" ]] && cp "${optional}" "${OUT_DIR}/${optional}"
done

riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/elf-header.txt"
riscv64-unknown-elf-readelf -A nuttx > "${OUT_DIR}/evidence/elf-attributes.txt"
riscv64-unknown-elf-readelf -S nuttx > "${OUT_DIR}/evidence/elf-sections.txt"
riscv64-unknown-elf-size -A nuttx > "${OUT_DIR}/evidence/elf-size.txt"
sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx.config" \
  > "${OUT_DIR}/evidence/sha256.txt"

python3 - "${OUT_DIR}/evidence/elf-attributes.txt" <<'PY'
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text().lower()
match = re.search(r'tag_riscv_arch:\s*"([^"]+)"', text)
if not match:
    raise SystemExit("N1 FAIL: ELF has no Tag_RISCV_arch attribute")
arch = match.group(1)
if not arch.startswith("rv32i") or "_zicsr" not in arch or "_zifencei" not in arch:
    raise SystemExit(f"N1 FAIL: unexpected ELF ISA attribute: {arch}")
base = arch.split("_", 1)[0]
if "m" not in base:
    raise SystemExit(f"N1 FAIL: M extension missing from ELF ISA attribute: {arch}")
if "a" in base or "c" in base:
    raise SystemExit(f"N1 FAIL: forbidden A/C extension present: {arch}")
print(f"N1 ISA PASS: {arch}")
PY

popd >/dev/null

echo "N1 PASS: pinned NuttX ${NUTTX_VERSION} RV32IM build completed"
