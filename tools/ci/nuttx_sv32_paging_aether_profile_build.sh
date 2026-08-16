#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/nuttx/manifest.env"

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}-sv32-paging"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}-sv32-paging"
PROFILE="${AETHERCORE_NUTTX_N5_PROFILE:-rv32ima}"
JOBS="${NUTTX_JOBS:-6}"

case "$PROFILE" in
  rv32ima)
    ENABLE_C=0
    DEFAULT_OUT_DIR="${ROOT_DIR}/build/nuttx-sv32-paging-aether-profile"
    CONTRACT="nuttx-13.0.0-aethercore-sv32-paging-rv32ima-build-v1"
    PROFILE_TEXT="rv32ima_zicsr_zifencei+Sv32+Sstc"
    ;;
  rv32imac)
    ENABLE_C=1
    DEFAULT_OUT_DIR="${ROOT_DIR}/build/nuttx-sv32c-paging-aether-profile"
    CONTRACT="nuttx-13.0.0-aethercore-sv32-paging-rv32imac-build-v1"
    PROFILE_TEXT="rv32imac_zicsr_zifencei+Sv32+Sstc"
    ;;
  *)
    echo "N5-B FAIL: unsupported AetherCore N5 software profile ${PROFILE}" >&2
    exit 2
    ;;
esac
OUT_DIR="${AETHERCORE_NUTTX_N5_OUT_DIR:-${DEFAULT_OUT_DIR}}"

[[ -d "${NUTTX_DIR}" && -d "${APPS_DIR}" ]] || {
  echo "N5-B FAIL: N5-A extracted source trees are missing" >&2
  exit 2
}
[[ -f "${ROOT_DIR}/build/nuttx-sv32-paging-audit/evidence/result.txt" ]] || {
  echo "N5-B FAIL: N5-A audit result is missing" >&2
  exit 2
}
grep -Fqx 'status=PASS' "${ROOT_DIR}/build/nuttx-sv32-paging-audit/evidence/result.txt" || {
  echo "N5-B FAIL: N5-A did not pass" >&2
  exit 2
}

CONFIG_NAME="$(cat "${ROOT_DIR}/build/nuttx-sv32-paging-audit/evidence/upstream-config-name.txt")"
[[ "${CONFIG_NAME}" == "knsh_paging" || "${CONFIG_NAME}" == "knsh32_paging" ]] || {
  echo "N5-B FAIL: unexpected upstream config ${CONFIG_NAME}" >&2
  exit 2
}

GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"

pushd "${NUTTX_DIR}" >/dev/null
make distclean >/dev/null 2>&1 || true
./tools/configure.sh -E -l -a "../$(basename "${APPS_DIR}")" "rv-virt:${CONFIG_NAME}" \
  2>&1 | tee "${OUT_DIR}/evidence/configure.log"

# Keep every paging/MMU/S-mode requirement from the upstream workload, but
# remove optional ISA features that AetherCore has not implemented. RV32A is
# intentionally retained because N5-A proved that both kernel and userspace
# contain real LR/SC/AMO instructions. Sstc is also retained: it is a genuine
# supervisor-timer requirement of the pinned workload and should drive RTL,
# not be silently compiled away. C is an explicit bounded profile choice: the
# historical rv32ima baseline keeps it disabled, while rv32imac requires it.
python3 - .config "${ENABLE_C}" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
enable_c = sys.argv[2] == "1"
settings = {
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_M": True,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A": True,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C": enable_c,
    "CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI": True,
    "CONFIG_ARCH_RV_ISA_V": False,
    "CONFIG_ARCH_FPU": False,
    "CONFIG_ARCH_DPFPU": False,
    "CONFIG_ARCH_QPFPU": False,
    "CONFIG_ARCH_RV_EXT_SSTC": True,
    "CONFIG_ARCH_USE_S_MODE": True,
    "CONFIG_ARCH_USE_MMU": True,
    "CONFIG_ARCH_ADDRENV": True,
    "CONFIG_BUILD_KERNEL": True,
    "CONFIG_PAGING": True,
}
lines = path.read_text().splitlines()
for symbol, enabled in settings.items():
    pattern = re.compile(rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$")
    lines = [line for line in lines if not pattern.match(line)]
    lines.append(f"{symbol}=y" if enabled else f"# {symbol} is not set")
path.write_text("\n".join(lines) + "\n")
PY

make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/evidence/olddefconfig.log"
cp .config "${OUT_DIR}/resolved.config"

required=(
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_M
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_A
  CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI
  CONFIG_ARCH_RV_EXT_SSTC
  CONFIG_ARCH_USE_S_MODE
  CONFIG_ARCH_USE_MMU
  CONFIG_ARCH_ADDRENV
  CONFIG_BUILD_KERNEL
  CONFIG_PAGING
)
if [[ "$ENABLE_C" == 1 ]]; then
  required+=(CONFIG_ARCH_CHIP_QEMU_RV_ISA_C)
fi
for symbol in "${required[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "N5-B FAIL: resolved profile lost ${symbol}" >&2
    exit 3
  }
