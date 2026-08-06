#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/software/nuttx/manifest.env"
OUT_DIR="${ROOT_DIR}/build/nuttx-n2"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/nuttx"
SOURCE_DIR="${CACHE_ROOT}/sources"
KCONFIGLIB_VERSION="14.1.0"
KCONFIGLIB_DIR="${CACHE_ROOT}/host-tools/kconfiglib-${KCONFIGLIB_VERSION}"
SIM_ROOT="${CACHE_ROOT}/sim/n2-rv32im"
RTL_DIR="${SIM_ROOT}/rtl"
OBJ_DIR="${SIM_ROOT}/obj"
MAX_CYCLES="${AETHERCORE_NUTTX_N2_MAX_CYCLES:-12000000}"
JOBS="${NUTTX_JOBS:-6}"

source "${MANIFEST}"
NUTTX_DIR="${SOURCE_DIR}/nuttx-${NUTTX_VERSION}"
APPS_DIR="${SOURCE_DIR}/apps-${NUTTX_VERSION}"

for command in make python3 verilator riscv64-unknown-elf-gcc \
  riscv64-unknown-elf-objcopy riscv64-unknown-elf-readelf \
  riscv64-unknown-elf-nm sha256sum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "N2 FAIL: required command not found: ${command}" >&2
    exit 2
  }
done

[[ -d "${NUTTX_DIR}" && -d "${APPS_DIR}" ]] || {
  echo "N2 FAIL: N1 pinned source trees are missing" >&2
  exit 2
}
[[ -x "${KCONFIGLIB_DIR}/bin/olddefconfig" ]] || {
  echo "N2 FAIL: cached kconfiglib ${KCONFIGLIB_VERSION} is missing" >&2
  exit 2
}

chmod +x "${ROOT_DIR}/mill" "${ROOT_DIR}/tools/ci/kconfig-tweak"
GENROMFS_BIN="$(bash "${ROOT_DIR}/tools/ci/ensure_genromfs.sh" "${CACHE_ROOT}")"
export PATH="$(dirname "${GENROMFS_BIN}"):${KCONFIGLIB_DIR}/bin:${ROOT_DIR}/tools/ci:${PATH}"
export PYTHONPATH="${KCONFIGLIB_DIR}${PYTHONPATH:+:${PYTHONPATH}}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/evidence" "${RTL_DIR}" "${OBJ_DIR}"

python3 "${ROOT_DIR}/tools/make_aethercore_nuttx_overlay.py" "${NUTTX_DIR}" \
  2>&1 | tee "${OUT_DIR}/evidence/overlay.log"

pushd "${NUTTX_DIR}" >/dev/null
make olddefconfig CROSSDEV=riscv64-unknown-elf-

required_enabled=(
  CONFIG_AETHERCORE_UART
  CONFIG_SERIAL
  CONFIG_DEV_CONSOLE
  CONFIG_SUPPRESS_INTERRUPTS
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_M
  CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI
  CONFIG_RISCV_TOOLCHAIN_GNU_RV64
)
for symbol in "${required_enabled[@]}"; do
  grep -Fqx "${symbol}=y" .config || {
    echo "N2 FAIL: resolved configuration did not enable ${symbol}" >&2
    exit 3
  }
done

forbidden_enabled=(
  CONFIG_16550_UART
  CONFIG_16550_UART0
  CONFIG_16550_UART0_SERIAL_CONSOLE
  CONFIG_FS_HOSTFS
  CONFIG_RISCV_SEMIHOSTING_HOSTFS
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_A
  CONFIG_ARCH_CHIP_QEMU_RV_ISA_C
)
for symbol in "${forbidden_enabled[@]}"; do
  if grep -Fqx "${symbol}=y" .config; then
    echo "N2 FAIL: resolved configuration enabled forbidden ${symbol}" >&2
    exit 3
  fi
done

grep -Fqx 'CONFIG_RAM_START=0x80000000' .config || {
  echo "N2 FAIL: RAM does not start at 0x80000000" >&2
  exit 3
}
grep -Fqx 'CONFIG_RAM_SIZE=67108856' .config || {
  echo "N2 FAIL: RAM size does not match the bounded 64 MiB simulation map" >&2
  exit 3
}

cp .config "${OUT_DIR}/nuttx.config"
make -j"${JOBS}" CROSSDEV=riscv64-unknown-elf- \
  2>&1 | tee "${OUT_DIR}/build.log"

[[ -s nuttx ]] || {
  echo "N2 FAIL: NuttX ELF was not produced" >&2
  exit 4
}

# The pinned rv-virt build does not guarantee a flat image side effect after a
# custom linker script.  Generate the simulator image explicitly from the
# verified ELF instead of relying on an optional board Makefile artifact.
riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin
[[ -s nuttx.bin ]] || {
  echo "N2 FAIL: objcopy did not produce a non-empty flat image" >&2
  exit 4
}

