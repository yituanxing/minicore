#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-p3a"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
JOBS="${NUTTX_JOBS:-6}"
KSTACK_SIZE="${AETHERCORE_NUTTX_P3_KSTACK_SIZE:-1568}"

source "${MANIFEST}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"

for command in make python3 riscv64-unknown-elf-objcopy \
  riscv64-unknown-elf-objdump riscv64-unknown-elf-readelf \
  riscv64-unknown-elf-nm sha256sum grep awk tee; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "P3-A FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -d "${NUTTX_DIR}" && -s "${NUTTX_DIR}/.config" ]] || {
  echo "P3-A FAIL: P1 source tree/config is missing; run nuttx_p1_protected_build.sh first" >&2
  exit 2
}
[[ -s "${ROOT_DIR}/build/nuttx-p1/evidence/result.txt" ]] || {
  echo "P3-A FAIL: frozen P1 regression evidence is missing" >&2
  exit 2
}
grep -Fqx 'status=PASS' "${ROOT_DIR}/build/nuttx-p1/evidence/result.txt" || {
  echo "P3-A FAIL: P1 regression did not pass before kernel-stack hardening" >&2
  exit 2
}
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "P3-A FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/tools/ci/kconfig-tweak"
GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"
cp "${ROOT_DIR}/build/nuttx-p1/evidence/result.txt" \
  "${OUT_DIR}/evidence/p1-regression-result.txt"

python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_p3_kernel_stack_overlay.py" \
  "${NUTTX_DIR}" 2>&1 | tee "${OUT_DIR}/evidence/kernel-stack-overlay.log"

pushd "${NUTTX_DIR}" >/dev/null
python3 - .config "${KSTACK_SIZE}" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
size = int(sys.argv[2], 0)
if size < 512:
    raise SystemExit("P3-A FAIL: kernel stack size is implausibly small")

settings = {
    "CONFIG_ARCH_KERNEL_STACK": "y",
    "CONFIG_ARCH_KERNEL_STACKSIZE": str(size),
    "CONFIG_ARCH_ADDRENV": None,
    "CONFIG_ARCH_USE_MMU": None,
    "CONFIG_ARCH_USE_S_MODE": None,
}
lines = path.read_text().splitlines()
for symbol, value in settings.items():
    pattern = re.compile(
        rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$"
    )
    lines = [line for line in lines if not pattern.match(line)]
    if value is None:
        lines.append(f"# {symbol} is not set")
    else:
        lines.append(f"{symbol}={value}")
path.write_text("\n".join(lines) + "\n")
PY

set +e
make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  > >(tee "${OUT_DIR}/evidence/olddefconfig.log") \
  2> >(tee "${OUT_DIR}/evidence/olddefconfig.err" >&2)
olddefconfig_rc=$?
set -e
cp .config "${OUT_DIR}/resolved.config"
if [[ "${olddefconfig_rc}" -ne 0 ]]; then
  echo "P3-A FAIL: olddefconfig returned ${olddefconfig_rc}" >&2
  exit 3
fi

# The build probe deliberately fails here if pinned Kconfig still hides the
# kernel-stack option.  We do not patch Kconfig speculatively.
grep -Fqx 'CONFIG_ARCH_KERNEL_STACK=y' .config || {
  echo "P3-A FAIL: olddefconfig removed CONFIG_ARCH_KERNEL_STACK=y" >&2
  echo "P3-A NOTE: patch the pinned Kconfig visibility only after this real failure is observed" >&2
  exit 3
}
grep -Fqx "CONFIG_ARCH_KERNEL_STACKSIZE=${KSTACK_SIZE}" .config || {
  echo "P3-A FAIL: resolved kernel stack size is not ${KSTACK_SIZE}" >&2
  exit 3
}
for forbidden in CONFIG_ARCH_ADDRENV CONFIG_ARCH_USE_MMU CONFIG_ARCH_USE_S_MODE; do
  if grep -Fqx "${forbidden}=y" .config; then
    echo "P3-A FAIL: kernel-stack hardening unexpectedly enabled ${forbidden}" >&2
    exit 3
  fi