done
if [[ "$ENABLE_C" == 0 ]] && grep -Fqx 'CONFIG_ARCH_CHIP_QEMU_RV_ISA_C=y' .config; then
  echo "N5-B FAIL: historical rv32ima profile unexpectedly retained C" >&2
  exit 3
fi
for symbol in CONFIG_ARCH_RV_ISA_V CONFIG_ARCH_FPU CONFIG_ARCH_DPFPU CONFIG_ARCH_QPFPU; do
  if grep -Fqx "${symbol}=y" .config; then
    echo "N5-B FAIL: resolved profile retained unsupported ${symbol}" >&2
    exit 3
  fi
done

make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/kernel-build-1.log"
make -j"${JOBS}" export CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/export.log"
EXPORT_TARBALL="$(ls -1t nuttx-export-*.tar.gz | head -n1)"

pushd "${APPS_DIR}" >/dev/null
./tools/mkimport.sh -z -x "${NUTTX_DIR}/${EXPORT_TARBALL}" \
  2>&1 | tee "${OUT_DIR}/apps-import-configure.log"
make -j"${JOBS}" import 2>&1 | tee "${OUT_DIR}/apps-import-build.log"
./tools/mkromfsimg.sh "${NUTTX_DIR}/arch/risc-v/src/board/romfs_boot.c" \
  2>&1 | tee "${OUT_DIR}/romfs.log"
popd >/dev/null

make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/kernel-build-2.log"
[[ -s nuttx ]] || { echo "N5-B FAIL: final kernel ELF missing" >&2; exit 4; }
cp nuttx "${OUT_DIR}/nuttx.elf"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"

USER_INIT="${APPS_DIR}/bin/init"
[[ -s "${USER_INIT}" ]] || USER_INIT="${APPS_DIR}/bin/nsh"
[[ -s "${USER_INIT}" ]] || { echo "N5-B FAIL: userspace init ELF missing" >&2; exit 4; }
cp "${USER_INIT}" "${OUT_DIR}/user-init.elf"

for image in nuttx "${USER_INIT}"; do
  name="kernel"
  [[ "${image}" != "nuttx" ]] && name="user"
  riscv64-unknown-elf-readelf -A "${image}" > "${OUT_DIR}/evidence/${name}-elf-attributes.txt"
  riscv64-unknown-elf-objdump -d "${image}" > "${OUT_DIR}/evidence/${name}-disassembly.txt"
done
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/kernel-symbols.txt"

AUDIT_ARGS=(
  --kernel-attributes "${OUT_DIR}/evidence/kernel-elf-attributes.txt"
  --kernel-disassembly "${OUT_DIR}/evidence/kernel-disassembly.txt"
  --user-attributes "${OUT_DIR}/evidence/user-elf-attributes.txt"
  --user-disassembly "${OUT_DIR}/evidence/user-disassembly.txt"
  --output "${OUT_DIR}/evidence/isa-audit.txt"
)
if [[ "$ENABLE_C" == 1 ]]; then
  AUDIT_ARGS+=(--require-c)
fi
python3 "${ROOT_DIR}/tools/ci/audit_riscv_elf_profile.py" "${AUDIT_ARGS[@]}"

for symbol in riscv_fillpage up_addrenv_create up_addrenv_select up_addrenv_destroy riscv_jump_to_user; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/kernel-symbols.txt" || {
    echo "N5-B FAIL: kernel missing ${symbol}" >&2
    exit 5
  }
done

sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/user-init.elf" > "${OUT_DIR}/evidence/images.sha256"
{
  echo "status=PASS"
  echo "stage=N5-B"
  echo "contract=${CONTRACT}"
  echo "software_profile=${PROFILE}"
  echo "profile=${PROFILE_TEXT}"
  echo "runtime_qualification=not-yet-attempted"
  if [[ "$ENABLE_C" == 1 ]]; then
    echo "optional_F_D_V=disabled"
    echo "C=required-by-real-kernel-and-userspace"
  else
    echo "optional_C_F_D_V=disabled"
  fi
  echo "RV32A=required-by-real-kernel-and-userspace"
  echo "Sstc=retained-as-real-supervisor-timer-requirement"
} > "${OUT_DIR}/evidence/result.txt"

popd >/dev/null

echo "N5-B PASS: bounded ${PROFILE^^} Sv32 paging software profile builds"
cat "${OUT_DIR}/evidence/isa-audit.txt"
