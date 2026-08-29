#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-sv32-paging-audit"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
ARCHIVE_DIR="${CACHE_ROOT}/archives"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
JOBS="${NUTTX_JOBS:-6}"

source "${MANIFEST}"
NUTTX_ARCHIVE="${ARCHIVE_DIR}/nuttx-${NUTTX_COMMIT}.tar.gz"
APPS_ARCHIVE="${ARCHIVE_DIR}/nuttx-apps-${NUTTX_APPS_COMMIT}.tar.gz"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}-sv32-paging"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}-sv32-paging"

for command in make python3 tar sha256sum grep sed awk \
  riscv64-unknown-elf-gcc riscv64-unknown-elf-readelf \
  riscv64-unknown-elf-objdump riscv64-unknown-elf-nm riscv64-unknown-elf-size; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N5-A FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -s "${NUTTX_ARCHIVE}" && -s "${APPS_ARCHIVE}" ]] || {
  echo "N5-A FAIL: pinned NuttX archives are missing" >&2
  exit 2
}
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "N5-A FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/tools/ci/kconfig-tweak"
GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence" "${SOURCE_DIR}"

extract_fresh() {
  local archive="$1"
  local destination="$2"
  local staging="${destination}.n5a.$$"
  rm -rf "${staging}" "${destination}"
  mkdir -p "${staging}"
  tar -xzf "${archive}" --strip-components=1 -C "${staging}"
  mv "${staging}" "${destination}"
}

extract_fresh "${NUTTX_ARCHIVE}" "${NUTTX_DIR}"
extract_fresh "${APPS_ARCHIVE}" "${APPS_DIR}"
sha256sum "${NUTTX_ARCHIVE}" "${APPS_ARCHIVE}" > "${OUT_DIR}/evidence/source-archives.sha256"

CONFIG_NAME=""
for candidate in knsh_paging knsh32_paging; do
  if [[ -f "${NUTTX_DIR}/boards/risc-v/qemu-rv/rv-virt/configs/${candidate}/defconfig" ]]; then
    CONFIG_NAME="${candidate}"
    break
  fi
done
[[ -n "${CONFIG_NAME}" ]] || {
  echo "N5-A FAIL: pinned NuttX ${NUTTX_VERSION} has neither rv-virt:knsh_paging nor rv-virt:knsh32_paging" >&2
  exit 3
}
echo "${CONFIG_NAME}" > "${OUT_DIR}/evidence/upstream-config-name.txt"
cp "${NUTTX_DIR}/boards/risc-v/qemu-rv/rv-virt/configs/${CONFIG_NAME}/defconfig" \
  "${OUT_DIR}/evidence/upstream-defconfig.txt"

pushd "${NUTTX_DIR}" >/dev/null
./tools/configure.sh -E -l -a "../$(basename "${APPS_DIR}")" "rv-virt:${CONFIG_NAME}" \
  2>&1 | tee "${OUT_DIR}/evidence/configure.log"
make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/evidence/olddefconfig.log"
cp .config "${OUT_DIR}/resolved.config"

python3 - .config "${OUT_DIR}/evidence/config-contract.txt" <<'PY'
from pathlib import Path
import sys

config = Path(sys.argv[1]).read_text().splitlines()
out = Path(sys.argv[2])
enabled = {line[:-2] for line in config if line.endswith("=y")}
values = {}
for line in config:
    if line.startswith("CONFIG_") and "=" in line:
        key, value = line.split("=", 1)
        values[key] = value

required = {
    "CONFIG_BUILD_KERNEL",
    "CONFIG_ARCH_ADDRENV",
    "CONFIG_ARCH_USE_MMU",
    "CONFIG_PAGING",
    "CONFIG_BINFMT_ELF_EXECUTABLE",
}
missing = sorted(required - enabled)
if missing:
    raise SystemExit("N5-A FAIL: upstream paging config lost required symbols: " + ", ".join(missing))

interesting = [
    "CONFIG_ARCH_CHIP_QEMU_RV32",
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_M",
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A",
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C",
    "CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI",
    "CONFIG_ARCH_USE_S_MODE",
    "CONFIG_ARCH_USE_MPU",
    "CONFIG_BUILD_KERNEL",
    "CONFIG_ARCH_ADDRENV",
    "CONFIG_ARCH_USE_MMU",
    "CONFIG_PAGING",
    "CONFIG_PAGING_BLOCKINGFILL",
    "CONFIG_ARCH_TEXT_VBASE",
    "CONFIG_ARCH_DATA_VBASE",
    "CONFIG_ARCH_HEAP_VBASE",
    "CONFIG_ARCH_TEXT_NPAGES",
    "CONFIG_ARCH_DATA_NPAGES",
    "CONFIG_ARCH_HEAP_NPAGES",
    "CONFIG_ARCH_PGPOOL_PBASE",
    "CONFIG_ARCH_PGPOOL_VBASE",
    "CONFIG_ARCH_PGPOOL_SIZE",
    "CONFIG_RAM_START",
    "CONFIG_RAM_SIZE",
    "CONFIG_POSIX_SPAWN_DEFAULT_STACKSIZE",
]
lines = ["contract=nuttx-13.0.0-rv-virt-sv32-paging-upstream-build-audit-v1"]
for key in interesting:
    if key in enabled:
        lines.append(f"{key}=y")
    elif key in values:
        lines.append(f"{key}={values[key]}")
    else:
        lines.append(f"{key}=<unset>")
