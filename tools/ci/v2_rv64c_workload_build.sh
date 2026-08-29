#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out_dir="${1:-${RUNNER_TEMP:-${repo_root}/out}/aethercore-v2-rv64c-workload}"
source_dir="${repo_root}/software/rv64"
cross="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}"

if [[ -z "${cross}" ]]; then
  echo "ERROR: AETHERCORE_RV64_LINUX_CROSS_COMPILE is not set" >&2
  exit 2
fi

for tool in gcc objcopy objdump readelf nm; do
  if [[ ! -x "${cross}${tool}" ]]; then
    echo "ERROR: missing RV64 tool ${cross}${tool}" >&2
    exit 3
  fi
done

gcc="${cross}gcc"
objcopy="${cross}objcopy"
objdump="${cross}objdump"
readelf="${cross}readelf"
nm="${cross}nm"

rm -rf "${out_dir}"
mkdir -p "${out_dir}"

common_flags=(
  -march=rv64imc_zicsr
  -mabi=lp64
  -mcmodel=medany
  -ffreestanding
  -fno-builtin
  -fno-stack-protector
  -fno-pic
  -fno-pie
  -ffunction-sections
  -fdata-sections
)

"${gcc}" "${common_flags[@]}" -Os \
  -c "${source_dir}/v2_rv64c_workload.c" \
  -o "${out_dir}/workload.o"

"${gcc}" "${common_flags[@]}" \
  -c "${source_dir}/v2_rv64c_start.S" \
  -o "${out_dir}/start.o"

"${gcc}" \
  -march=rv64imc_zicsr \
  -mabi=lp64 \
  -nostdlib \
  -nostartfiles \
  -static \
  -no-pie \
  -Wl,--build-id=none \
  -Wl,--gc-sections \
  -Wl,-T,"${source_dir}/v2_rv64c_workload.ld" \
  "${out_dir}/start.o" \
  "${out_dir}/workload.o" \
  -o "${out_dir}/workload.elf"

"${objcopy}" -O binary \
  "${out_dir}/workload.elf" \
  "${out_dir}/workload.bin"
"${objdump}" -d -M no-aliases \
  "${out_dir}/workload.elf" \
  > "${out_dir}/workload.dis"
"${readelf}" -h "${out_dir}/workload.elf" \
  > "${out_dir}/workload.elf-header"
"${nm}" -n "${out_dir}/workload.elf" \
  > "${out_dir}/workload.nm"

if ! grep -q 'Class:[[:space:]]*ELF64' "${out_dir}/workload.elf-header"; then
  cat "${out_dir}/workload.elf-header" >&2
  echo "ERROR: workload is not ELF64" >&2
  exit 4
fi
if ! grep -q 'Machine:[[:space:]]*RISC-V' "${out_dir}/workload.elf-header"; then
  cat "${out_dir}/workload.elf-header" >&2
  echo "ERROR: workload is not RISC-V" >&2
  exit 5
fi
if ! grep -Eq '^0000000080000000[[:space:]]+[[:alpha:]][[:space:]]+_start$' "${out_dir}/workload.nm"; then
  cat "${out_dir}/workload.nm" >&2
  echo "ERROR: _start is not linked at 0x80000000" >&2
  exit 6
fi

awk '
  /<rv64c_workload>:/ { capture = 1; print; next }
  capture && /^[[:space:]]*$/ { exit }
  capture { print }
' "${out_dir}/workload.dis" > "${out_dir}/rv64c_workload.dis"

if [[ ! -s "${out_dir}/rv64c_workload.dis" ]]; then
  echo "ERROR: rv64c_workload disassembly is empty" >&2
  exit 7
fi

compressed_count="$(grep -Ec '[[:space:]]c\.[[:alnum:]_.]+' "${out_dir}/rv64c_workload.dis" || true)"
if (( compressed_count < 2 )); then
  cat "${out_dir}/rv64c_workload.dis" >&2
  echo "ERROR: compiler workload did not retain at least two compressed instructions" >&2
  exit 8
fi

if ! grep -Eq '[[:space:]]mul[[:space:]]' "${out_dir}/rv64c_workload.dis"; then
  cat "${out_dir}/rv64c_workload.dis" >&2
  echo "ERROR: compiler workload lost the intended 32-bit MUL instruction" >&2
  exit 9
fi

if grep -Eq '[[:space:]](c\.)?(ld|sd|lw|sw|lh|sh|lb|sb)[[:space:]]' "${out_dir}/rv64c_workload.dis"; then
  cat "${out_dir}/rv64c_workload.dis" >&2
  echo "ERROR: focused RV64C workload unexpectedly depends on data memory" >&2
  exit 10
fi

image_bytes="$(stat -c '%s' "${out_dir}/workload.bin")"
if (( image_bytes <= 0 || image_bytes > 4096 )); then
  echo "ERROR: unexpected workload image size ${image_bytes}" >&2
  exit 11
fi

sha256sum "${out_dir}/workload.elf" "${out_dir}/workload.bin"
printf 'rv64c_workload_image_bytes=%s\n' "${image_bytes}"
printf 'rv64c_workload_compressed_instructions=%s\n' "${compressed_count}"
printf 'rv64c_workload_expected_a0=94\n'
cat "${out_dir}/rv64c_workload.dis"

cat > "${out_dir}/workload.meta" <<EOF
march=rv64imc_zicsr
mabi=lp64
gcc_version=$(${gcc} -dumpfullversion)
image_bytes=${image_bytes}
compressed_instructions=${compressed_count}
expected_a0=94
EOF

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    printf 'AETHERCORE_V2_RV64C_WORKLOAD_BIN=%s\n' "${out_dir}/workload.bin"
    printf 'AETHERCORE_V2_RV64C_WORKLOAD_ELF=%s\n' "${out_dir}/workload.elf"
    printf 'AETHERCORE_V2_RV64C_WORKLOAD_DIR=%s\n' "${out_dir}"
    printf 'AETHERCORE_V2_RV64C_COMPRESSED_COUNT=%s\n' "${compressed_count}"
  } >> "${GITHUB_ENV}"
fi
