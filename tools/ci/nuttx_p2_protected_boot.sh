#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
P1_DIR="${ROOT_DIR}/build/nuttx-p1"
OUT_DIR="${ROOT_DIR}/build/nuttx-p2"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SIM_ROOT="${CACHE_ROOT}/sim/protected-rv32imu-pmp-interrupt"
RTL_DIR="${SIM_ROOT}/rtl"
OBJ_DIR="${SIM_ROOT}/obj"
GENERATED_MAIN="${SIM_ROOT}/sim_main_nuttx_protected.cpp"
RUNNER="${OBJ_DIR}/VAetherCoreNuttXProtectedSimTop"
SIM_FINGERPRINT_FILE="${SIM_ROOT}/source.sha256"
SIM_ABI_VERSION="nuttx-protected-sim-v2"
IMAGE="${P1_DIR}/aethercore-protected.bin"
MAX_CYCLES="${AETHERCORE_NUTTX_P2_MAX_CYCLES:-30000000}"
RX_GAP_CYCLES="${AETHERCORE_NUTTX_P2_RX_GAP_CYCLES:-1000}"
STALL_PERIODS=(0 3)

for command in python3 verilator sha256sum grep awk find sort wc tee; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "P2 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -s "${IMAGE}" ]] || {
  echo "P2 FAIL: P1 protected combined image is missing: ${IMAGE}" >&2
  exit 2
}
[[ -s "${P1_DIR}/nuttx.elf" && -s "${P1_DIR}/nuttx_user.elf" ]] || {
  echo "P2 FAIL: P1 kernel/userspace ELF evidence is incomplete" >&2
  exit 2
}
[[ -s "${P1_DIR}/nuttx.bin" && -s "${P1_DIR}/nuttx_user.bin" ]] || {
  echo "P2 FAIL: P1 kernel/userspace load images are incomplete" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/mill"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence" "${SIM_ROOT}"

python3 - \
  "${P1_DIR}/nuttx.bin" "${P1_DIR}/nuttx_user.bin" "${IMAGE}" <<'PY' \
  2>&1 | tee "${OUT_DIR}/evidence/image-layout.log"
from pathlib import Path
import sys

kernel_path, user_path, combined_path = map(Path, sys.argv[1:])
kernel = kernel_path.read_bytes()
user = user_path.read_bytes()
combined = combined_path.read_bytes()
user_offset = 0x40000
partition_size = 0x40000

if len(kernel) > partition_size:
    raise SystemExit(
        f"P2 FAIL: kernel load image is {len(kernel)} bytes, exceeds 256 KiB kflash"
    )
if len(user) > partition_size:
    raise SystemExit(
        f"P2 FAIL: user load image is {len(user)} bytes, exceeds 256 KiB uflash"
    )
expected_size = max(len(kernel), user_offset + len(user))
if len(combined) != expected_size:
    raise SystemExit(
        f"P2 FAIL: combined image size is {len(combined)}, expected {expected_size}"
    )
if combined[:len(kernel)] != kernel:
    raise SystemExit("P2 FAIL: combined image does not preserve the kernel load bytes")
if any(combined[len(kernel):user_offset]):
    raise SystemExit("P2 FAIL: non-zero bytes escaped into the kflash/uflash gap")
if combined[user_offset:user_offset + len(user)] != user:
    raise SystemExit("P2 FAIL: userspace load bytes are not placed at 0x80040000")
print(
    "P2 protected image layout PASS: "
    f"kernel={len(kernel)} user={len(user)} combined={len(combined)} "
    "user-offset=0x40000"
)
PY

sim_fingerprint="$({
  printf 'sim-abi=%s\n' "${SIM_ABI_VERSION}"
  verilator --version
  find "${ROOT_DIR}/src/main/scala" -type f \
    \( -name '*.scala' -o -name '*.sc' \) -print | sort | \
    while IFS= read -r source; do
      sha256sum "${source}"
    done
  sha256sum \
    "${ROOT_DIR}/build.sc" \
    "${ROOT_DIR}/mill" \
    "${ROOT_DIR}/sim/sim_main.cpp" \
    "${ROOT_DIR}/sim/nemu_difftest.cpp" \
    "${ROOT_DIR}/sim/nemu_difftest.h" \
    "${ROOT_DIR}/tools/make_nuttx_protected_runner.py"
} | sha256sum | awk '{print $1}')"

sim_cache=miss
if [[ -x "${RUNNER}" && -s "${SIM_FINGERPRINT_FILE}" && \
      "$(cat "${SIM_FINGERPRINT_FILE}")" == "${sim_fingerprint}" ]]; then
  sim_cache=hit
  echo "P2: reuse protected simulator ${sim_fingerprint}"
