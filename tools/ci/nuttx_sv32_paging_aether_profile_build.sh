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
OUT_DIR="${ROOT_DIR}/build/nuttx-sv32-paging-aether-profile"
JOBS="${NUTTX_JOBS:-6}"

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

export PATH="${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"

pushd "${NUTTX_DIR}" >/dev/null
make distclean >/dev/null 2>&1 || true
./tools/configure.sh -E -l -a "../$(basename "${APPS_DIR}")" "rv-virt:${CONFIG_NAME}" \
  2>&1 | tee "${OUT_DIR}/evidence/configure.log"

# Keep every paging/MMU/S-mode requirement from the upstream workload, but
# remove optional ISA features that AetherCore has not implemented.  RV32A is
# intentionally retained because N5-A proved that both kernel and userspace
# contain real LR/SC/AMO instructions.  Sstc is also retained: it is a genuine
# supervisor-timer requirement of the pinned workload and should drive RTL,
# not be silently compiled away.
python3 - .config <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
settings = {
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_M": True,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A": True,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C": False,
    "CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI": True,
    "CONFIG_ARCH_RV_ISA_V": False,
    "CONFIG_ARCH_FPU": False,
    "CONFIG_ARCH_DPFPU": False,
    "CONFIG_ARCH_QPFPU": False,
    "CONFIG_ARCH_RV_ISA_SSTC": True,
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
  CONFIG_ARCH_RV_ISA_SSTC
  CONFIG_ARCH_USE_S_MODE
  CONFIG_ARCH_USE_MMU
  CONFIG_ARCH_ADDRENV
  CONFIG_BUILD_KERNEL
  CONFIG_PAGING
)
for symbol in "${required[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "N5-B FAIL: resolved profile lost ${symbol}" >&2
    exit 3
  }
done
for symbol in CONFIG_ARCH_CHIP_QEMU_RV_ISA_C CONFIG_ARCH_RV_ISA_V CONFIG_ARCH_FPU CONFIG_ARCH_DPFPU CONFIG_ARCH_QPFPU; do
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

python3 - "${OUT_DIR}" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])

def arch(name: str) -> str:
    text = (root / "evidence" / f"{name}-elf-attributes.txt").read_text(errors="replace")
    m = re.search(r'Tag_RISCV_arch:\s*"([^"]+)"', text, re.I)
    if not m:
        raise SystemExit(f"N5-B FAIL: missing Tag_RISCV_arch for {name}")
    return m.group(1).lower()

def atomics(name: str) -> int:
    text = (root / "evidence" / f"{name}-disassembly.txt").read_text(errors="replace")
    return len(re.findall(r"\b(?:lr\.w|sc\.w|amo(?:swap|add|xor|and|or|min|max|minu|maxu)\.w)(?:\.(?:aq|rl|aqrl))?\b", text))

lines = []
for name in ("kernel", "user"):
    a = arch(name)
    normalized = re.sub(r"\d+p\d+", "", a)
    tokens = normalized.split("_")
    base = tokens[0]
    ext = base[len("rv32i"):]
    if not base.startswith("rv32i") or "m" not in ext or "a" not in ext:
        raise SystemExit(f"N5-B FAIL: {name} is not RV32IMA: {a}")
    for forbidden in ("c", "f", "d", "v"):
        if forbidden in ext:
            raise SystemExit(f"N5-B FAIL: {name} retained {forbidden.upper()}: {a}")
    if "zicsr" not in tokens or "zifencei" not in tokens:
        raise SystemExit(f"N5-B FAIL: {name} lost Zicsr/Zifencei: {a}")
    count = atomics(name)
    if count == 0:
        raise SystemExit(f"N5-B FAIL: {name} contains no real RV32A instruction")
    lines += [f"{name}_arch={a}", f"{name}_atomic_instructions={count}"]
(root / "evidence" / "isa-audit.txt").write_text("\n".join(lines) + "\n")
PY

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
  echo "contract=nuttx-13.0.0-aethercore-sv32-paging-rv32ima-build-v1"
  echo "profile=rv32ima_zicsr_zifencei+Sv32+Sstc"
  echo "runtime_qualification=not-yet-attempted"
  echo "optional_C_F_D_V=disabled"
  echo "RV32A=required-by-real-kernel-and-userspace"
  echo "Sstc=retained-as-real-supervisor-timer-requirement"
} > "${OUT_DIR}/evidence/result.txt"

popd >/dev/null

echo "N5-B PASS: bounded RV32IMA Sv32 paging software profile builds"
cat "${OUT_DIR}/evidence/isa-audit.txt"
