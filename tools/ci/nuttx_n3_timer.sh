#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-n3"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
SIM_ROOT="${CACHE_ROOT}/sim/n2-rv32im"
RUNNER="${SIM_ROOT}/obj/VAetherCoreSimTop"
MAX_CYCLES="${AETHERCORE_NUTTX_N3_MAX_CYCLES:-12000000}"
JOBS="${NUTTX_JOBS:-6}"

source "${MANIFEST}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"

for command in make python3 riscv64-unknown-elf-objcopy \
  riscv64-unknown-elf-readelf riscv64-unknown-elf-nm sha256sum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N3 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "N3 FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
  exit 2
}
[[ -x "${RUNNER}" ]] || {
  echo "N3 FAIL: cached N2 AetherCore runner is missing" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/tools/ci/kconfig-tweak"
GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence"
python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_n3_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/overlay.log"

pushd "${NUTTX_DIR}" >/dev/null
make olddefconfig CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/evidence/olddefconfig.log"

required_enabled=(
  CONFIG_AETHERCORE_UART
  CONFIG_AETHERCORE_TIMER
  CONFIG_TESTING_OSTEST
  CONFIG_TESTING_OSTEST_WAITRESULT
)
for symbol in "${required_enabled[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "N3 FAIL: resolved configuration did not enable ${symbol}" >&2
    exit 3
  }
done
if grep -Fqx 'CONFIG_SUPPRESS_INTERRUPTS=y' .config; then
  echo "N3 FAIL: synchronous trap dispatch was suppressed" >&2
  exit 3
fi
grep -Fqx 'CONFIG_INIT_ENTRYPOINT="ostest_main"' .config || {
  echo "N3 FAIL: ostest is not the N3 init entrypoint" >&2
  exit 3
}
grep -Fqx 'CONFIG_TESTING_OSTEST_LOOPS=1' .config || exit 3
grep -Fqx 'CONFIG_TESTING_OSTEST_RR_RANGE=100' .config || exit 3
grep -Fqx 'CONFIG_TESTING_OSTEST_RR_RUNS=1' .config || exit 3

cp .config "${OUT_DIR}/nuttx.config"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"
[[ -s nuttx ]] || {
  echo "N3 FAIL: NuttX ELF was not produced" >&2
  exit 4
}
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
[[ -s nuttx.bin ]] || {
  echo "N3 FAIL: flat image was not produced" >&2
  exit 4
}

cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"
riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/elf-header.txt"
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/symbols.txt"
for symbol in ostest_main up_timer_initialize riscv_mtimer_initialize; do
  grep -Eq "[[:space:]]${symbol}$" "${OUT_DIR}/evidence/symbols.txt" || {
    echo "N3 FAIL: linked image is missing ${symbol}" >&2
    exit 4
  }
done
popd >/dev/null

set +e
"${RUNNER}" "${OUT_DIR}/nuttx.bin" \
  --max-cycles "${MAX_CYCLES}" --self-check-exit \
  2>&1 | tee "${OUT_DIR}/evidence/boot.log"
rc=${PIPESTATUS[0]}
set -e

[[ "${rc}" -eq 2 ]] || {
  echo "N3 FAIL: simulation returned ${rc}, expected bounded timeout" >&2
  exit 5
}
grep -Fq 'user_main: Begin argument test' "${OUT_DIR}/evidence/boot.log" || {
  echo "N3 FAIL: ostest did not wake after its initial 500 ms sleep" >&2
  exit 5
}
grep -Fq "FAIL: timeout after ${MAX_CYCLES} cycles" "${OUT_DIR}/evidence/boot.log" || {
  echo "N3 FAIL: simulation did not terminate at the configured bound" >&2
  exit 5
}
if grep -Eq 'PANIC|EXCEPTION:|irq_unexpected_isr' "${OUT_DIR}/evidence/boot.log"; then
  echo "N3 FAIL: panic or unexpected trap observed" >&2
  exit 5
fi

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-n3-timer-ostest-v1
image=${OUT_DIR}/nuttx.bin
runner=${RUNNER}
max_cycles=${MAX_CYCLES}
timer_mtime=0x0200bff8
timer_mtimecmp=0x02004000
timer_frequency_hz=10000000
proof=ostest-woke-after-500ms-usleep
external_interrupts=disabled-until-n4
termination=bounded-timeout
EOF
sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx.bin" \
  "${OUT_DIR}/nuttx.config" "${OUT_DIR}/evidence/boot.log" \
  > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "N3 PASS: machine timer scheduled and woke bounded ostest"
