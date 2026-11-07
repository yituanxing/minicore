#!/usr/bin/env bash
set -euo pipefail

# Optional, separately identified *fast architecture regression* Linux image.
# It is intentionally NOT the frozen RV64 defconfig, Product SD1/Debian, or a
# substitute for the normal G4 PID1 evidence. Reuse its exact initramfs PID1,
# ISA, UART/PLIC/clock source and kernel source, but remove built-in drivers
# for hardware which the RAM-only AetherSoC does not expose.
#
#   bash tools/ci/rv64_board_lean_linux_build.sh plan
#   bash tools/ci/rv64_linux_early_build.sh
#   bash tools/ci/rv64_minimal_initramfs_build.sh
#   bash tools/ci/rv64_board_lean_linux_build.sh build
#
# The build action emits a distinct Linux Image + OpenSBI fw_payload.bin in
# build/rv64-linux-board-lean{,-payload}. Neither qualified input is modified.
# The ordinary G4 Actions cache restores only a *sparse* initramfs Image,
# vmlinux and .config; for actual incremental Kbuild reuse, start from the
# separately qualified FULL Linux baseline object cache instead.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/software/rv64/opensbi_first_exec.env"
source "${ROOT_DIR}/software/rv64/linux_early.env"

ACTION="${1:-}"
case "${ACTION}" in
  plan|build) ;;
  *) echo 'usage: rv64_board_lean_linux_build.sh plan|build' >&2; exit 2 ;;
esac

# Preserve the full Linux operating-system substrate. G4 is a RAM-initramfs
# board, not a PCI/USB/SCSI/VirtIO machine; those general-purpose defconfig
# drivers cause substantial probing/initcall work without any matching DT node.
# Leave SPI and MMC enabled for later Product SD1 exercises; leave NET/UNIX,
# proc/sysfs/devtmpfs, scheduler, timers, MMU, ELF and all real UART/IRQ
# owners intact. The resolved Kconfig is checked AFTER olddefconfig, because
# merely editing .config cannot override an upstream Kconfig select.
UNUSED_BOARD_DEVICES=(
  PCI PCIEPORTBUS PCI_HOST_GENERIC PCIE_XILINX PCIE_FU740
  USB USB_STORAGE USB_UAS USB_XHCI_HCD USB_EHCI_HCD USB_OHCI_HCD
  SCSI SCSI_VIRTIO ATA
  VIRTIO_BLK VIRTIO_NET VIRTIO_CONSOLE VIRTIO_PCI VIRTIO_MMIO
  VIRTIO_BALLOON VIRTIO_INPUT
  DRM FB SOUND SND MEDIA_SUPPORT
)

PRESERVED_KERNEL_FEATURES=(
  64BIT MMU RISCV NONPORTABLE RISCV_SBI RISCV_TIMER
  RISCV_INTC SIFIVE_PLIC OF BLK_DEV_INITRD BINFMT_ELF
  SERIAL_8250 SERIAL_8250_CONSOLE SERIAL_OF_PLATFORM
  DEVTMPFS DEVTMPFS_MOUNT PROC_FS SYSFS TMPFS
  NET UNIX SPI MMC MMC_SPI
)

if [[ "${ACTION}" == plan ]]; then
  echo "AETHERCORE_RV64_BOARD_LEAN_PLAN linux=${RV64_LINUX_VERSION} base=qualified-RAM-initramfs object_seed=qualified-full-baseline-Kbuild no_debian=1 no_pid1_claim=1"
  printf 'disable=%s\n' "${UNUSED_BOARD_DEVICES[@]}"
  printf 'preserve=%s\n' "${PRESERVED_KERNEL_FEATURES[@]}"
  exit 0
fi

fail() { echo "RV64_BOARD_LEAN_BUILD_REJECTED: $*" >&2; exit 3; }

: "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:?run tools/ensure_riscv64_linux_gcc_13_3.sh first}"
CROSS="${AETHERCORE_RV64_LINUX_CROSS_COMPILE}"
[[ -x "${CROSS}gcc" && -x "${CROSS}readelf" ]] || fail 'missing pinned RV64 Linux cross compiler/readelf'
[[ "$("${CROSS}gcc" -dumpmachine)" == riscv64*linux* ]] || fail 'not an RV64 Linux cross compiler'
[[ "$("${CROSS}gcc" -dumpfullversion)" == 13.3.0 ]] || fail 'cross compiler must be pinned GCC 13.3.0'

CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}"
SOURCE_DIR="${CACHE_ROOT}/rv64/linux/linux-${RV64_LINUX_VERSION}"
BASELINE_RESULT="${ROOT_DIR}/build/rv64-linux-early/result.txt"
SEED_BUILD_DIR="${ROOT_DIR}/build/rv64-linux-initramfs"
SEED_OBJ="${SEED_BUILD_DIR}/obj"
BUILD_DIR="${ROOT_DIR}/build/rv64-linux-board-lean"
OBJ_DIR="${BUILD_DIR}/obj"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
IMAGE="${OBJ_DIR}/arch/riscv/boot/Image"
VMLINUX="${OBJ_DIR}/vmlinux"
CONFIG="${OBJ_DIR}/.config"
QUALIFIED="${EVIDENCE_DIR}/qualified.env"
JOBS="${RV64_LINUX_JOBS:-$(nproc)}"

[[ -f "${SOURCE_DIR}/.aethercore-linux-source-sha256" ]] || fail 'frozen Linux source cache absent'
[[ "$(<"${SOURCE_DIR}/.aethercore-linux-source-sha256")" == "${RV64_LINUX_SHA256}" ]] || fail 'Linux source hash differs'
[[ -x "${SOURCE_DIR}/scripts/config" ]] || fail 'upstream Linux scripts/config missing'
grep -qx 'RV64_LINUX_EARLY_BUILD_RESULT: status=PASS' "${BASELINE_RESULT}" || fail 'qualified FULL Linux baseline build required first'
BASELINE_IMAGE="$(sed -n 's/^kernel_image=//p' "${BASELINE_RESULT}" | head -n 1)"
BASELINE_OBJ="${BASELINE_IMAGE%/arch/riscv/boot/Image}"
case "${BASELINE_OBJ}" in
  "${CACHE_ROOT}"/rv64/linux-build/*/obj) ;;
  *) fail 'baseline object path is not inside the pinned Linux build cache' ;;
esac
BASELINE_QUALIFIER="${BASELINE_OBJ%/obj}/qualified.env"
BASELINE_RECIPE="$(sed -n 's/^kernel_recipe_key=//p' "${BASELINE_RESULT}" | head -n 1)"
[[ -s "${BASELINE_IMAGE}" && -s "${BASELINE_OBJ}/.config" &&
   -s "${BASELINE_OBJ}/vmlinux" && -s "${BASELINE_QUALIFIER}" ]] ||
  fail 'complete qualified baseline Linux object cache is missing'
# A sparse archive can contain .config/vmlinux/Image but cannot provide fast
# incremental Kbuild. Refuse that false cache hit: require genuine compiled
# C and scheduler objects from the Linux 6.6.143 baseline.
for required_object in init/main.o kernel/sched/core.o drivers/tty/serial/8250/8250_core.o; do
  [[ -s "${BASELINE_OBJ}/${required_object}" ]] || fail "baseline cache lacks compiled Kbuild object ${required_object}"
done
BASELINE_OBJECT_COUNT="$(find "${BASELINE_OBJ}" -type f -name '*.o' | wc -l | tr -d ' ')"
[[ "${BASELINE_OBJECT_COUNT}" =~ ^[0-9]+$ ]] && (( BASELINE_OBJECT_COUNT >= 100 )) ||
  fail "Linux Kbuild cache is suspiciously sparse (${BASELINE_OBJECT_COUNT} object files)"
BASELINE_IMAGE_SHA="$(sha256sum "${BASELINE_IMAGE}" | awk '{print $1}')"
BASELINE_CONFIG_SHA="$(sha256sum "${BASELINE_OBJ}/.config" | awk '{print $1}')"
grep -Fxq "recipe_key=${BASELINE_RECIPE}" "${BASELINE_QUALIFIER}" || fail 'baseline cache recipe identity differs'
grep -Fxq "image_sha256=${BASELINE_IMAGE_SHA}" "${BASELINE_QUALIFIER}" || fail 'baseline cache Image hash differs'
grep -Fxq "config_sha256=${BASELINE_CONFIG_SHA}" "${BASELINE_QUALIFIER}" || fail 'baseline cache config hash differs'
grep -Fxq "kernel_image_sha256=${BASELINE_IMAGE_SHA}" "${BASELINE_RESULT}" || fail 'baseline result Image hash differs'
grep -qx 'RV64_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS' "${SEED_BUILD_DIR}/result.txt" || fail 'qualified G4 initramfs kernel required first'
[[ -s "${SEED_OBJ}/.config" && -s "${SEED_OBJ}/vmlinux" && -s "${SEED_OBJ}/arch/riscv/boot/Image" ]] || fail 'qualified G4 source objects absent'
SEED_IMAGE_SHA="$(sha256sum "${SEED_OBJ}/arch/riscv/boot/Image" | awk '{print $1}')"
SEED_CONFIG_SHA="$(sha256sum "${SEED_OBJ}/.config" | awk '{print $1}')"
grep -Fxq "image_sha256=${SEED_IMAGE_SHA}" "${SEED_BUILD_DIR}/result.txt" || fail 'G4 initramfs Image bytes differ from evidence'
grep -Fxq "linux_version=${RV64_LINUX_VERSION}" "${SEED_BUILD_DIR}/result.txt" || fail 'G4 initramfs Linux version differs'
grep -Fxq "baseline_image=${BASELINE_IMAGE}" "${SEED_BUILD_DIR}/result.txt" || fail 'initramfs was not derived from this exact qualified Kbuild baseline'
[[ -s "${SEED_BUILD_DIR}/evidence/resolved.config" ]] || fail 'G4 initramfs resolved config evidence missing'
[[ "$(sha256sum "${SEED_BUILD_DIR}/evidence/resolved.config" | awk '{print $1}')" == "${SEED_CONFIG_SHA}" ]] ||
  fail 'initramfs resolved config evidence and sparse .config disagree'
[[ -s "${SEED_BUILD_DIR}/evidence/sha256.txt" ]] &&
  sha256sum --check --status "${SEED_BUILD_DIR}/evidence/sha256.txt" ||
  fail 'qualified initramfs Image/PID1/config evidence hashes no longer match'
INITRAMFS_SPEC="$(sed -n 's/^initramfs_spec=//p' "${SEED_BUILD_DIR}/result.txt" | head -n 1)"
[[ -s "${INITRAMFS_SPEC}" ]] || fail 'qualified initramfs input specification absent'
INIT_ELF="$(sed -n 's/^init=//p' "${SEED_BUILD_DIR}/result.txt" | head -n 1)"
[[ -s "${INIT_ELF}" ]] || fail 'qualified PID1 ELF absent'
grep -Fxq "file /init ${INIT_ELF} 0755 0 0" "${INITRAMFS_SPEC}" ||
  fail 'qualified initramfs no longer embeds the expected PID1 ELF'
grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"${INITRAMFS_SPEC}\"" "${SEED_OBJ}/.config" || fail 'G4 initramfs config no longer refers to the qualified PID1 input'

RECIPE="$(
  {
    printf '%s\n' "linux=${RV64_LINUX_SHA256}" "seed_image=${SEED_IMAGE_SHA}" "seed_config=${SEED_CONFIG_SHA}"
    printf '%s\n' "baseline_recipe=${BASELINE_RECIPE}" "baseline_image=${BASELINE_IMAGE_SHA}" "baseline_config=${BASELINE_CONFIG_SHA}"
    printf '%s\n' "initramfs=$(sha256sum "${INITRAMFS_SPEC}" | awk '{print $1}')"
    printf '%s\n' "init_elf=$(sha256sum "${INIT_ELF}" | awk '{print $1}')"
    printf '%s\n' "gcc=$("${CROSS}gcc" -dumpfullversion)"
    sha256sum "${BASH_SOURCE[0]}" "${ROOT_DIR}/software/rv64/linux_early.env"
  } | sha256sum | awk '{print $1}'
)"

config_has_y() { grep -qx "CONFIG_${1}=y" "${CONFIG}"; }
config_has_disabled() { ! grep -Eq "^CONFIG_${1}=[ym]$" "${CONFIG}"; }

qualify_config() {
  [[ -s "${CONFIG}" ]] || return 1
  local option
  for option in "${PRESERVED_KERNEL_FEATURES[@]}"; do
    config_has_y "${option}" || return 1
  done
  for option in "${UNUSED_BOARD_DEVICES[@]}"; do
    config_has_disabled "${option}" || return 1
  done
  grep -Fxq "CONFIG_INITRAMFS_SOURCE=\"${INITRAMFS_SPEC}\"" "${CONFIG}" || return 1
  grep -qx '# CONFIG_EFI is not set' "${CONFIG}" || return 1
  grep -qx '# CONFIG_RISCV_ISA_C is not set' "${CONFIG}" || return 1
  grep -qx '# CONFIG_FPU is not set' "${CONFIG}" || return 1
}

qualify_cache() {
  [[ -s "${IMAGE}" && -s "${VMLINUX}" && -s "${QUALIFIED}" ]] || return 1
  grep -Fxq "recipe=${RECIPE}" "${QUALIFIED}" || return 1
  qualify_config || return 1
  grep -Fxq "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')" "${QUALIFIED}" || return 1
  grep -Fxq "vmlinux_sha256=$(sha256sum "${VMLINUX}" | awk '{print $1}')" "${QUALIFIED}" || return 1
  grep -Fxq "config_sha256=$(sha256sum "${CONFIG}" | awk '{print $1}')" "${QUALIFIED}" || return 1
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Class:[[:space:]]*ELF64' || return 1
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || return 1
}

if qualify_cache; then
  echo "RV64_BOARD_LEAN_KERNEL_CACHE_HIT recipe=${RECIPE}"
else
  # Copy *all* qualified baseline objects, not the sparse restored G4
  # initramfs cache. Its already-qualified .config and /init manifest are
  # overlaid below; Kbuild then incrementally rebuilds only the selected
  # lean config and embedded initramfs without writing to either source.
  # This exact directory is owned by the board-lean build, never by G4/SD1.
  if [[ -e "${BUILD_DIR}" && ! -d "${BUILD_DIR}" ]]; then
    fail 'board-lean output path exists but is not a directory'
  fi
  mkdir -p "${BUILD_DIR}"
  OLD_OBJ=""
  if [[ -e "${OBJ_DIR}" ]]; then
    OLD_OBJ="${BUILD_DIR}/obsolete-obj.$$.old"
    mv "${OBJ_DIR}" "${OLD_OBJ}"
  fi
  cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"
  mkdir -p "${EVIDENCE_DIR}"
  cp "${SEED_OBJ}/.config" "${CONFIG}"
  config_args=()
  for option in "${UNUSED_BOARD_DEVICES[@]}"; do config_args+=(-d "${option}"); done
  "${SOURCE_DIR}/scripts/config" --file "${CONFIG}" "${config_args[@]}"

  export KBUILD_BUILD_USER="${RV64_LINUX_BUILD_USER}"
  export KBUILD_BUILD_HOST="${RV64_LINUX_BUILD_HOST}"
  export KBUILD_BUILD_VERSION="${RV64_LINUX_BUILD_VERSION}"
  export KBUILD_BUILD_TIMESTAMP="${RV64_LINUX_BUILD_TIMESTAMP}"
  export TZ="${RV64_LINUX_BUILD_TZ}"
  make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${CROSS}" olddefconfig \
    2>&1 | tee "${EVIDENCE_DIR}/config.log"
  qualify_config || fail 'resolved lean Linux lost essential platform/OS features or retained absent devices'
  make -C "${SOURCE_DIR}" O="${OBJ_DIR}" ARCH=riscv CROSS_COMPILE="${CROSS}" -j"${JOBS}" Image \
    2>&1 | tee "${EVIDENCE_DIR}/linux-build.log"
  [[ -s "${IMAGE}" && -s "${VMLINUX}" ]] || fail 'no board-lean Linux Image/vmlinux'
  "${CROSS}readelf" -h "${VMLINUX}" | grep -q 'Machine:[[:space:]]*RISC-V' || fail 'board-lean vmlinux is not RISC-V ELF'
  cp "${CONFIG}" "${EVIDENCE_DIR}/resolved.config"
  diff -u "${SEED_OBJ}/.config" "${CONFIG}" > "${EVIDENCE_DIR}/base-vs-lean.config.diff" || [[ $? -eq 1 ]]
  {
    printf 'recipe=%s\n' "${RECIPE}"
    printf 'full_baseline_recipe=%s\n' "${BASELINE_RECIPE}"
    printf 'full_baseline_image_sha256=%s\n' "${BASELINE_IMAGE_SHA}"
    printf 'full_baseline_config_sha256=%s\n' "${BASELINE_CONFIG_SHA}"
    printf 'full_baseline_objects=%s\n' "${BASELINE_OBJECT_COUNT}"
    printf 'seed_image_sha256=%s\n' "${SEED_IMAGE_SHA}"
    printf 'seed_config_sha256=%s\n' "${SEED_CONFIG_SHA}"
    printf 'image_sha256=%s\n' "$(sha256sum "${IMAGE}" | awk '{print $1}')"
    printf 'vmlinux_sha256=%s\n' "$(sha256sum "${VMLINUX}" | awk '{print $1}')"
    printf 'config_sha256=%s\n' "$(sha256sum "${CONFIG}" | awk '{print $1}')"
  } > "${QUALIFIED}"
  qualify_cache || fail 'fresh board-lean Image failed post-build qualification'
  # The old object tree is recoverable while the new build is incomplete;
  # once the exact new Image passes the checks, release only our own backup.
  if [[ -n "${OLD_OBJ}" ]]; then rm -rf -- "${OLD_OBJ}"; fi
fi

[[ "$(sha256sum "${SEED_OBJ}/.config" | awk '{print $1}')" == "${SEED_CONFIG_SHA}" ]] ||
  fail 'the frozen G4 source config changed during the private board-lean build'
[[ "$(sha256sum "${SEED_OBJ}/arch/riscv/boot/Image" | awk '{print $1}')" == "${SEED_IMAGE_SHA}" ]] ||
  fail 'the frozen G4 Linux Image changed during the private board-lean build'
[[ "$(sha256sum "${BASELINE_OBJ}/.config" | awk '{print $1}')" == "${BASELINE_CONFIG_SHA}" &&
   "$(sha256sum "${BASELINE_IMAGE}" | awk '{print $1}')" == "${BASELINE_IMAGE_SHA}" ]] ||
  fail 'the full frozen Kbuild baseline changed during the private board-lean build'

{
  echo 'RV64_BOARD_LEAN_LINUX_BUILD_RESULT: status=PASS'
  echo "linux_version=${RV64_LINUX_VERSION}"
  echo "linux_source_sha256=${RV64_LINUX_SHA256}"
  echo "seed_image_sha256=${SEED_IMAGE_SHA}"
  echo "seed_config_sha256=${SEED_CONFIG_SHA}"
  echo "full_baseline_recipe=${BASELINE_RECIPE}"
  echo "full_baseline_image_sha256=${BASELINE_IMAGE_SHA}"
  echo "full_baseline_config_sha256=${BASELINE_CONFIG_SHA}"
  echo "full_baseline_objects=${BASELINE_OBJECT_COUNT}"
  echo "object_seed=${BASELINE_OBJ}"
  echo "recipe=${RECIPE}"
  echo "config=${CONFIG}"
  echo "base_image_bytes=$(stat -c %s "${SEED_OBJ}/arch/riscv/boot/Image")"
  echo "image=${IMAGE}"
  echo "image_bytes=$(stat -c %s "${IMAGE}")"
  echo "image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')"
  echo "vmlinux=${VMLINUX}"
  echo 'qualification=LEAN_G4_RAM_INITRAMFS_BUILD_ONLY_NOT_RTL_PID1_OR_DEBIAN_PASS'
} | tee "${BUILD_DIR}/result.txt"

# Existing payload builder already pins the ISA/ABI/DTB/firmware layout and
# checks Image SHA, ELF64 entry, OpenSBI version and DTB overlap. Reuse it with
# a distinct output; do not duplicate or loosen any of those safety checks.
PAYLOAD_DIR="${ROOT_DIR}/build/rv64-board-lean-payload"
PAYLOAD_RESULT="${PAYLOAD_DIR}/result.txt"
PAYLOAD_FINGERPRINT="$(
  cd "${ROOT_DIR}"
  {
    printf '%s\n' "linux_image=$(sha256sum "${IMAGE}" | awk '{print $1}')"
    printf '%s\n' "opensbi=${RV64_OPENSBI_COMMIT}"
    sha256sum tools/ci/rv64_minimal_init_payload_build.sh \
      software/rv64/opensbi_first_exec.env software/rv64/linux_early.env build.mill
    find src/main/scala -type f -name '*.scala' -print0 | sort -z | xargs -0 sha256sum
  } | sha256sum | awk '{print $1}'
)"
PAYLOAD_CACHE_TICKET="${PAYLOAD_DIR}/evidence/board-lean-cache-key.txt"
OPENSBI_SOURCE="${CACHE_ROOT}/l32/opensbi/${RV64_OPENSBI_COMMIT}"
[[ -d "${OPENSBI_SOURCE}/.git" ]] || fail 'pinned OpenSBI source cache absent'
[[ "$(git -C "${OPENSBI_SOURCE}" rev-parse HEAD)" == "${RV64_OPENSBI_COMMIT}" ]] || fail 'pinned OpenSBI source commit differs'
git -C "${OPENSBI_SOURCE}" diff --quiet --ignore-submodules -- || fail 'pinned OpenSBI source is dirty'
if [[ -s "${PAYLOAD_RESULT}" ]] &&
   grep -Fxq 'RV64_BOARD_LEAN_PAYLOAD_BUILD_RESULT: status=PASS' "${PAYLOAD_RESULT}" &&
   grep -Fxq "linux_image_sha256=$(sha256sum "${IMAGE}" | awk '{print $1}')" "${PAYLOAD_RESULT}" &&
   grep -Fxq "fingerprint=${PAYLOAD_FINGERPRINT}" "${PAYLOAD_CACHE_TICKET}" &&
   grep -Fxq "opensbi_commit=${RV64_OPENSBI_COMMIT}" "${PAYLOAD_DIR}/evidence/inputs.txt" &&
   grep -Fxq "isa=${RV64_OPENSBI_ISA}" "${PAYLOAD_RESULT}" &&
   grep -Fxq "abi=${RV64_OPENSBI_ABI}" "${PAYLOAD_RESULT}" &&
   sha256sum --check --status "${PAYLOAD_DIR}/evidence/sha256.txt" &&
   [[ -s "${PAYLOAD_DIR}/opensbi/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.bin" ]]; then
  echo 'RV64_BOARD_LEAN_PAYLOAD_CACHE_HIT: previous firmware Image SHA matches'
else
  RV64_PAYLOAD_LINUX_BUILD_DIR="${BUILD_DIR}" \
  RV64_PAYLOAD_LINUX_QUALIFIER=RV64_BOARD_LEAN_LINUX_BUILD_RESULT \
  RV64_PAYLOAD_BUILD_DIR="${PAYLOAD_DIR}" \
  RV64_PAYLOAD_RESULT_TAG=RV64_BOARD_LEAN_PAYLOAD_BUILD_RESULT \
  RV64_PAYLOAD_INPUT_TAG=RV64_BOARD_LEAN_PAYLOAD_INPUT \
  RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init" \
  RV64_PAYLOAD_ISA="${RV64_OPENSBI_ISA}" \
  RV64_PAYLOAD_ABI="${RV64_OPENSBI_ABI}" \
  RV64_PAYLOAD_ENABLE_SPI_SD=0 \
  RV64_PAYLOAD_PRODUCT_BOARD=0 \
    bash "${ROOT_DIR}/tools/ci/rv64_minimal_init_payload_build.sh"
  printf 'fingerprint=%s\n' "${PAYLOAD_FINGERPRINT}" > "${PAYLOAD_CACHE_TICKET}"
fi

grep -Fxq 'spi_sd=0' "${PAYLOAD_RESULT}" || fail 'board-lean payload unexpectedly needs SD media'
grep -Fxq 'product_board=0' "${PAYLOAD_RESULT}" || fail 'board-lean payload changed the frozen RAM board'
echo "RV64_BOARD_LEAN_PAYLOAD_READY: firmware_bin=${PAYLOAD_DIR}/opensbi/platform/${RV64_OPENSBI_PLATFORM}/firmware/fw_payload.bin no_guest_cycles=1 not_pid1_or_debian=1"
