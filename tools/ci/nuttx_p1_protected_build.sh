#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-p1"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
ARCHIVE_DIR="${CACHE_ROOT}/archives"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
JOBS="${NUTTX_JOBS:-6}"

source "${MANIFEST}"
NUTTX_ARCHIVE="${ARCHIVE_DIR}/nuttx-${NUTTX_COMMIT}.tar.gz"
APPS_ARCHIVE="${ARCHIVE_DIR}/nuttx-apps-${NUTTX_APPS_COMMIT}.tar.gz"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}"

for command in make python3 tar riscv64-unknown-elf-gcc \
  riscv64-unknown-elf-objcopy riscv64-unknown-elf-readelf \
  riscv64-unknown-elf-nm sha256sum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "P1 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -s "${NUTTX_ARCHIVE}" && -s "${APPS_ARCHIVE}" ]] || {
  echo "P1 FAIL: pinned NuttX archives are missing; run the staged NuttX source fetch first" >&2
  exit 2
}
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "P1 FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
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
  local staging="${destination}.p1.$$"
  rm -rf "${staging}" "${destination}"
  mkdir -p "${staging}"
  tar -xzf "${archive}" --strip-components=1 -C "${staging}"
  mv "${staging}" "${destination}"
}

extract_fresh "${NUTTX_ARCHIVE}" "${NUTTX_DIR}"
extract_fresh "${APPS_ARCHIVE}" "${APPS_DIR}"
sha256sum "${NUTTX_ARCHIVE}" "${APPS_ARCHIVE}" \
  > "${OUT_DIR}/evidence/source-archives.sha256"

pushd "${NUTTX_DIR}" >/dev/null
./tools/configure.sh -E -l -a "../$(basename "${APPS_DIR}")" rv-virt:pnsh \
  2>&1 | tee "${OUT_DIR}/evidence/configure.log"

python3 - .config <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
settings: dict[str, str | bool] = {
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_M": True,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A": False,
    "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C": False,
    "CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI": True,
    "CONFIG_ARCH_RV_ISA_V": False,
    "CONFIG_ARCH_FPU": False,
    "CONFIG_ARCH_DPFPU": False,
    "CONFIG_ARCH_QPFPU": False,
    "CONFIG_FS_HOSTFS": False,
    "CONFIG_RISCV_SEMIHOSTING_HOSTFS": False,
    "CONFIG_RISCV_TOOLCHAIN_GNU_RV64": True,
    "CONFIG_RISCV_TOOLCHAIN_GNU_RV32": False,
    "CONFIG_RISCV_TOOLCHAIN_CLANG": False,
    "CONFIG_BUILD_PROTECTED": True,
    "CONFIG_ARCH_USE_MPU": True,
    "CONFIG_BUILTIN": True,
    "CONFIG_SYSTEM_NSH": True,
    "CONFIG_NSH_BUILTIN_APPS": True,
    "CONFIG_EXAMPLES_HELLO": True,
    "CONFIG_SUPPRESS_INTERRUPTS": False,
    "CONFIG_NUTTX_USERSPACE": "0x80040000",
}
lines = path.read_text().splitlines()
for symbol, value in settings.items():
    pattern = re.compile(
        rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$"
    )
    lines = [line for line in lines if not pattern.match(line)]
    if isinstance(value, bool):
        lines.append(f"{symbol}=y" if value else f"# {symbol} is not set")
    else:
        lines.append(f"{symbol}={value}")
path.write_text("\n".join(lines) + "\n")
PY

# Reuse the already qualified AetherCore console, timer, and PLIC boundaries.
python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/n2-overlay.log"
python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_n3_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/n3-overlay.log"
python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_n4_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/n4-overlay.log"

make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/evidence/olddefconfig.log"

required_enabled=(
  CONFIG_BUILD_PROTECTED
  CONFIG_ARCH_USE_MPU
  CONFIG_BUILTIN
  CONFIG_SYSTEM_NSH
  CONFIG_NSH_BUILTIN_APPS
  CONFIG_EXAMPLES_HELLO
  CONFIG_AETHERCORE_UART
  CONFIG_AETHERCORE_TIMER
  CONFIG_AETHERCORE_UART_RX_IRQ
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_M
  CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI
  CONFIG_RISCV_TOOLCHAIN_GNU_RV64
)
for symbol in "${required_enabled[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "P1 FAIL: resolved configuration did not enable ${symbol}" >&2
    exit 3
  }
