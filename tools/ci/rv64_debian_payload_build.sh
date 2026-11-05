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
export RV64_PAYLOAD_PRODUCT_BOARD=1
# The generic builder has only Linux/OpenSBI knowledge. Publish Debian's
# final PASS marker only after its external-root/media identity checks, never
# as an intermediate side effect of an unfinished wrapper.
export RV64_PAYLOAD_RESULT_TAG='RV64_DEBIAN_PAYLOAD_BUILD_INTERMEDIATE_RESULT'
export RV64_PAYLOAD_INPUT_TAG='RV64_DEBIAN_PAYLOAD_INPUT'
export RV64_PAYLOAD_ISA='rv64imafdc_zicsr_zifencei'
# Machine-mode OpenSBI uses the integer calling convention even when the
# hardware advertises F/D; only Debian S/U-mode ELFs use LP64D.
export RV64_PAYLOAD_ABI='lp64'

# The generic OpenSBI payload builder only qualifies an Image by PASS + hash.
# It cannot distinguish an earlier D3 initramfs kernel from a genuine SD1
# block-root kernel. Reject stale/mismatched software *before* compiling a
# DTB/OpenSBI payload, never after another multi-billion-cycle boot attempt.
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
manifest_field() { sed -n "s/^$2=//p" "$1" | head -n 1; }
linux_result="${RV64_PAYLOAD_LINUX_BUILD_DIR}/result.txt"
linux_image="${RV64_PAYLOAD_LINUX_BUILD_DIR}/obj/arch/riscv/boot/Image"
linux_config="${RV64_PAYLOAD_LINUX_BUILD_DIR}/obj/.config"
[[ -s "${linux_result}" && -s "${linux_image}" && -s "${linux_config}" ]] || \
  fail 'missing qualified Debian Linux Image/config/manifest; build the matching stage first'
grep -qx 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "${linux_result}" || \
  fail 'Debian Linux Image is not qualified'
[[ "$(manifest_field "${linux_result}" stage)" == "${STAGE}" ]] || \
  fail "Debian rootfs stage ${STAGE} does not match the staged Linux Image stage"
rootfs_dir="${ROOT_DIR}/build/rv64-debian-rootfs/rootfs"
[[ "$(manifest_field "${ROOTFS_RESULT}" rootfs)" == "${rootfs_dir}" &&
   "$(manifest_field "${linux_result}" rootfs)" == "${rootfs_dir}" ]] || \
  fail 'Debian rootfs or Linux Image refers to an unexpected rootfs directory'
rootfs_result_sha="$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')"
[[ "$(manifest_field "${linux_result}" rootfs_result_sha256)" == "${rootfs_result_sha}" ]] || \
  fail 'Debian Linux Image was built against a different qualified rootfs manifest'
[[ "$(manifest_field "${linux_result}" image)" == "${linux_image}" ]] || \
  fail 'Debian Linux result names a different Image'
linux_image_sha="$(sha256sum "${linux_image}" | awk '{print $1}')"
[[ "$(manifest_field "${linux_result}" image_sha256)" == "${linux_image_sha}" ]] || \
  fail 'Debian Linux Image bytes drifted after qualification'
linux_config_sha="$(sha256sum "${linux_config}" | awk '{print $1}')"
[[ "$(manifest_field "${linux_result}" config_sha256)" == "${linux_config_sha}" ]] || \
  fail 'Debian Linux configuration drifted after qualification'
linux_result_sha="$(sha256sum "${linux_result}" | awk '{print $1}')"

if [[ "${STAGE}" == sd1 ]]; then
  # Linux does not carry the native Debian rootfs in its Image at SD1. The
  # external SD medium owns the MBR/ext4 root tree; OpenSBI firmware is still
  # explicitly placed in DDR by the board loader. Do not accidentally boot
  # G5's in-memory D3 /init by inheriting the normal rdinit=/init argument.
  source "${ROOT_DIR}/software/rv64/linux_early.env"
  export RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init"
  export RV64_PAYLOAD_ENABLE_SPI_SD=1
  # The rootfs is an external MBR/ext4 disk. SD1 must not inherit D3's
  # embedded /init, and every block-root driver must be available built-in
  # before mounting /dev/mmcblk0p1 (not as an unreachable .ko module).
  grep -Fqx 'CONFIG_INITRAMFS_SOURCE=""' "${linux_config}" || \
    fail 'SD1 Linux Image embeds a rootfs instead of mounting the SD card'
  for required in CONFIG_DEVTMPFS_MOUNT=y CONFIG_SPI_OC_TINY=y CONFIG_MMC_SPI=y \
    CONFIG_MMC_BLOCK=y CONFIG_EXT4_FS=y CONFIG_MSDOS_PARTITION=y; do
    grep -Fqx "${required}" "${linux_config}" || \
      fail "SD1 Linux Image lacks built-in block-root requirement ${required}"
  done
  rootfs_tree_sha="$(manifest_field "${ROOTFS_RESULT}" rootfs_tree_sha256)"
  [[ "${rootfs_tree_sha}" =~ ^[[:xdigit:]]{64}$ &&
     "$(manifest_field "${linux_result}" rootfs_tree_sha256)" == "${rootfs_tree_sha}" ]] || \
    fail 'SD1 Linux Image and full Debian rootfs tree identities disagree'
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
  sd_rootfs_result_sha="$(manifest_field "${sd_result}" rootfs_result_sha256)"
  if [[ "${sd_rootfs_result_sha}" != "${rootfs_result_sha}" ]]; then
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
  sd_tree_sha="$(manifest_field "${sd_result}" rootfs_tree_sha256)"
  [[ "${sd_tree_sha}" == "${rootfs_tree_sha}" ]] || {
    echo 'ERROR: SD1 raw SD image was staged from a different full Debian tree' >&2
    exit 1
  }
  sd_result_sha="$(sha256sum "${sd_result}" | awk '{print $1}')"
