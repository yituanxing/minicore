#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:-}"
shift || true

if [[ -z "${TARGET}" || "$#" -eq 0 ]]; then
  echo "usage: $0 <busybox|minimal-initramfs|busybox-initramfs|linux-payload|minimal-payload|busybox-payload> <build-command...>" >&2
  exit 2
fi

hash_file_if_present() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    sha256sum "${path}"
  else
    printf 'missing  %s\n' "${path}"
  fi
}

compiler_identity() {
  local compiler="$1"
  local resolved
  resolved="$(command -v "${compiler}" 2>/dev/null || true)"
  if [[ -n "${resolved}" && -f "${resolved}" ]]; then
    printf 'compiler=%s\n' "${resolved}"
    "${compiler}" --version | head -n 1
    "${compiler}" -dumpmachine 2>/dev/null || true
    sha256sum "${resolved}"
  else
    printf 'compiler=missing:%s\n' "${compiler}"
  fi
}

result_file=""
result_marker=""
marker_file=""
inputs=()
outputs=()
dynamic_inputs=()

case "${TARGET}" in
  busybox)
    # shellcheck disable=SC1091
    source "${ROOT_DIR}/software/l32_busybox/manifest.env"
    base_gcc="${L32_USERSPACE_CROSS_COMPILE_PREFIX}gcc"
    result_file="${ROOT_DIR}/build/l32-busybox/result.txt"
    result_marker="L32_BUSYBOX_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-busybox/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_busybox_build.sh"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-busybox/busybox-src/busybox"
      "${ROOT_DIR}/build/l32-busybox/musl-probe"
      "${ROOT_DIR}/build/l32-busybox/musl-prefix/bin/musl-gcc"
    )
    dynamic_inputs+=("$(compiler_identity "${base_gcc}")")
    if [[ -n "${REALGCC:-}" ]]; then
      dynamic_inputs+=("$(hash_file_if_present "${REALGCC}")")
    fi
    ;;
  minimal-initramfs)
    result_file="${ROOT_DIR}/build/l32-linux-initramfs/result.txt"
    result_marker="L32_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-linux-initramfs/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/software/l32_userspace/minimal_init.S"
      "${ROOT_DIR}/tools/ci/l32_minimal_initramfs_build.sh"
      "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-linux-initramfs/minimal-init/init"
      "${ROOT_DIR}/build/l32-linux-initramfs/minimal-init/initramfs.list"
      "${ROOT_DIR}/build/l32-linux-initramfs/obj/vmlinux"
      "${ROOT_DIR}/build/l32-linux-initramfs/obj/arch/riscv/boot/Image"
    )
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-linux/obj/arch/riscv/boot/Image")")
    ;;
  busybox-initramfs)
    result_file="${ROOT_DIR}/build/l32-linux-busybox/result.txt"
    result_marker="L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-linux-busybox/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
      "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-linux-busybox/rootfs/init"
      "${ROOT_DIR}/build/l32-linux-busybox/rootfs/initramfs.list"
      "${ROOT_DIR}/build/l32-linux-busybox/obj/vmlinux"
      "${ROOT_DIR}/build/l32-linux-busybox/obj/arch/riscv/boot/Image"
    )
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-linux/obj/arch/riscv/boot/Image")")
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-busybox/busybox-src/busybox")")
    ;;
  linux-payload)
    # shellcheck disable=SC1091
    source "${ROOT_DIR}/software/l32/manifest.env"
    result_file="${ROOT_DIR}/build/l32-linux-boot/result.txt"
    result_marker="L32_LINUX_PAYLOAD_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-linux-boot/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_linux_payload_build.sh"
      "${ROOT_DIR}/tools/ci/make_l32_dtb.py"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-linux-boot/opensbi/platform/generic/firmware/fw_payload.elf"
      "${ROOT_DIR}/build/l32-linux-boot/opensbi/platform/generic/firmware/fw_payload.bin"
      "${ROOT_DIR}/build/l32-linux-boot/aethercore-rv32-linux.dtb"
    )
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-linux/obj/arch/riscv/boot/Image")")
    dynamic_inputs+=("$(compiler_identity "${L32_CROSS_COMPILE_PREFIX}gcc")")
    ;;
  minimal-payload)
    # shellcheck disable=SC1091
    source "${ROOT_DIR}/software/l32/manifest.env"
    result_file="${ROOT_DIR}/build/l32-minimal-init-boot/result.txt"
    result_marker="L32_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-minimal-init-boot/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_minimal_init_payload_build.sh"
      "${ROOT_DIR}/tools/ci/make_l32_dtb.py"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.elf"
      "${ROOT_DIR}/build/l32-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin"
      "${ROOT_DIR}/build/l32-minimal-init-boot/aethercore-rv32-initramfs.dtb"
    )
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-linux-initramfs/obj/arch/riscv/boot/Image")")
    dynamic_inputs+=("$(compiler_identity "${L32_CROSS_COMPILE_PREFIX}gcc")")
    ;;
  busybox-payload)
    # shellcheck disable=SC1091
    source "${ROOT_DIR}/software/l32/manifest.env"
    result_file="${ROOT_DIR}/build/l32-busybox-shell-boot/result.txt"
    result_marker="L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS"
    marker_file="${ROOT_DIR}/build/l32-busybox-shell-boot/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_busybox_payload_build.sh"
      "${ROOT_DIR}/tools/ci/make_l32_dtb.py"
    )
    outputs=(
      "${ROOT_DIR}/build/l32-busybox-shell-boot/opensbi/platform/generic/firmware/fw_payload.elf"
      "${ROOT_DIR}/build/l32-busybox-shell-boot/opensbi/platform/generic/firmware/fw_payload.bin"
      "${ROOT_DIR}/build/l32-busybox-shell-boot/aethercore-rv32-busybox.dtb"
    )
    dynamic_inputs+=("$(hash_file_if_present "${ROOT_DIR}/build/l32-linux-busybox/obj/arch/riscv/boot/Image")")
    dynamic_inputs+=("$(compiler_identity "${L32_CROSS_COMPILE_PREFIX}gcc")")
    ;;
  *)
    echo "ERROR: unknown L32 software cache target: ${TARGET}" >&2
    exit 2
    ;;