else
  echo "P2: rebuild protected simulator ${sim_fingerprint}"
  rm -rf "${RTL_DIR}" "${OBJ_DIR}" "${GENERATED_MAIN}"
  rm -f "${SIM_FINGERPRINT_FILE}"
  mkdir -p "${RTL_DIR}" "${OBJ_DIR}"

  "${ROOT_DIR}/mill" aethercore.runMain aethercore.ElaborateNuttXProtected \
    --target-dir "${RTL_DIR}" \
    2>&1 | tee "${OUT_DIR}/evidence/elaboration.log"

  mapfile -t rtl_sources < <(find "${RTL_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
  if (( ${#rtl_sources[@]} == 0 )); then
    echo "P2 FAIL: protected elaboration produced no SystemVerilog" >&2
    exit 3
  fi

  python3 "${ROOT_DIR}/tools/make_nuttx_protected_runner.py" \
    "${ROOT_DIR}/sim/sim_main.cpp" "${GENERATED_MAIN}" \
    2>&1 | tee "${OUT_DIR}/evidence/runner-generation.log"

  verilator --cc --exe --build --trace -Wall -Wno-fatal \
    --top-module AetherCoreNuttXProtectedSimTop \
    -Mdir "${OBJ_DIR}" \
    -CFLAGS "-I${ROOT_DIR}/sim -std=c++20 -O2" -LDFLAGS "-ldl" \
    "${rtl_sources[@]}" "${GENERATED_MAIN}" \
    "${ROOT_DIR}/sim/nemu_difftest.cpp" \
    2>&1 | tee "${OUT_DIR}/evidence/simulator-build.log"

  [[ -x "${RUNNER}" ]] || {
    echo "P2 FAIL: protected AetherCore runner was not produced" >&2
    exit 3
  }
  printf '%s\n' "${sim_fingerprint}" > "${SIM_FINGERPRINT_FILE}"
fi

[[ -x "${RUNNER}" ]] || {
  echo "P2 FAIL: protected AetherCore runner is unavailable after cache preparation" >&2
  exit 3
}
cat > "${OUT_DIR}/evidence/simulator-cache.txt" <<EOF
status=${sim_cache}
fingerprint=${sim_fingerprint}
abi=${SIM_ABI_VERSION}
runner=${RUNNER}
verilator=$(verilator --version)
EOF
cat "${OUT_DIR}/evidence/simulator-cache.txt"

rx_args=(
  --rx-after-uart "nsh> "
  --rx-byte 0x68
  --rx-byte 0x65
  --rx-byte 0x6c
  --rx-byte 0x6c
  --rx-byte 0x6f
  --rx-byte 0x0a
  --rx-gap-cycles "${RX_GAP_CYCLES}"
)

run_positive() {
  local stall_period="$1"
  local log_file="${OUT_DIR}/evidence/boot-stall-${stall_period}.log"
  local args=(
    "${IMAGE}"
    --max-cycles "${MAX_CYCLES}"
    --self-check-exit
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
    echo "P2 FAIL: simulation returned ${rc}, expected immediate success at the second NSH prompt (stall=${stall_period})" >&2
    exit 4
  }
  grep -Fq 'Hello, World!!' "${log_file}" || {
    echo "P2 FAIL: U-mode hello output was not observed (stall=${stall_period})" >&2
    exit 4
  }
  local prompt_count
  prompt_count="$(grep -o 'nsh>' "${log_file}" | wc -l)"
  [[ "${prompt_count}" -ge 2 ]] || {
    echo "P2 FAIL: NSH did not return after the U-mode hello process (stall=${stall_period})" >&2
    exit 4
  }
  grep -Eq 'UMODE_EVIDENCE user-commits=[1-9][0-9]* u-ecalls=[1-9][0-9]* mrets=[1-9][0-9]*' \
    "${log_file}" || {
    echo "P2 FAIL: architectural U-mode evidence is incomplete (stall=${stall_period})" >&2
    exit 4
  }
  grep -Eq 'UMODE_COMMAND_EVIDENCE user-commits=[1-9][0-9]* u-ecalls=[1-9][0-9]* mrets=[1-9][0-9]*' \
    "${log_file}" || {
    echo "P2 FAIL: hello command phase did not add user commits, U-mode ECALLs, and MRET returns (stall=${stall_period})" >&2
    exit 4
  }
  grep -Eq 'PROTECTED_EXCEPTION .*cause=0x8([[:space:]]|$)' "${log_file}" || {
    echo "P2 FAIL: no explicit ECALL-from-U exception record (stall=${stall_period})" >&2
    exit 4
  }
  grep -Fq 'PASS: protected NSH returned after U-mode hello' "${log_file}" || {
    echo "P2 FAIL: runner did not terminate on the returned NSH prompt (stall=${stall_period})" >&2
    exit 4
  }
  if grep -Eq 'PANIC|EXCEPTION:|irq_unexpected_isr|FAIL: timeout|FAIL: no instruction retired|FAIL: no ECALL-from-U|FAIL: no MRET|FAIL: protected command phase|FAIL: hello command' \
    "${log_file}"; then
    echo "P2 FAIL: protected runtime panic, timeout, or incomplete command-phase evidence" >&2
    exit 4
  fi
}

for stall_period in "${STALL_PERIODS[@]}"; do
  run_positive "${stall_period}"
done

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-p2-protected-umode-hello-v1
image=${IMAGE}
runner=${RUNNER}
simulator_cache=${sim_cache}
simulator_fingerprint=${sim_fingerprint}
kernel_privilege=M
userspace_privilege=U
user_program=hello
user_output=Hello, World!!
input_arm=nsh-prompt
input_command=hello
image_layout=kflash-0x80000000-0x80040000,uflash-0x80040000-0x80080000
shared_toy_assertions=disabled-via-self-check-exit
syscall_proof=ecall-from-u-cause-8
transition_proof=mret-and-user-text-commit
command_phase_proof=post-first-prompt-user-commit-ecall-mret
pmp_entries=4
interrupt_platform=clint,plic,uart-rx
stall_periods=0,3
max_cycles=${MAX_CYCLES}
termination=immediate-success-after-second-nsh-prompt
EOF
sha256sum \
  "${IMAGE}" "${P1_DIR}/nuttx.elf" "${P1_DIR}/nuttx_user.elf" \
  "${RUNNER}" "${OUT_DIR}/evidence/boot-stall-0.log" \
  "${OUT_DIR}/evidence/boot-stall-3.log" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "P2 PASS: NuttX executed hello in protected U-mode and returned to NSH"
