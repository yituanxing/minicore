#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/l32/manifest.env"
source "${ROOT_DIR}/software/l32/linux-runtime.env"

LINUX_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
VMLINUX="${LINUX_BUILD_DIR}/obj/vmlinux"
IMAGE="${LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
CONFIG="${LINUX_BUILD_DIR}/evidence/resolved.config"
FW_BUILD_DIR="${ROOT_DIR}/build/l32-linux-opensbi"

chmod +x "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
if ! "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" check "${LINUX_BUILD_DIR}"; then
  echo "L32 Linux exact-output cache miss: rebuilding the frozen kernel once." >&2
  chmod +x "${ROOT_DIR}/tools/ci/l32_linux_build.sh"
  "${ROOT_DIR}/tools/ci/l32_linux_build.sh"
  "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh" mark "${LINUX_BUILD_DIR}"
fi

for file in "${VMLINUX}" "${IMAGE}" "${CONFIG}"; do
  [[ -s "${file}" ]] || { echo "ERROR: missing frozen Linux artifact ${file}" >&2; exit 30; }
done

printf '%s  %s\n' "${L32_LINUX_VMLINUX_SHA256}" "${VMLINUX}" | sha256sum -c -
printf '%s  %s\n' "${L32_LINUX_IMAGE_SHA256}" "${IMAGE}" | sha256sum -c -
printf '%s  %s\n' "${L32_LINUX_CONFIG_SHA256}" "${CONFIG}" | sha256sum -c -
actual_image_bytes="$(stat -c '%s' "${IMAGE}")"
[[ "${actual_image_bytes}" == "${L32_LINUX_IMAGE_BYTES}" ]] || {
  echo "ERROR: frozen Linux Image size ${actual_image_bytes} != ${L32_LINUX_IMAGE_BYTES}" >&2
  exit 31
}

rm -rf "${FW_BUILD_DIR}"
L32_OPENSBI_BUILD_DIR="${FW_BUILD_DIR}" \
L32_FW_PAYLOAD_PATH="${IMAGE}" \
L32_TOOLCHAIN_MODE=gcc \
L32_JOBS="${L32_JOBS:-$(nproc)}" \
  "${ROOT_DIR}/tools/ci/l32_opensbi_build.sh"

FW_ELF="${FW_BUILD_DIR}/build/platform/${L32_PLATFORM}/firmware/fw_payload.elf"
FW_BIN="${FW_BUILD_DIR}/build/platform/${L32_PLATFORM}/firmware/fw_payload.bin"
[[ -s "${FW_ELF}" && -s "${FW_BIN}" ]] || {
  echo "ERROR: OpenSBI did not produce the Linux payload firmware" >&2
  exit 32
}

OBJDUMP="${L32_CROSS_COMPILE_PREFIX}objdump"
command -v "${OBJDUMP}" >/dev/null 2>&1 || { echo "ERROR: missing ${OBJDUMP}" >&2; exit 33; }
payload_vma="$(${OBJDUMP} -h "${FW_ELF}" | awk '$2 == ".payload" {print "0x" $4; exit}')"
[[ -n "${payload_vma}" ]] || { echo "ERROR: OpenSBI firmware has no .payload section" >&2; exit 34; }
[[ "$((payload_vma))" -eq "$((L32_LINUX_PHYS_ENTRY))" ]] || {
  echo "ERROR: Linux payload VMA ${payload_vma} != ${L32_LINUX_PHYS_ENTRY}" >&2
  exit 35
}

mkdir -p "${FW_BUILD_DIR}/evidence"
{
  echo "L32_LINUX_FW_PAYLOAD_RESULT: status=PASS"
  echo "linux_image=${IMAGE}"
  echo "linux_image_sha256=${L32_LINUX_IMAGE_SHA256}"
  echo "linux_image_bytes=${actual_image_bytes}"
  echo "linux_phys_entry=${L32_LINUX_PHYS_ENTRY}"
  echo "linux_payload_vma=${payload_vma}"
  echo "firmware=${FW_ELF}"
  echo "firmware_bin=${FW_BIN}"
} | tee "${FW_BUILD_DIR}/linux-payload-result.txt"
sha256sum "${FW_ELF}" "${FW_BIN}" "${IMAGE}" \
  | tee "${FW_BUILD_DIR}/evidence/linux-payload-sha256.txt"