else
  export RV64_PAYLOAD_ENABLE_SPI_SD=0
fi

# A full Product G5 Linux bring-up can spend hundreds of millions of RTL
# cycles without a new printk.  The opt-in diagnostic boot keeps the exact
# Debian/rootfs payload but makes initcall entry/return visible in UART logs.
# Short A/B and ordinary builds retain their frozen bootargs by default.
case "${RV64_DEBIAN_INITCALL_TRACE:-0}" in
  0) ;;
  1)
    # D1/D2 never enter the SD1-only source above. The debug bootargs must
    # still use the same frozen Linux command line, not an unset shell var.
    source "${ROOT_DIR}/software/rv64/linux_early.env"
    if [[ "${STAGE}" == sd1 ]]; then
      export RV64_PAYLOAD_BOOTARGS="${RV64_PAYLOAD_BOOTARGS} initcall_debug loglevel=8"
    else
      export RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init initcall_debug loglevel=8"
    fi
    ;;
  *) fail 'RV64_DEBIAN_INITCALL_TRACE must be 0 or 1' ;;
esac

# Reuse the exact OpenSBI/DTS/link-address implementation of G4, changing
# only the chosen software ISA/ABI and the qualified RV64GC Linux Image.
bash "${ROOT_DIR}/tools/ci/rv64_minimal_init_payload_build.sh"
grep -qx 'RV64_DEBIAN_PAYLOAD_BUILD_INTERMEDIATE_RESULT: status=PASS' \
  "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
# The Product RTL PLIC exposes 52 sources. The older legacy DTS exports 16
# and can appear to boot until the first real SD IRQ; reject that profile
# before publishing a Debian firmware PASS marker.
grep -Fxq 'product_board=1' "${RV64_PAYLOAD_BUILD_DIR}/result.txt" || \
  fail 'Debian firmware used the legacy board profile instead of Product'
grep -Fq 'riscv,ndev = <52>;' "${RV64_PAYLOAD_BUILD_DIR}/aethersoc-v0-initramfs.dts" || \
  fail 'Debian firmware DTS uses a stale PLIC source count (need Product 52)'
# All provenance checks above precede the expensive build. Repeat the cheap
# byte-level identities afterward: a simultaneous restage must not publish a
# PASS payload accidentally bound to two different software generations.
[[ "$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')" == "${rootfs_result_sha}" &&
   "$(sha256sum "${linux_result}" | awk '{print $1}')" == "${linux_result_sha}" &&
   "$(sha256sum "${linux_image}" | awk '{print $1}')" == "${linux_image_sha}" &&
   "$(sha256sum "${linux_config}" | awk '{print $1}')" == "${linux_config_sha}" ]] || \
  fail 'Debian rootfs/Linux Image identities changed while OpenSBI was built'
if [[ "${STAGE}" == sd1 ]]; then
  [[ "$(sha256sum "${sd_path}" | awk '{print $1}')" == "${sd_sha}" ]] || \
    fail 'SD1 raw SD image changed while OpenSBI was built'
  [[ "$(sha256sum "${sd_result}" | awk '{print $1}')" == "${sd_result_sha}" ]] || \
    fail 'SD1 raw SD image manifest changed while OpenSBI was built'
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
    printf 'sd_image_manifest_sha256=%s\n' "${sd_result_sha}"
    printf 'linux_result_sha256=%s\n' "${linux_result_sha}"
    printf 'linux_stage=%s\n' "${STAGE}"
    printf 'linux_config_sha256=%s\n' "${linux_config_sha}"
    printf 'rootfs_result_sha256=%s\n' "${sd_rootfs_result_sha}"
    printf 'rootfs_tree_sha256=%s\n' "${sd_tree_sha}"
    printf 'firmware_bin_sha256=%s\n' "$(sha256sum "${sd1_firmware_bin}" | awk '{print $1}')"
    printf 'fdt_sha256=%s\n' "$(sha256sum "${sd1_fdt}" | awk '{print $1}')"
  } >> "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
fi
printf 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS\n' >> \
  "${RV64_PAYLOAD_BUILD_DIR}/result.txt"