done
forbidden_enabled=(
  CONFIG_ARCH_USE_S_MODE
  CONFIG_SUPPRESS_INTERRUPTS
  CONFIG_16550_UART
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_A
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_C
  CONFIG_ARCH_RV_ISA_V
  CONFIG_ARCH_FPU
  CONFIG_ARCH_DPFPU
  CONFIG_ARCH_QPFPU
  CONFIG_FS_HOSTFS
  CONFIG_RISCV_SEMIHOSTING_HOSTFS
  CONFIG_RISCV_TOOLCHAIN_GNU_RV32
  CONFIG_RISCV_TOOLCHAIN_CLANG
)
for symbol in "${forbidden_enabled[@]}"; do
  if grep -Fqx "${symbol}=y" .config; then
    echo "P1 FAIL: resolved configuration enabled forbidden ${symbol}" >&2
    exit 3
  fi
done
grep -Fqx 'CONFIG_NUTTX_USERSPACE=0x80040000' .config || {
  echo "P1 FAIL: userspace link address is not 0x80040000" >&2
  exit 3
}

cp .config "${OUT_DIR}/nuttx.config"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"

[[ -s nuttx && -s nuttx_user ]] || {
  echo "P1 FAIL: protected build did not produce both nuttx and nuttx_user" >&2
  exit 4
}
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
riscv64-unknown-elf-objcopy -O binary nuttx_user nuttx_user.bin
[[ -s nuttx.bin && -s nuttx_user.bin ]] || {
  echo "P1 FAIL: protected flat images are empty" >&2
  exit 4
}

cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx_user "${OUT_DIR}/nuttx_user.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
cp nuttx_user.bin "${OUT_DIR}/nuttx_user.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
[[ -f nuttx_user.map ]] && cp nuttx_user.map "${OUT_DIR}/nuttx_user.map"

riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/kernel-elf-header.txt"
riscv64-unknown-elf-readelf -A nuttx > "${OUT_DIR}/evidence/kernel-elf-attributes.txt"
riscv64-unknown-elf-readelf -SW nuttx > "${OUT_DIR}/evidence/kernel-elf-sections.txt"
riscv64-unknown-elf-readelf -h nuttx_user > "${OUT_DIR}/evidence/user-elf-header.txt"
riscv64-unknown-elf-readelf -A nuttx_user > "${OUT_DIR}/evidence/user-elf-attributes.txt"
riscv64-unknown-elf-readelf -SW nuttx_user > "${OUT_DIR}/evidence/user-elf-sections.txt"
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/kernel-symbols.txt"
riscv64-unknown-elf-nm -n nuttx_user > "${OUT_DIR}/evidence/user-symbols.txt"

for symbol in qemu_rv_userspace qemu_rv_configure_mpu riscv_append_pmp_region riscv_swint; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/kernel-symbols.txt" || {
    echo "P1 FAIL: kernel image is missing ${symbol}" >&2
    exit 4
  }
done
for symbol in nsh_main hello_main; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/user-symbols.txt" || {
    echo "P1 FAIL: userspace image is missing ${symbol}" >&2
    exit 4
  }
done

python3 - \
  "${OUT_DIR}/evidence/kernel-elf-header.txt" \
  "${OUT_DIR}/evidence/kernel-elf-sections.txt" \
  "${OUT_DIR}/evidence/user-elf-sections.txt" \
  "${OUT_DIR}/evidence/kernel-elf-attributes.txt" \
  "${OUT_DIR}/evidence/user-elf-attributes.txt" <<'PY'
from pathlib import Path
import re
import sys

kernel_header, kernel_sections, user_sections, *attribute_paths = map(Path, sys.argv[1:])
header = kernel_header.read_text()
entry_match = re.search(r"Entry point address:\s*(0x[0-9a-fA-F]+)", header)
if not entry_match or int(entry_match.group(1), 16) != 0x80000000:
    raise SystemExit("P1 FAIL: kernel entry is not 0x80000000")

