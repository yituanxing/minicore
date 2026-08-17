#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"

BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"
MARKER="${BUILD_DIR}/evidence/runtime-image-cache.txt"
RESULT="${BUILD_DIR}/result.txt"
IMAGE="${BUILD_DIR}/obj/arch/riscv/boot/Image"
VMLINUX="${BUILD_DIR}/obj/vmlinux"

BASE_KERNEL_BUILD_DIR="${ROOT_DIR}/build/l32-linux"
if [[ "${L32_USERSPACE_REQUIRE_C}" -eq 1 ]]; then
  BASE_KERNEL_BUILD_DIR="${ROOT_DIR}/build/l32-linux-rv32imac"
fi
BASE_KERNEL_IMAGE="${BASE_KERNEL_BUILD_DIR}/obj/arch/riscv/boot/Image"
BASE_KERNEL_RESULT="${BASE_KERNEL_BUILD_DIR}/result.txt"

inputs=(
  "${ROOT_DIR}/software/l32/manifest.env"
  "${ROOT_DIR}/software/l32_busybox/manifest.env"
  "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
  "${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
  "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
)
dynamic=(
  "${BASE_KERNEL_IMAGE}"
  "${BASE_KERNEL_RESULT}"
  "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/busybox-src/busybox"
  "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/result.txt"
  "${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/l32-runtime-probe"
  "${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/result.txt"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/lua"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/lua-smoke.lua"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/sqlite-smoke"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/bash"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/bash-smoke.sh"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/busybox-real"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/zlib-smoke"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/libpng-smoke"
  "${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/result.txt"
)

hash_or_missing() {
  if [[ -f "$1" ]]; then sha256sum "$1"; else printf 'missing  %s\n' "$1"; fi
}
key="$({
  printf 'profile=%s\nisa=%s\nrequire_c=%s\n' \
    "${L32_USERSPACE_PROFILE}" "${L32_USERSPACE_EFFECTIVE_ISA}" "${L32_USERSPACE_REQUIRE_C}"
  for f in "${inputs[@]}" "${dynamic[@]}"; do hash_or_missing "$f"; done
} | sha256sum | awk '{print $1}')"

hit=1
[[ -f "${RESULT}" ]] && grep -qx 'L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS' "${RESULT}" || hit=0
[[ -f "${RESULT}" ]] && grep -qx "profile=${L32_USERSPACE_PROFILE}" "${RESULT}" || hit=0
[[ -f "${MARKER}" ]] && [[ "$(awk '$1=="input_key" {print $2; exit}' "${MARKER}" 2>/dev/null)" == "${key}" ]] || hit=0
[[ -f "${MARKER}" ]] && [[ "$(awk '$1=="profile" {print $2; exit}' "${MARKER}" 2>/dev/null)" == "${L32_USERSPACE_PROFILE}" ]] || hit=0
for f in "${IMAGE}" "${VMLINUX}"; do
  rel="${f#${ROOT_DIR}/}"
  expected="$(awk -v p="${rel}" '$1=="sha256" && $3==p {print $2; exit}' "${MARKER}" 2>/dev/null || true)"
  [[ -n "${expected}" && -s "${f}" && "$(sha256sum "${f}" | awk '{print $1}')" == "${expected}" ]] || hit=0
done
if (( hit )); then
  echo "L32_RUNTIME_IMAGE_CACHE_HIT profile=${L32_USERSPACE_PROFILE} key=${key}"
  exit 0
fi

echo "L32_RUNTIME_IMAGE_CACHE_MISS profile=${L32_USERSPACE_PROFILE} key=${key}"
"${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
grep -qx 'L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS' "${RESULT}"
grep -qx "profile=${L32_USERSPACE_PROFILE}" "${RESULT}"
mkdir -p "$(dirname "${MARKER}")"
tmp="${MARKER}.tmp.$$"
{
  echo "profile ${L32_USERSPACE_PROFILE}"
  echo "isa ${L32_USERSPACE_EFFECTIVE_ISA}"
  echo "require_c ${L32_USERSPACE_REQUIRE_C}"
  echo "input_key ${key}"
  for f in "${IMAGE}" "${VMLINUX}"; do
    echo "sha256 $(sha256sum "${f}" | awk '{print $1}') ${f#${ROOT_DIR}/}"
  done
} > "${tmp}"
mv "${tmp}" "${MARKER}"
echo "L32_RUNTIME_IMAGE_CACHE_MARK profile=${L32_USERSPACE_PROFILE} key=${key}"
