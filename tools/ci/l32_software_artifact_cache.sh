#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:-}"
shift || true

if [[ -z "${TARGET}" || "$#" -eq 0 ]]; then
  echo "usage: $0 <busybox|runtime-probe|minimal-initramfs|busybox-initramfs|linux-payload|minimal-payload|busybox-payload> <build-command...>" >&2
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
profile_sensitive=0
cache_profile=""
inputs=()
outputs=()
dynamic_inputs=()

case "${TARGET}" in
  busybox)
    source "${ROOT_DIR}/software/l32_busybox/manifest.env"
    source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
    profile_sensitive=1
    cache_profile="${L32_USERSPACE_PROFILE}"
    base_gcc="${L32_USERSPACE_CROSS_COMPILE_PREFIX}gcc"
    result_file="${L32_USERSPACE_BUSYBOX_BUILD_DIR}/result.txt"
    result_marker="L32_BUSYBOX_BUILD_RESULT: status=PASS"
    marker_file="${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
      "${ROOT_DIR}/tools/ci/riscv_elf_profile.py"
      "${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"
      "${ROOT_DIR}/tools/ci/l32_busybox_build.sh"
    )
    outputs=(
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/busybox-src/busybox"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-probe"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/bin/musl-gcc"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/libc.a"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crt1.o"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crti.o"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crtn.o"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/toolchain-probe-profile.txt"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/musl-probe-profile.txt"
      "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/busybox-profile.txt"
    )
    dynamic_inputs+=("profile=${L32_USERSPACE_PROFILE}")
    dynamic_inputs+=("isa=${L32_USERSPACE_EFFECTIVE_ISA}")
    dynamic_inputs+=("require_c=${L32_USERSPACE_REQUIRE_C}")
    dynamic_inputs+=("$(compiler_identity "${base_gcc}")")
    ;;
  runtime-probe)
    source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
    profile_sensitive=1
    cache_profile="${L32_USERSPACE_PROFILE}"
    result_file="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/result.txt"
    result_marker="L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS"
    marker_file="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/software/l32_busybox/runtime_probe.c"
      "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
      "${ROOT_DIR}/tools/ci/riscv_elf_profile.py"
      "${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"
      "${ROOT_DIR}/tools/ci/l32_runtime_probe_build.sh"
    )
    outputs=(
      "${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/l32-runtime-probe"
      "${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/profile.txt"
    )
    dynamic_inputs+=("profile=${L32_USERSPACE_PROFILE}")
    dynamic_inputs+=("isa=${L32_USERSPACE_EFFECTIVE_ISA}")
    dynamic_inputs+=("require_c=${L32_USERSPACE_REQUIRE_C}")
    dynamic_inputs+=("$(hash_file_if_present "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/libc.a")")
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
    source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
    profile_sensitive=1
    cache_profile="${L32_USERSPACE_PROFILE}"
    result_file="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/result.txt"
    result_marker="L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS"
    marker_file="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
      "${ROOT_DIR}/tools/ci/l32_busybox_initramfs_build.sh"
      "${ROOT_DIR}/tools/ci/l32_linux_cache_key.sh"
    )
    outputs=(
      "${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/rootfs/init"
      "${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/rootfs/initramfs.list"
      "${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/obj/vmlinux"
      "${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/obj/arch/riscv/boot/Image"
    )
    dynamic_inputs+=("profile=${L32_USERSPACE_PROFILE}")
    dynamic_inputs+=("isa=${L32_USERSPACE_EFFECTIVE_ISA}")
    dynamic_inputs+=("require_c=${L32_USERSPACE_REQUIRE_C}")
    dynamic_inputs+=("$(hash_file_if_present "${L32_USERSPACE_BUSYBOX_BUILD_DIR}/busybox-src/busybox")")
    dynamic_inputs+=("$(hash_file_if_present "${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/l32-runtime-probe")")
    ;;
  linux-payload)
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
    source "${ROOT_DIR}/software/l32/manifest.env"
    source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
    profile_sensitive=1
    cache_profile="${L32_USERSPACE_PROFILE}"
    result_file="${L32_USERSPACE_PAYLOAD_BUILD_DIR}/result.txt"
    result_marker="L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS"
    marker_file="${L32_USERSPACE_PAYLOAD_BUILD_DIR}/evidence/software-cache.txt"
    inputs=(
      "${ROOT_DIR}/software/l32/manifest.env"
      "${ROOT_DIR}/software/l32_busybox/manifest.env"
      "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"
      "${ROOT_DIR}/tools/ci/l32_busybox_payload_build.sh"
      "${ROOT_DIR}/tools/ci/make_l32_dtb.py"
    )
    outputs=(
      "${L32_USERSPACE_PAYLOAD_BUILD_DIR}/opensbi/platform/generic/firmware/fw_payload.elf"
      "${L32_USERSPACE_PAYLOAD_BUILD_DIR}/opensbi/platform/generic/firmware/fw_payload.bin"
      "${L32_USERSPACE_PAYLOAD_BUILD_DIR}/aethercore-rv32-busybox.dtb"
    )
    dynamic_inputs+=("profile=${L32_USERSPACE_PROFILE}")
    dynamic_inputs+=("opensbi_isa=${L32_USERSPACE_OPENSBI_ISA}")
    dynamic_inputs+=("dtb_isa=${L32_USERSPACE_DTB_ISA}")
    dynamic_inputs+=("$(hash_file_if_present "${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/obj/arch/riscv/boot/Image")")
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
if (( profile_sensitive )) && [[ "$(awk '$1 == "profile" { print $2; exit }' "${marker_file}" 2>/dev/null)" != "${cache_profile}" ]]; then
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
  printf 'L32_SOFTWARE_CACHE_HIT target=%s profile=%s key=%s\n' "${TARGET}" "${cache_profile:-historical}" "${input_key}"
  exit 0
fi

printf 'L32_SOFTWARE_CACHE_MISS target=%s profile=%s key=%s\n' "${TARGET}" "${cache_profile:-historical}" "${input_key}"
"$@"

[[ -f "${result_file}" ]] && grep -qx "${result_marker}" "${result_file}" || {
  echo "ERROR: ${TARGET} build did not produce its qualified PASS result" >&2
  exit 30
}
if (( profile_sensitive )) && ! grep -qx "profile=${cache_profile}" "${result_file}"; then
  echo "ERROR: ${TARGET} build result does not match requested userspace profile ${cache_profile}" >&2
  exit 30
fi
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
  if (( profile_sensitive )); then
    printf 'profile %s\n' "${cache_profile}"
  fi
  for output in "${outputs[@]}"; do
    rel="${output#${ROOT_DIR}/}"
    printf 'sha256 %s %s\n' "$(sha256sum "${output}" | awk '{print $1}')" "${rel}"
  done
} > "${tmp_marker}"
mv "${tmp_marker}" "${marker_file}"
printf 'L32_SOFTWARE_CACHE_MARK target=%s profile=%s key=%s outputs=%d\n' \
  "${TARGET}" "${cache_profile:-historical}" "${input_key}" "${#outputs[@]}"