out.write_text("\n".join(lines) + "\n")
PY

# First build produces the kernel/export package used to build separate userspace.
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/kernel-build-1.log"
make -j"${JOBS}" export CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/export.log"

EXPORT_TARBALL="$(ls -1t nuttx-export-*.tar.gz | head -n1)"
[[ -s "${EXPORT_TARBALL}" ]] || {
  echo "N5-A FAIL: kernel export archive was not produced" >&2
  exit 4
}

pushd "${APPS_DIR}" >/dev/null
./tools/mkimport.sh -z -x "${NUTTX_DIR}/${EXPORT_TARBALL}" \
  2>&1 | tee "${OUT_DIR}/apps-import-configure.log"
make -j"${JOBS}" import \
  2>&1 | tee "${OUT_DIR}/apps-import-build.log"
./tools/mkromfsimg.sh "${NUTTX_DIR}/arch/risc-v/src/board/romfs_boot.c" \
  2>&1 | tee "${OUT_DIR}/romfs.log"
popd >/dev/null

# Rebuild kernel with generated ROMFS/user binaries linked into the image.
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/kernel-build-2.log"

[[ -s nuttx ]] || {
  echo "N5-A FAIL: final NuttX kernel ELF is missing" >&2
  exit 4
}
cp nuttx "${OUT_DIR}/nuttx.elf"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
[[ -f arch/risc-v/src/board/romfs_boot.c ]] && cp arch/risc-v/src/board/romfs_boot.c "${OUT_DIR}/romfs_boot.c"

riscv64-unknown-elf-size nuttx > "${OUT_DIR}/evidence/kernel-size.txt"
riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/kernel-elf-header.txt"
riscv64-unknown-elf-readelf -A nuttx > "${OUT_DIR}/evidence/kernel-elf-attributes.txt"
riscv64-unknown-elf-readelf -SW nuttx > "${OUT_DIR}/evidence/kernel-elf-sections.txt"
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/kernel-symbols.txt"
riscv64-unknown-elf-objdump -d nuttx > "${OUT_DIR}/evidence/kernel-disassembly.txt"

for symbol in riscv_fillpage up_addrenv_create up_addrenv_select up_addrenv_destroy; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/kernel-symbols.txt" || {
    echo "N5-A FAIL: final kernel is missing MMU/paging symbol ${symbol}" >&2
    exit 5
  }
done

USER_INIT="${APPS_DIR}/bin/init"
[[ -s "${USER_INIT}" ]] || USER_INIT="${APPS_DIR}/bin/nsh"
[[ -s "${USER_INIT}" ]] || {
  echo "N5-A FAIL: imported userspace init/nsh ELF is missing" >&2
  exit 5
}
cp "${USER_INIT}" "${OUT_DIR}/user-init.elf"
riscv64-unknown-elf-readelf -A "${USER_INIT}" > "${OUT_DIR}/evidence/user-elf-attributes.txt"
riscv64-unknown-elf-objdump -d "${USER_INIT}" > "${OUT_DIR}/evidence/user-disassembly.txt"

python3 - "${OUT_DIR}/evidence/kernel-elf-attributes.txt" \
  "${OUT_DIR}/evidence/user-elf-attributes.txt" \
  "${OUT_DIR}/evidence/kernel-disassembly.txt" \
  "${OUT_DIR}/evidence/user-disassembly.txt" \
  "${OUT_DIR}/evidence/isa-audit.txt" <<'PY'
from pathlib import Path
import re
import sys

kernel_attr, user_attr, kernel_dis, user_dis, output = map(Path, sys.argv[1:])

def riscv_arch(path: Path) -> str:
    text = path.read_text(errors="replace")
    m = re.search(r'Tag_RISCV_arch:\s*"([^"]+)"', text, re.I)
    return m.group(1) if m else "<missing>"

def count_atomic(path: Path) -> int:
    text = path.read_text(errors="replace")
    return len(re.findall(r"\b(?:lr\.w|sc\.w|amo(?:swap|add|xor|and|or|min|max|minu|maxu)\.w)(?:\.(?:aq|rl|aqrl))?\b", text))

lines = [
    f"kernel_arch={riscv_arch(kernel_attr)}",
    f"user_arch={riscv_arch(user_attr)}",
    f"kernel_atomic_instructions={count_atomic(kernel_dis)}",
    f"user_atomic_instructions={count_atomic(user_dis)}",
]
output.write_text("\n".join(lines) + "\n")
PY

sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/user-init.elf" \
  > "${OUT_DIR}/evidence/images.sha256"

{
  echo "status=PASS"
  echo "stage=N5-A"
  echo "contract=nuttx-13.0.0-rv-virt-sv32-paging-upstream-build-audit-v1"
  echo "runtime_qualification=not-yet-attempted"
  echo "upstream_config=${CONFIG_NAME}"
  echo "source_commit=${NUTTX_COMMIT}"
  echo "apps_commit=${NUTTX_APPS_COMMIT}"
} > "${OUT_DIR}/evidence/result.txt"

popd >/dev/null

echo "N5-A PASS: pinned NuttX Sv32 paging build and architecture audit completed"
cat "${OUT_DIR}/evidence/config-contract.txt"
cat "${OUT_DIR}/evidence/isa-audit.txt"