done
for required in CONFIG_BUILD_PROTECTED CONFIG_ARCH_USE_MPU CONFIG_LIB_SYSCALL \
  CONFIG_RISCV_PERCPU_SCRATCH CONFIG_ARCH_CHIP_QEMU_RV_ISA_A; do
  grep -Fqx "${required}=y" .config || {
    echo "P3-A FAIL: protected prerequisite ${required} is not enabled" >&2
    exit 3
  }
done

echo "P3-A resolved config PASS: independent kernel stack ${KSTACK_SIZE} bytes, addrenv/MMU/S-mode disabled" \
  | tee "${OUT_DIR}/evidence/resolved-config-summary.txt"

# CONFIG_ARCH_KERNEL_STACK changes exception entry assembly and TCB lifetime
# behavior, so force a clean rebuild instead of trusting incremental objects.
make clean 2>&1 | tee "${OUT_DIR}/evidence/clean.log"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"

[[ -s nuttx && -s nuttx_user ]] || {
  echo "P3-A FAIL: hardened protected build did not produce nuttx and nuttx_user" >&2
  exit 4
}
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
riscv64-unknown-elf-objcopy -O binary nuttx_user nuttx_user.bin
cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx_user "${OUT_DIR}/nuttx_user.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
cp nuttx_user.bin "${OUT_DIR}/nuttx_user.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
[[ -f nuttx_user.map ]] && cp nuttx_user.map "${OUT_DIR}/nuttx_user.map"

riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/kernel-symbols.txt"
riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/kernel-elf-header.txt"
riscv64-unknown-elf-readelf -A nuttx > "${OUT_DIR}/evidence/kernel-elf-attributes.txt"
riscv64-unknown-elf-readelf -A nuttx_user > "${OUT_DIR}/evidence/user-elf-attributes.txt"
riscv64-unknown-elf-objdump -dr nuttx > "${OUT_DIR}/evidence/kernel-disassembly.txt"

for symbol in up_addrenv_kstackalloc up_addrenv_kstackfree \
  riscv_exception exception_common riscv_percpu_set_kstack; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/kernel-symbols.txt" || {
    echo "P3-A FAIL: hardened kernel image is missing ${symbol}" >&2
    exit 4
  }
done

# Keep the same deterministic protected load-image layout as frozen P1/P2.
python3 - "${OUT_DIR}/nuttx.bin" "${OUT_DIR}/nuttx_user.bin" \
  "${OUT_DIR}/aethercore-protected.bin" <<'PY'
from pathlib import Path
import sys

kernel_path, user_path, output_path = map(Path, sys.argv[1:])
kernel = kernel_path.read_bytes()
user = user_path.read_bytes()
user_offset = 0x40000
partition_size = 0x40000
if len(kernel) > partition_size:
    raise SystemExit(
        f"P3-A FAIL: kernel load image ({len(kernel)} bytes) exceeds 256 KiB kflash"
    )
if len(user) > partition_size:
    raise SystemExit(
        f"P3-A FAIL: user load image ({len(user)} bytes) exceeds 256 KiB uflash"
    )
combined = bytearray(max(len(kernel), user_offset + len(user)))
combined[:len(kernel)] = kernel
combined[user_offset:user_offset + len(user)] = user
output_path.write_bytes(combined)
print(
    f"P3-A combined image PASS: kernel={len(kernel)} user={len(user)} total={len(combined)}"
)
PY

popd >/dev/null

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-p3a-independent-kernel-stack-build-v1
base_contract=nuttx-13.0.0-aethercore-p1-protected-rv32ima-build-v2
kernel_stack=enabled
kernel_stack_size=${KSTACK_SIZE}
address_environment=disabled
mmu=disabled
supervisor_mode=disabled
userspace_privilege=U
kernel_privilege=M
pmp_entries=4
profile=rv32ima_zicsr_zifencei
runtime=not-yet-qualified
fault_isolation=not-yet-qualified
EOF
sha256sum \
  "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx_user.elf" \
  "${OUT_DIR}/nuttx.bin" "${OUT_DIR}/nuttx_user.bin" \
  "${OUT_DIR}/aethercore-protected.bin" "${OUT_DIR}/resolved.config" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "P3-A PASS: protected RV32IMA images rebuilt with independent kernel stacks"
