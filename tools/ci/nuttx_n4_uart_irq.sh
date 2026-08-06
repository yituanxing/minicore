#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-n4"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
SIM_ROOT="${CACHE_ROOT}/sim/n2-rv32im"
RUNNER="${SIM_ROOT}/obj/VAetherCoreSimTop"
MAX_CYCLES="${AETHERCORE_NUTTX_N4_MAX_CYCLES:-12000000}"
RX_START_CYCLE="${AETHERCORE_NUTTX_N4_RX_START_CYCLE:-8000000}"
RX_GAP_CYCLES="${AETHERCORE_NUTTX_N4_RX_GAP_CYCLES:-1000}"
JOBS="${NUTTX_JOBS:-6}"
STALL_PERIODS=(0 3)

source "${MANIFEST}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"

for command in make python3 riscv64-unknown-elf-objcopy \
  riscv64-unknown-elf-readelf riscv64-unknown-elf-nm sha256sum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N4 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "N4 FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
  exit 2
}
[[ -x "${RUNNER}" ]] || {
  echo "N4 FAIL: cached AetherCore runner is missing" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/tools/ci/kconfig-tweak"
GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"
python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_n4_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/overlay.log"

pushd "${NUTTX_DIR}" >/dev/null
make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/evidence/olddefconfig.log"

required_enabled=(
  CONFIG_AETHERCORE_UART
  CONFIG_AETHERCORE_TIMER
  CONFIG_AETHERCORE_UART_RX_IRQ
  CONFIG_SERIAL
  CONFIG_DEV_CONSOLE
)
for symbol in "${required_enabled[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "N4 FAIL: resolved configuration did not enable ${symbol}" >&2
    exit 3
  }
done
if grep -Fqx 'CONFIG_SUPPRESS_INTERRUPTS=y' .config; then
  echo "N4 FAIL: interrupt dispatch was suppressed" >&2
  exit 3
fi
grep -Fqx 'CONFIG_INIT_ENTRYPOINT="nsh_main"' .config || {
  echo "N4 FAIL: NSH is not the N4 init entrypoint" >&2
  exit 3
}

cp .config "${OUT_DIR}/nuttx.config"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"
[[ -s nuttx ]] || {
  echo "N4 FAIL: NuttX ELF was not produced" >&2
  exit 4
}
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
[[ -s nuttx.bin ]] || {
  echo "N4 FAIL: flat image was not produced" >&2
  exit 4
}

cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/elf-header.txt"
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/symbols.txt"
for symbol in nsh_main aethercore_serialinit riscv_dispatch_irq; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/symbols.txt" || {
    echo "N4 FAIL: linked image is missing ${symbol}" >&2
    exit 4
  }
done
popd >/dev/null

rx_args=(
  --rx-byte 0x65
  --rx-byte 0x63
  --rx-byte 0x68
  --rx-byte 0x6f
  --rx-byte 0x20
  --rx-byte 0x4e
  --rx-byte 0x34
  --rx-byte 0x2d
  --rx-byte 0x49
  --rx-byte 0x52
  --rx-byte 0x51
  --rx-byte 0x2d
  --rx-byte 0x50
  --rx-byte 0x41
  --rx-byte 0x53
  --rx-byte 0x53
  --rx-byte 0x0a
  --rx-start-cycle "${RX_START_CYCLE}"
  --rx-gap-cycles "${RX_GAP_CYCLES}"
)

run_positive() {
  local stall_period="$1"
  local log_file="${OUT_DIR}/evidence/boot-stall-${stall_period}.log"
  local args=(
    "${OUT_DIR}/nuttx.bin"
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
    echo "N4 FAIL: simulation returned ${rc}, expected bounded timeout (stall=${stall_period})" >&2
    exit 5
  }
  grep -Fqx $'N4-IRQ-PASS\r' "${log_file}" || {
    echo "N4 FAIL: injected NSH command did not produce its output (stall=${stall_period})" >&2
    exit 5
  }
  local prompt_count
  prompt_count="$(grep -o 'nsh>' "${log_file}" | wc -l)"
  [[ "${prompt_count}" -ge 2 ]] || {
    echo "N4 FAIL: NSH did not return to its prompt after RX ISR (stall=${stall_period})" >&2
    exit 5
  }
  grep -Fq "FAIL: timeout after ${MAX_CYCLES} cycles" "${log_file}" || {
    echo "N4 FAIL: simulation did not terminate at the configured bound" >&2
    exit 5
  }
  if grep -Eq 'PANIC|EXCEPTION:|irq_unexpected_isr' "${log_file}"; then
    echo "N4 FAIL: panic or unexpected trap observed" >&2
    exit 5
  fi
}

for stall_period in "${STALL_PERIODS[@]}"; do
  run_positive "${stall_period}"
done

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-n4-uart-rx-plic-nsh-v1
image=${OUT_DIR}/nuttx.bin
runner=${RUNNER}
max_cycles=${MAX_CYCLES}
uart_rx=0x10000100
uart_rx_irq_source=1
plic=0x0c000000
rx_command=echo-N4-IRQ-PASS
rx_start_cycle=${RX_START_CYCLE}
rx_gap_cycles=${RX_GAP_CYCLES}
stall_periods=0,3
proof=plic-claim-dispatch-complete-and-nsh-fd-console-return
termination=bounded-timeout-after-second-nsh-prompt
EOF
sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx.bin" \
  "${OUT_DIR}/nuttx.config" "${OUT_DIR}/evidence/boot-stall-0.log" \
  "${OUT_DIR}/evidence/boot-stall-3.log" "${RUNNER}" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "N4 PASS: UART RX traversed PLIC and returned to NSH"