def section_address(path: Path, name: str) -> int:
    pattern = re.compile(
        rf"\[\s*\d+\]\s+{re.escape(name)}\s+\S+\s+([0-9a-fA-F]+)"
    )
    match = pattern.search(path.read_text())
    if not match:
        raise SystemExit(f"P1 FAIL: missing ELF section {name} in {path}")
    return int(match.group(1), 16)

kernel_text = section_address(kernel_sections, ".text")
user_space = section_address(user_sections, ".userspace")
user_text = section_address(user_sections, ".text")
user_data = section_address(user_sections, ".data")
if kernel_text != 0x80000000:
    raise SystemExit(f"P1 FAIL: kernel .text starts at {kernel_text:#x}")
if user_space != 0x80040000:
    raise SystemExit(f"P1 FAIL: .userspace starts at {user_space:#x}")
if not 0x80040000 <= user_text < 0x80080000:
    raise SystemExit(f"P1 FAIL: user .text escaped UFLASH: {user_text:#x}")
if not 0x80200000 <= user_data < 0x80300000:
    raise SystemExit(f"P1 FAIL: user .data escaped USRAM: {user_data:#x}")

for path in attribute_paths:
    text = path.read_text().lower()
    match = re.search(r'tag_riscv_arch:\s*"([^"]+)"', text)
    if not match:
        raise SystemExit(f"P1 FAIL: missing Tag_RISCV_arch in {path}")
    arch = match.group(1)
    normalized = re.sub(r"\d+p\d+", "", arch)
    tokens = normalized.split("_")
    base = tokens[0]
    extensions = base[len("rv32i"):]
    if not base.startswith("rv32i") or "zicsr" not in tokens or "zifencei" not in tokens:
        raise SystemExit(f"P1 FAIL: unexpected ISA attribute {arch}")
    if "m" not in extensions and "m" not in tokens[1:]:
        raise SystemExit(f"P1 FAIL: M extension missing from {arch}")
    for forbidden in ("a", "c", "f", "d", "v"):
        if forbidden in extensions or forbidden in tokens[1:]:
            raise SystemExit(f"P1 FAIL: forbidden extension {forbidden} in {arch}")
print("P1 ELF layout and ISA PASS")
PY

python3 - "${OUT_DIR}/nuttx.bin" "${OUT_DIR}/nuttx_user.bin" \
  "${OUT_DIR}/aethercore-protected.bin" <<'PY'
from pathlib import Path
import sys

kernel_path, user_path, output_path = map(Path, sys.argv[1:])
kernel = kernel_path.read_bytes()
user = user_path.read_bytes()
user_offset = 0x40000
if len(kernel) > user_offset:
    raise SystemExit(
        f"P1 FAIL: kernel load image ({len(kernel)} bytes) overlaps userspace offset"
    )
combined = bytearray(max(len(kernel), user_offset + len(user)))
combined[:len(kernel)] = kernel
combined[user_offset:user_offset + len(user)] = user
output_path.write_bytes(combined)
print(
    f"P1 combined image: kernel={len(kernel)} user={len(user)} total={len(combined)}"
)
PY

popd >/dev/null

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-p1-protected-build-v1
kernel_image=${OUT_DIR}/nuttx.elf
userspace_image=${OUT_DIR}/nuttx_user.elf
combined_image=${OUT_DIR}/aethercore-protected.bin
kernel_privilege=M
userspace_privilege=U
userspace_link=0x80040000
user_flash=0x80040000-0x80080000:rx
user_ram=0x80200000-0x80300000:rw
pmp_mode=NAPOT
pmp_entries_required=2
syscall_boundary=riscv_swint
user_programs=nsh,hello
runtime=not-yet-qualified
profile=rv32im_zicsr_zifencei
EOF
sha256sum \
  "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx_user.elf" \
  "${OUT_DIR}/nuttx.bin" "${OUT_DIR}/nuttx_user.bin" \
  "${OUT_DIR}/aethercore-protected.bin" "${OUT_DIR}/nuttx.config" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "P1 PASS: NuttX protected kernel and U-mode NSH/hello images built"
