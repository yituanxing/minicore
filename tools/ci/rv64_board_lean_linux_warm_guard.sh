#!/usr/bin/env bash
set -euo pipefail

# Run BEFORE rv64_linux_early_build.sh. That script intentionally rebuilds a
# missing/invalid qualified Kbuild object tree. The short Product gate must
# never accidentally enter that expensive cold path because a cache key hit
# contained only a sparse Image/vmlinux/.config archive.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT}/software/rv64/linux_early.env"
source "${ROOT}/software/rv64/opensbi_first_exec.env"
fail() { echo "RV64_BOARD_LEAN_WARM_CACHE_REQUIRED: $*" >&2; exit 16; }

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?pinned GCC must be provisioned first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
[[ -x "${CROSS}gcc" && -x "${CROSS}readelf" ]] || fail 'pinned GCC/readelf is missing'
[[ "$("${CROSS}gcc" -dumpmachine)" == riscv64*linux* ]] || fail 'cross compiler target drifted'
[[ "$("${CROSS}gcc" -dumpfullversion)" == 13.3.0 ]] || fail 'cross compiler version drifted'

CACHE="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
LINUX_SOURCE="${CACHE}/rv64/linux/linux-${RV64_LINUX_VERSION}"
ARCHIVE="${CACHE}/rv64/linux/linux-${RV64_LINUX_VERSION}.tar.xz"
[[ -s "${ARCHIVE}" && -s "${LINUX_SOURCE}/.aethercore-linux-source-sha256" ]] ||
  fail 'frozen source archive or tree absent (cold download forbidden)'
[[ "$(<"${LINUX_SOURCE}/.aethercore-linux-source-sha256")" == "${RV64_LINUX_SHA256}" ]] ||
  fail 'Linux source tree marker differs'
printf '%s  %s\n' "${RV64_LINUX_SHA256}" "${ARCHIVE}" | sha256sum -c --status - ||
  fail 'frozen kernel tarball hash differs'

# Exactly mirror the recipe computed by rv64_linux_early_build.sh. A change
# to the builder or pinned options changes this recipe and closes this gate.
RECIPE="$({
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
  sha256sum "${ROOT}/software/rv64/linux_early.env" "${ROOT}/tools/ci/rv64_linux_early_build.sh"
} | sha256sum | awk '{print $1}')"
OBJ="${CACHE}/rv64/linux-build/${RECIPE}/obj"
PROOF="${CACHE}/rv64/linux-build/${RECIPE}/qualified.env"
IMAGE="${OBJ}/arch/riscv/boot/Image"
CONFIG="${OBJ}/.config"
VMLINUX="${OBJ}/vmlinux"
[[ -s "${PROOF}" && -s "${IMAGE}" && -s "${CONFIG}" && -s "${VMLINUX}" ]] ||
  fail 'exact qualified full Linux object cache missing'
grep -Fxq "recipe_key=${RECIPE}" "${PROOF}" || fail 'recipe identity mismatch'
grep -Fxq "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')" "${PROOF}" ||
  fail 'Image byte hash mismatch'
grep -Fxq "config_sha256=$(sha256sum "${CONFIG}" | awk '{print $1}')" "${PROOF}" ||
  fail 'Kconfig byte hash mismatch'
for required in \
  'CONFIG_64BIT=y' 'CONFIG_MMU=y' 'CONFIG_NONPORTABLE=y' \
  '# CONFIG_EFI is not set' '# CONFIG_RISCV_ISA_C is not set' \
  '# CONFIG_FPU is not set' '# CONFIG_VGA_CONSOLE is not set'; do
  grep -Fxq "${required}" "${CONFIG}" || fail "frozen Kconfig invalid: ${required}"
done
! grep -qx 'CONFIG_PORTABLE=y' "${CONFIG}" || fail 'PORTABLE unexpectedly enabled'
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || fail 'not ELF64'
"${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || fail 'not RISC-V'
for required in init/main.o kernel/sched/core.o drivers/tty/serial/8250/8250_core.o; do
  [[ -s "${OBJ}/${required}" ]] || fail "sparse Kbuild archive: missing ${required}"
done
OBJECTS="$(find "${OBJ}" -type f -name '*.o' | wc -l | tr -d ' ')"
(( OBJECTS >= 100 )) || fail "sparse Kbuild archive: only ${OBJECTS} .o files"

# If OpenSBI source is absent, the base builder would fetch and compile it.
OPEN_SBI="${CACHE}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
[[ -d "${OPEN_SBI}/.git" ]] || fail 'qualified pinned OpenSBI source missing'
[[ "$(git -C "${OPEN_SBI}" rev-parse HEAD)" == "${RV64_OPENSBI_COMMIT}" ]] ||
  fail 'pinned OpenSBI commit differs'
git -C "${OPEN_SBI}" diff --quiet --ignore-submodules -- || fail 'cached OpenSBI source is dirty'

echo "RV64_BOARD_LEAN_WARM_CACHE_PASS: recipe=${RECIPE} full_objects=${OBJECTS} source_archive_sha256=${RV64_LINUX_SHA256} cold_kernel_path_forbidden=1"
