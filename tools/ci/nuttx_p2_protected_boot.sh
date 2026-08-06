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

chmod +x "${ROOT_DIR}/mill"
rm -rf "${OUT_DIR}" "${RTL_DIR}" "${OBJ_DIR}"
mkdir -p "${OUT_DIR}/evidence" "${RTL_DIR}" "${OBJ_DIR}" "${SIM_ROOT}"

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

  [[ "${rc}" -eq 2 ]] || {
    echo "P2 FAIL: simulation returned ${rc}, expected bounded timeout after hello (stall=${stall_period})" >&2
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
  grep -Eq 'PROTECTED_EXCEPTION .*cause=0x8([[:space:]]|$)' "${log_file}" || {
    echo "P2 FAIL: no explicit ECALL-from-U exception record (stall=${stall_period})" >&2
    exit 4
  }
  grep -Fq "FAIL: timeout after ${MAX_CYCLES} cycles" "${log_file}" || {
    echo "P2 FAIL: simulation did not terminate at the configured bound" >&2
    exit 4
  }
  if grep -Eq 'PANIC|EXCEPTION:|irq_unexpected_isr|FAIL: no instruction retired|FAIL: no ECALL-from-U|FAIL: no MRET' \
    "${log_file}"; then
    echo "P2 FAIL: protected runtime panic or missing architecture evidence" >&2
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
kernel_privilege=M
userspace_privilege=U
user_program=hello
user_output=Hello, World!!
input_arm=nsh-prompt
input_command=hello
shared_toy_assertions=disabled-via-self-check-exit
syscall_proof=ecall-from-u-cause-8
transition_proof=mret-and-user-text-commit
pmp_entries=4
interrupt_platform=clint,plic,uart-rx
stall_periods=0,3
max_cycles=${MAX_CYCLES}
termination=bounded-timeout-after-second-nsh-prompt
EOF
sha256sum \
  "${IMAGE}" "${P1_DIR}/nuttx.elf" "${P1_DIR}/nuttx_user.elf" \
  "${RUNNER}" "${OUT_DIR}/evidence/boot-stall-0.log" \
  "${OUT_DIR}/evidence/boot-stall-3.log" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "P2 PASS: NuttX executed hello in protected U-mode and returned to NSH"
