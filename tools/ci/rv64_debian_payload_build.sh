#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOTFS_RESULT="${ROOT_DIR}/build/rv64-debian-rootfs/result.txt"
[[ -s "${ROOTFS_RESULT}" ]] || { echo 'ERROR: missing Debian rootfs staging result' >&2; exit 1; }
STAGE="$(sed -n 's/^stage=//p' "${ROOTFS_RESULT}" | head -n 1)"
case "${STAGE}" in d1|d2|d3|sd1) ;; *) echo "ERROR: unsupported stage ${STAGE}" >&2; exit 1 ;; esac
export RV64_PAYLOAD_LINUX_BUILD_DIR="${ROOT_DIR}/build/rv64-debian-linux"
export RV64_PAYLOAD_BUILD_DIR="${ROOT_DIR}/build/rv64-debian-boot"
export RV64_PAYLOAD_LINUX_QUALIFIER='RV64_DEBIAN_LINUX_BUILD_RESULT'
export RV64_PAYLOAD_RESULT_TAG='RV64_DEBIAN_PAYLOAD_BUILD_RESULT'
export RV64_PAYLOAD_INPUT_TAG='RV64_DEBIAN_PAYLOAD_INPUT'
export RV64_PAYLOAD_ISA='rv64imafdc_zicsr_zifencei'
# Machine-mode OpenSBI uses the integer calling convention even when the
# hardware advertises F/D; only Debian S/U-mode ELFs use LP64D.
export RV64_PAYLOAD_ABI='lp64'

if [[ "${STAGE}" == sd1 ]]; then
  # Linux does not carry the native Debian rootfs in its Image at SD1. The
  # external SD medium owns the MBR/ext4 root tree; OpenSBI firmware is still
  # explicitly placed in DDR by the board loader. Do not accidentally boot
  # G5's in-memory D3 /init by inheriting the normal rdinit=/init argument.
  source "${ROOT_DIR}/software/rv64/linux_early.env"
  export RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init"
  export RV64_PAYLOAD_ENABLE_SPI_SD=1
  sd_result="${ROOT_DIR}/build/rv64-debian-sd1-sd/result.txt"
  grep -qx 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "${sd_result}" || {
    echo 'ERROR: build an explicit SD1 MBR/ext4 raw SD image before firmware' >&2
    exit 1
  }
  sd_path="$(sed -n 's/^sd_image=//p' "${sd_result}" | head -n 1)"
  [[ "${sd_path}" == "${ROOT_DIR}/build/rv64-debian-sd1-sd/sd-rootfs.img" && -s "${sd_path}" ]] || {
    echo 'ERROR: SD1 SD image path or artifact is missing' >&2
    exit 1
  }
  sd_rootfs_result_sha="$(sed -n 's/^rootfs_result_sha256=//p' "${sd_result}" | head -n 1)"
  if [[ "${sd_rootfs_result_sha}" != "$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')" ]]; then
    echo 'ERROR: SD1 raw SD image was built from a different Debian rootfs manifest' >&2
    exit 1
  fi
  sd_sha="$(sed -n 's/^sd_image_sha256=//p' "${sd_result}" | head -n 1)"
  if [[ "${sd_sha}" != "$(sha256sum "${sd_path}" | awk '{print $1}')" ]]; then
    echo 'ERROR: SD1 raw SD image bytes changed after offline MBR/ext4 staging' >&2
    exit 1
  fi
  grep -Fqx 'bootargs=root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init' "${sd_result}" || {
    echo 'ERROR: SD partition contract differs from kernel root selection' >&2
    exit 1
  }
  for required in 'stage=sd1' 'partition_table=MBR' 'partition_count=1' \
    'partition1_type=0x83' 'partition1_fs=ext4' 'partition1_first_lba=2048' \
    'sector_bytes=512'; do
    grep -Fqx "${required}" "${sd_result}" || {
      echo "ERROR: SD1 raw SD image partition metadata differs: ${required}" >&2
      exit 1
    }
  done
  sd_tree_sha="$(sed -n 's/^rootfs_tree_sha256=//p' "${sd_result}" | head -n 1)"
  [[ "${sd_tree_sha}" == "$(sed -n 's/^rootfs_tree_sha256=//p' "${ROOTFS_RESULT}" | head -n 1)" ]] || {
    echo 'ERROR: SD1 raw SD image was staged from a different full Debian tree' >&2
    exit 1
  }
else
  export RV64_PAYLOAD_ENABLE_SPI_SD=0
fi

# Reuse the exact OpenSBI/DTS/link-address implementation of G4, changing
# only the chosen software ISA/ABI and the qualified RV64GC Linux Image.
bash "${ROOT_DIR}/tools/ci/rv64_minimal_init_payload_build.sh"
grep -qx 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' \
  "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
if [[ "${STAGE}" == sd1 ]]; then
  grep -Fxq "bootargs=${RV64_PAYLOAD_BOOTARGS}" "${RV64_PAYLOAD_BUILD_DIR}/result.txt" || {
    echo 'ERROR: final SD1 firmware DTB bootargs differ from raw SD card contract' >&2
    exit 1
  }
  grep -Fxq 'spi_sd=1' "${RV64_PAYLOAD_BUILD_DIR}/result.txt" || {
    echo 'ERROR: SD1 firmware DTB omitted the SPI-SD hardware controller' >&2
    exit 1
  }
  # The OpenSBI payload by itself is not a deployable distro: retain the
  # exact removable-media identity alongside the firmware/DTS identity.
  # The Product FPGA stream loader uses TWO independent DDR segments (fw and
  # DTB), so bind their own byte-level identities into the SD1 handoff. The
  # simulator must never substitute an embedded-DTB assumption for the
  # hardware-visible 0x87f00000 DTB segment.
  sd1_firmware_bin="$(sed -n 's/^firmware_bin=//p' "${RV64_PAYLOAD_BUILD_DIR}/result.txt" | head -n 1)"
  sd1_fdt="$(sed -n 's/^fdt=//p' "${RV64_PAYLOAD_BUILD_DIR}/result.txt" | head -n 1)"
  [[ -s "${sd1_firmware_bin}" && -s "${sd1_fdt}" ]] || {
    echo 'ERROR: SD1 firmware/independent DTB missing at dual-segment identity bind' >&2
    exit 1
  }
  {
    printf 'sd_image=%s\n' "${sd_path}"
    printf 'SD_IMAGE=%s\n' "${sd_path}"
    printf 'sd_image_sha256=%s\n' "${sd_sha}"
    printf 'sd_image_manifest=%s\n' "${sd_result}"
    printf 'rootfs_result_sha256=%s\n' "${sd_rootfs_result_sha}"
    printf 'rootfs_tree_sha256=%s\n' "${sd_tree_sha}"
    printf 'firmware_bin_sha256=%s\n' "$(sha256sum "${sd1_firmware_bin}" | awk '{print $1}')"
    printf 'fdt_sha256=%s\n' "$(sha256sum "${sd1_fdt}" | awk '{print $1}')"
  } >> "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
fi
