#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
P3A_DIR="${ROOT_DIR}/build/nuttx-p3a"
OUT_DIR="${ROOT_DIR}/build/nuttx-p3b"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
SIM_ROOT="${CACHE_ROOT}/sim/protected-rv32imu-pmp-interrupt"
RUNNER="${SIM_ROOT}/obj/VAetherCoreNuttXProtectedSimTop"
MAX_CYCLES="${AETHERCORE_NUTTX_P3_MAX_CYCLES:-30000000}"
RX_GAP_CYCLES="${AETHERCORE_NUTTX_P3_RX_GAP_CYCLES:-1000}"
JOBS="${NUTTX_JOBS:-6}"
STALL_PERIODS=(0 3)
FAULT_ADDRESS="0x80000000"
FAULT_CAUSE="0x5"

source "${MANIFEST}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}"
IMAGE="${OUT_DIR}/aethercore-protected.bin"

for command in make python3 riscv64-unknown-elf-objcopy sha256sum grep wc tee; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "P3-B FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -s "${P3A_DIR}/evidence/result.txt" ]] || {
  echo "P3-B FAIL: P3-A result evidence is missing" >&2
  exit 2
}
grep -Fqx 'status=PASS' "${P3A_DIR}/evidence/result.txt" || {
  echo "P3-B FAIL: P3-A did not pass" >&2
  exit 2
}
[[ -d "${NUTTX_DIR}" && -d "${APPS_DIR}" && -s "${NUTTX_DIR}/.config" ]] || {
  echo "P3-B FAIL: configured pinned NuttX/apps source tree is unavailable" >&2
  exit 2
}
[[ -x "${RUNNER}" ]] || {
  echo "P3-B FAIL: cached protected runner is unavailable: ${RUNNER}" >&2
  exit 2
}
for required in CONFIG_ARCH_KERNEL_STACK CONFIG_BUILD_PROTECTED \
  CONFIG_ARCH_USE_MPU CONFIG_RISCV_PERCPU_SCRATCH; do
  grep -Fqx "${required}=y" "${NUTTX_DIR}/.config" || {
    echo "P3-B FAIL: P3-A source config lost ${required}=y" >&2
    exit 2
  }
done
for forbidden in CONFIG_ARCH_ADDRENV CONFIG_ARCH_USE_MMU CONFIG_ARCH_USE_S_MODE; do
  if grep -Fqx "${forbidden}=y" "${NUTTX_DIR}/.config"; then
    echo "P3-B FAIL: forbidden P3 setting became active: ${forbidden}" >&2
    exit 2
  fi
done

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"
cp "${P3A_DIR}/resolved.config" "${OUT_DIR}/resolved.config"
cp "${P3A_DIR}/evidence/result.txt" "${OUT_DIR}/evidence/p3a-result.txt"

python3 "${ROOT_DIR}/tools/nuttx_p3_fault_probe.py" "${APPS_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/fault-probe-overlay.log"

# P3-A already performed the clean hardened build.  The only P3-B source
# change is the pinned hello test probe, so let normal Make dependencies rebuild
# that userspace object and relink the protected images.  Fail closed below if
# the marker is not present in the resulting user ELF.
pushd "${NUTTX_DIR}" >/dev/null
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"
[[ -s nuttx && -s nuttx_user ]] || {
  echo "P3-B FAIL: fault-probe rebuild did not produce nuttx and nuttx_user" >&2
  exit 3
}
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
riscv64-unknown-elf-objcopy -O binary nuttx_user nuttx_user.bin
cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx_user "${OUT_DIR}/nuttx_user.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
cp nuttx_user.bin "${OUT_DIR}/nuttx_user.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
[[ -f nuttx_user.map ]] && cp nuttx_user.map "${OUT_DIR}/nuttx_user.map"
popd >/dev/null

grep -aFq 'P3_FAULT_BEGIN address=0x80000000' "${OUT_DIR}/nuttx_user.elf" || {
  echo "P3-B FAIL: rebuilt user image does not contain the fault probe" >&2
  exit 3
}
grep -aFq 'P3_FAULT_SURVIVED' "${OUT_DIR}/nuttx_user.elf" || {
  echo "P3-B FAIL: rebuilt user image is missing the post-fault sentinel" >&2
  exit 3
}

python3 - "${OUT_DIR}/nuttx.bin" "${OUT_DIR}/nuttx_user.bin" "${IMAGE}" <<'PY'
from pathlib import Path
import sys

kernel_path, user_path, output_path = map(Path, sys.argv[1:])
kernel = kernel_path.read_bytes()
user = user_path.read_bytes()
user_offset = 0x40000
partition_size = 0x40000
if len(kernel) > partition_size:
    raise SystemExit(
        f"P3-B FAIL: kernel load image ({len(kernel)} bytes) exceeds 256 KiB kflash"
    )
if len(user) > partition_size:
    raise SystemExit(
        f"P3-B FAIL: user load image ({len(user)} bytes) exceeds 256 KiB uflash"
    )
combined = bytearray(max(len(kernel), user_offset + len(user)))
combined[:len(kernel)] = kernel
combined[user_offset:user_offset + len(user)] = user
output_path.write_bytes(combined)
print(
    f"P3-B combined image PASS: kernel={len(kernel)} user={len(user)} total={len(combined)}"
)
PY

