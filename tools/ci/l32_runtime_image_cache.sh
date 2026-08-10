#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox"
MARKER="${BUILD_DIR}/evidence/runtime-image-cache.txt"
RESULT="${BUILD_DIR}/result.txt"
IMAGE="${BUILD_DIR}/obj/arch/riscv/boot/Image"
VMLINUX="${BUILD_DIR}/obj/vmlinux"

inputs=(
  "${ROOT_DIR}/software/l32/manifest.env"
  "${ROOT_DIR}/software/l32_busybox/manifest.env"
  "${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
  "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
)
dynamic=(
  "${ROOT_DIR}/build/l32-linux/obj/arch/riscv/boot/Image"
  "${ROOT_DIR}/build/l32-busybox/busybox-src/busybox"
  "${ROOT_DIR}/build/l32-runtime-probe/l32-runtime-probe"
  "${ROOT_DIR}/build/l32-real-programs/lua"
  "${ROOT_DIR}/build/l32-real-programs/lua-smoke.lua"
  "${ROOT_DIR}/build/l32-real-programs/sqlite-smoke"
  "${ROOT_DIR}/build/l32-real-programs/bash"
  "${ROOT_DIR}/build/l32-real-programs/bash-smoke.sh"
  "${ROOT_DIR}/build/l32-real-programs/zlib-smoke"
)

hash_or_missing() {
  if [[ -f "$1" ]]; then sha256sum "$1"; else printf 'missing  %s\n' "$1"; fi
}
key="$({ for f in "${inputs[@]}" "${dynamic[@]}"; do hash_or_missing "$f"; done; } | sha256sum | awk '{print $1}')"

hit=1
[[ -f "${RESULT}" ]] && grep -qx 'L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS' "${RESULT}" || hit=0
[[ -f "${MARKER}" ]] && [[ "$(awk '$1=="input_key" {print $2; exit}' "${MARKER}" 2>/dev/null)" == "${key}" ]] || hit=0
for f in "${IMAGE}" "${VMLINUX}"; do
  rel="${f#${ROOT_DIR}/}"
  expected="$(awk -v p="${rel}" '$1=="sha256" && $3==p {print $2; exit}' "${MARKER}" 2>/dev/null || true)"
  [[ -n "${expected}" && -s "${f}" && "$(sha256sum "${f}" | awk '{print $1}')" == "${expected}" ]] || hit=0
done
if (( hit )); then echo "L32_RUNTIME_IMAGE_CACHE_HIT key=${key}"; exit 0; fi

echo "L32_RUNTIME_IMAGE_CACHE_MISS key=${key}"
"${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
grep -qx 'L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS' "${RESULT}"
mkdir -p "$(dirname "${MARKER}")"
tmp="${MARKER}.tmp.$$"
{
  echo "input_key ${key}"
  for f in "${IMAGE}" "${VMLINUX}"; do echo "sha256 $(sha256sum "${f}" | awk '{print $1}') ${f#${ROOT_DIR}/}"; done
} > "${tmp}"
mv "${tmp}" "${MARKER}"
echo "L32_RUNTIME_IMAGE_CACHE_MARK key=${key}"