cp nuttx "${OUT_DIR}/nuttx.elf"
cp nuttx.bin "${OUT_DIR}/nuttx.bin"
[[ -f nuttx.map ]] && cp nuttx.map "${OUT_DIR}/nuttx.map"

riscv64-unknown-elf-readelf -h nuttx > "${OUT_DIR}/evidence/elf-header.txt"
riscv64-unknown-elf-readelf -l nuttx > "${OUT_DIR}/evidence/elf-program-headers.txt"
riscv64-unknown-elf-readelf -A nuttx > "${OUT_DIR}/evidence/elf-attributes.txt"
riscv64-unknown-elf-nm -n nuttx > "${OUT_DIR}/evidence/symbols.txt"

entry="$(awk '/Entry point address:/ {print $4}' "${OUT_DIR}/evidence/elf-header.txt")"
[[ "${entry}" == "0x80000000" ]] || {
  echo "N2 FAIL: ELF entry is ${entry}, expected 0x80000000" >&2
  exit 4
}
grep -Eq '[[:space:]]nx_start$' "${OUT_DIR}/evidence/symbols.txt" || {
  echo "N2 FAIL: linked image does not expose nx_start" >&2
  exit 4
}
grep -Eq '[[:space:]]aethercore_serialinit$' "${OUT_DIR}/evidence/symbols.txt" || {
  echo "N2 FAIL: native AetherCore console was not linked" >&2
  exit 4
}
popd >/dev/null

rm -rf "${RTL_DIR}"
mkdir -p "${RTL_DIR}" "${OBJ_DIR}"
"${ROOT_DIR}/mill" aethercore.runMain aethercore.ElaborateZephyr \
  --target-dir "${RTL_DIR}"
mapfile -t rtl_sources < <(find "${RTL_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
if (( ${#rtl_sources[@]} == 0 )); then
  echo "N2 FAIL: no generated AetherCore SystemVerilog sources" >&2
  exit 5
fi

verilator --cc --exe --build --trace -Wall -Wno-fatal \
  --top-module AetherCoreSimTop -Mdir "${OBJ_DIR}" \
  -CFLAGS "-std=c++20 -O2" -LDFLAGS "-ldl" \
  "${rtl_sources[@]}" "${ROOT_DIR}/sim/sim_main.cpp" \
  "${ROOT_DIR}/sim/nemu_difftest.cpp" \
  2>&1 | tee "${OUT_DIR}/evidence/simulator-build.log"

runner="${OBJ_DIR}/VAetherCoreSimTop"
[[ -x "${runner}" ]] || {
  echo "N2 FAIL: AetherCore simulation runner was not produced" >&2
  exit 5
}

set +e
"${runner}" "${OUT_DIR}/nuttx.bin" \
  --max-cycles "${MAX_CYCLES}" --self-check-exit \
  2>&1 | tee "${OUT_DIR}/evidence/boot.log"
rc=${PIPESTATUS[0]}
set -e

# N2 intentionally has polling TX only and keeps all interrupts suppressed.
# After NSH reaches its prompt there is no RX byte to consume and no guest exit
# request, so the bounded simulator timeout is the expected termination.
[[ "${rc}" -eq 2 ]] || {
  echo "N2 FAIL: simulation returned ${rc}, expected bounded timeout after NSH prompt" >&2
  exit 6
}
grep -Fq 'nsh>' "${OUT_DIR}/evidence/boot.log" || {
  echo "N2 FAIL: NSH prompt was not observed" >&2
  exit 6
}
grep -Fq "FAIL: timeout after ${MAX_CYCLES} cycles" "${OUT_DIR}/evidence/boot.log" || {
  echo "N2 FAIL: simulation did not end at the configured bound" >&2
  exit 6
}

cat > "${OUT_DIR}/evidence/result.txt" <<EOF
status=PASS
contract=nuttx-13.0.0-aethercore-n2-polling-uart-nsh-v1
image=${OUT_DIR}/nuttx.bin
runner=${runner}
entry=0x80000000
uart_tx=0x10000000
max_cycles=${MAX_CYCLES}
termination=bounded-timeout-after-nsh-prompt
prompt=nsh>
interrupts=suppressed-until-n3
profile=rv32im_zicsr_zifencei
stop_on_trap=false
stop_on_wfi=false
EOF

sha256sum "${OUT_DIR}/nuttx.elf" "${OUT_DIR}/nuttx.bin" \
  "${OUT_DIR}/nuttx.config" "${OUT_DIR}/evidence/boot.log" \
  "${runner}" > "${OUT_DIR}/evidence/artifacts.sha256"
cat "${OUT_DIR}/evidence/result.txt"
echo "N2 PASS: AetherCore booted pinned NuttX to the polling-UART NSH prompt"