rx_args=(
  --rx-after-uart "nsh> "
  --rx-byte 0x68
  --rx-byte 0x65
  --rx-byte 0x6c
  --rx-byte 0x6c
  --rx-byte 0x6f
  --rx-byte 0x20
  --rx-byte 0x70
  --rx-byte 0x6d
  --rx-byte 0x70
  --rx-byte 0x66
  --rx-byte 0x61
  --rx-byte 0x75
  --rx-byte 0x6c
  --rx-byte 0x74
  --rx-byte 0x0a
  --rx-gap-cycles "${RX_GAP_CYCLES}"
)

run_fault() {
  local stall_period="$1"
  local log_file="${OUT_DIR}/evidence/fault-stall-${stall_period}.log"
  local args=(
    "${IMAGE}"
    --max-cycles "${MAX_CYCLES}"
    "${rx_args[@]}"
  )
  if [[ "${stall_period}" != "0" ]]; then
    args+=(--stall-period "${stall_period}")
  fi

  set +e
  "${RUNNER}" "${args[@]}" 2>&1 | tee "${log_file}"
  local rc=${PIPESTATUS[0]}
  set -e
  [[ "${rc}" -eq 0 ]] || {
    echo "P3-B FAIL: fault-isolation simulation returned ${rc} (stall=${stall_period})" >&2
    exit 4
  }

  grep -Fq 'P3_FAULT_BEGIN address=0x80000000' "${log_file}" || {
    echo "P3-B FAIL: fault probe did not start in U-mode (stall=${stall_period})" >&2
    exit 4
  }
  if grep -Fq 'P3_FAULT_SURVIVED' "${log_file}"; then
    echo "P3-B FAIL: forbidden kernel-flash load unexpectedly survived PMP" >&2
    exit 4
  fi

  local fault_count
  fault_count="$(grep -Ec 'PROTECTED_EXCEPTION pc=0x800[4-7][0-9a-fA-F]{4} .*cause=0x5 .*value=0x80000000' "${log_file}" || true)"
  [[ "${fault_count}" -eq 1 ]] || {
    echo "P3-B FAIL: expected exactly one U-mode load-access fault at 0x80000000, got ${fault_count}" >&2
    exit 4
  }
  grep -Eq 'EXCEPTION: Load access fault\. MCAUSE: 0*5, EPC: [0-9a-fA-F]+, MTVAL: 80000000' "${log_file}" || {
    echo "P3-B FAIL: NuttX did not report the precise PMP load fault" >&2
    exit 4
  }
  grep -Fq 'Segmentation fault in' "${log_file}" || {
    echo "P3-B FAIL: NuttX did not isolate the offending user task" >&2
    exit 4
  }
  if grep -Eq 'PANIC!!!|PANIC:|irq_unexpected_isr|FAIL: timeout|FAIL: no instruction retired' "${log_file}"; then
    echo "P3-B FAIL: user fault escalated into a kernel panic or stalled runtime" >&2
    exit 4
  fi

  python3 - "${log_file}" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(errors="replace")
begin = text.find("P3_FAULT_BEGIN address=0x80000000")
fault = text.find("cause=0x5", begin)
segv = text.find("Segmentation fault in", fault)
prompt = text.find("nsh>", fault)
if min(begin, fault, segv, prompt) < 0:
    raise SystemExit("P3-B FAIL: fault/recovery evidence ordering is incomplete")
if not (begin < fault < segv < prompt):
    raise SystemExit(
        "P3-B FAIL: expected probe < PMP fault < task isolation < returned NSH, "
        f"got offsets {begin}, {fault}, {segv}, {prompt}"
    )
print("P3-B recovery ordering PASS: probe -> PMP fault -> task isolation -> NSH")
PY

  grep -Eq 'UMODE_COMMAND_EVIDENCE user-commits=[1-9][0-9]* u-ecalls=[1-9][0-9]* mrets=[1-9][0-9]*' \
    "${log_file}" || {
    echo "P3-B FAIL: fault command did not retain U-mode/ECALL/MRET evidence" >&2
    exit 4
  }
}

for stall_period in "${STALL_PERIODS[@]}"; do
  run_fault "${stall_period}"
done

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-p3b-pmp-fault-isolation-v1
base_contract=nuttx-13.0.0-aethercore-p3a-independent-kernel-stack-build-v1
image=${IMAGE}
runner=${RUNNER}
attacker=hello-pmpfault-test-probe
fault_access=load
fault_address=${FAULT_ADDRESS}
fault_cause=${FAULT_CAUSE}
recovery=forced-cancel-and-return-to-nsh
kernel_panic=absent
kernel_stack=enabled
address_environment=disabled
mmu=disabled
supervisor_mode=disabled
stall_periods=0,3
max_cycles=${MAX_CYCLES}
EOF
sha256sum \
  "${IMAGE}" "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx_user.elf" \
  "${RUNNER}" "${OUT_DIR}/evidence/fault-stall-0.log" \
  "${OUT_DIR}/evidence/fault-stall-3.log" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "P3-B PASS: U-mode PMP fault was isolated and NSH recovered"