esac

input_key="$({
  for input in "${inputs[@]}"; do
    hash_file_if_present "${input}"
  done
  printf '%s\n' "${dynamic_inputs[@]}"
} | sha256sum | awk '{print $1}')"

cache_hit=1
if [[ ! -f "${result_file}" ]] || ! grep -qx "${result_marker}" "${result_file}"; then
  cache_hit=0
fi
if [[ ! -f "${marker_file}" ]] || [[ "$(awk '$1 == "input_key" { print $2; exit }' "${marker_file}" 2>/dev/null)" != "${input_key}" ]]; then
  cache_hit=0
fi

if (( cache_hit )); then
  for output in "${outputs[@]}"; do
    rel="${output#${ROOT_DIR}/}"
    expected="$(awk -v p="${rel}" '$1 == "sha256" && $3 == p { print $2; exit }' "${marker_file}")"
    if [[ -z "${expected}" || ! -s "${output}" ]]; then
      cache_hit=0
      break
    fi
    actual="$(sha256sum "${output}" | awk '{print $1}')"
    if [[ "${actual}" != "${expected}" ]]; then
      cache_hit=0
      break
    fi
  done
fi

if (( cache_hit )); then
  printf 'L32_SOFTWARE_CACHE_HIT target=%s key=%s\n' "${TARGET}" "${input_key}"
  exit 0
fi

printf 'L32_SOFTWARE_CACHE_MISS target=%s key=%s\n' "${TARGET}" "${input_key}"
"$@"

[[ -f "${result_file}" ]] && grep -qx "${result_marker}" "${result_file}" || {
  echo "ERROR: ${TARGET} build did not produce its qualified PASS result" >&2
  exit 30
}
for output in "${outputs[@]}"; do
  [[ -s "${output}" ]] || {
    echo "ERROR: ${TARGET} build is missing qualified output ${output}" >&2
    exit 31
  }
done

mkdir -p "$(dirname "${marker_file}")"
tmp_marker="${marker_file}.tmp.$$"
{
  printf 'input_key %s\n' "${input_key}"
  for output in "${outputs[@]}"; do
    rel="${output#${ROOT_DIR}/}"
    printf 'sha256 %s %s\n' "$(sha256sum "${output}" | awk '{print $1}')" "${rel}"
  done
} > "${tmp_marker}"
mv "${tmp_marker}" "${marker_file}"
printf 'L32_SOFTWARE_CACHE_MARK target=%s key=%s outputs=%d\n' "${TARGET}" "${input_key}" "${#outputs[@]}"
