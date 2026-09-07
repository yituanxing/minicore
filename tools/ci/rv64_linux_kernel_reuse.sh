#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?provision tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BUILD_DIR="${ROOT_DIR}/build/rv64-linux-early"
EVIDENCE_DIR="${BUILD_DIR}/evidence"

recipe_key="$({
  printf '%s\n' \
    "recipe=${RV64_LINUX_RECIPE_VERSION}" \
    "linux=${RV64_LINUX_SHA256}" \
    "defconfig=${RV64_LINUX_DEFCONFIG}" \
    "gcc=$("${CROSS}gcc" -dumpfullversion)" \
    "target=$("${CROSS}gcc" -dumpmachine)" \
    "build_user=${RV64_LINUX_BUILD_USER}" \
    "build_host=${RV64_LINUX_BUILD_HOST}" \
    "build_version=${RV64_LINUX_BUILD_VERSION}" \
    "build_timestamp=${RV64_LINUX_BUILD_TIMESTAMP}" \
    "build_tz=${RV64_LINUX_BUILD_TZ}"
  sha256sum "${ROOT_DIR}/software/rv64/linux_early.env" "${ROOT_DIR}/tools/ci/rv64_linux_early_build.sh"
} | sha256sum | awk '{print $1}')"

KERNEL_CACHE="${CACHE_ROOT}/rv64/linux-build/${recipe_key}"
OBJ_DIR="${KERNEL_CACHE}/obj"
QUALIFIED="${KERNEL_CACHE}/qualified.env"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
VMLINUX="${OBJ_DIR}/vmlinux"
CONFIG="${OBJ_DIR}/.config"

valid=1
[[ -d "${SOURCE_DIR}" ]] || valid=0
[[ -s "${IMAGE}" && -s "${VMLINUX}" && -s "${CONFIG}" && -s "${QUALIFIED}" ]] || valid=0
if [[ "${valid}" == "1" ]]; then
  grep -qx "recipe_key=${recipe_key}" "${QUALIFIED}" || valid=0
  grep -qx 'CONFIG_64BIT=y' "${CONFIG}" || valid=0
  grep -qx 'CONFIG_MMU=y' "${CONFIG}" || valid=0
  grep -qx '# CONFIG_RISCV_ISA_C is not set' "${CONFIG}" || valid=0
  grep -qx '# CONFIG_FPU is not set' "${CONFIG}" || valid=0
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || valid=0
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || valid=0
fi

if [[ "${valid}" != "1" ]]; then
  echo "RV64 kernel-only cache miss; falling back to full qualified baseline build"
  exec bash "${ROOT_DIR}/tools/ci/rv64_linux_early_build.sh"
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${EVIDENCE_DIR}"
cp "${CONFIG}" "${EVIDENCE_DIR}/resolved.config"
cp "${QUALIFIED}" "${EVIDENCE_DIR}/qualified.env"
sha256sum "${VMLINUX}" "${IMAGE}" "${CONFIG}" > "${EVIDENCE_DIR}/kernel-sha256.txt"

{
  echo "RV64_LINUX_EARLY_BUILD_RESULT: status=PASS"
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_source_sha256=${RV64_LINUX_SHA256}"
  echo "linux_recipe=${RV64_LINUX_RECIPE_VERSION}"
  echo "kernel_recipe_key=${recipe_key}"
  echo "kernel_image=${IMAGE}"
  echo "kernel_image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
  echo "kernel_reuse_only=1"
} | tee "${BUILD_DIR}/result.txt"

echo "RV64 kernel-only qualified cache hit: ${recipe_key}"
