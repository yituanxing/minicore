#!/usr/bin/env bash
set -euo pipefail

# Offline, host-side physical SD media construction for Product G5-SD1
# persistence workstream. This script NEVER executes a foreign ELF, mounts a
# host loop device, launches RTL, or starts a distribution boot. The resulting
# raw disk image has a DOS/MBR partition table and ONE ext4 partition:
#   /dev/mmcblk0      raw SD card (LBA0 holds MBR)
#   /dev/mmcblk0p1    ext4 Debian root (LBA2048, 1MiB alignment)
# Firmware is loaded into DDR separately by the physical board loader.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOTFS_RESULT="${ROOT_DIR}/build/rv64-debian-rootfs/result.txt"
BUILD_DIR="${ROOT_DIR}/build/rv64-debian-sd1-sd"
SD_IMAGE="${BUILD_DIR}/sd-rootfs.img"
PART_IMAGE="${BUILD_DIR}/partition-rootfs.ext4"
EVIDENCE_DIR="${BUILD_DIR}/evidence"
FIRST_LBA=2048
SECTOR_BYTES=512
PART_LABEL=aethercore-root
PART_UUID="${RV64_SD1_ROOTFS_UUID:-96207020-0015-4000-8000-000000000501}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for tool in python3 sudo mkfs.ext4 sha256sum stat du truncate dd; do
  command -v "$tool" >/dev/null || fail "SD1 SD image builder requires $tool"
done

[[ -s "${ROOTFS_RESULT}" ]] || fail 'SD1 full native Debian rootfs must be staged first'
grep -qx 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "${ROOTFS_RESULT}" || \
  fail 'SD1 rootfs source did not pass its offline integrity checks'
stage="$(sed -n 's/^stage=//p' "${ROOTFS_RESULT}" | head -n 1)"
[[ "${stage}" == sd1 ]] || fail "need separately qualified stage=sd1 rootfs (got ${stage})"
ROOTFS_DIR="$(sed -n 's/^rootfs=//p' "${ROOTFS_RESULT}" | head -n 1)"
[[ "${ROOTFS_DIR}" == "${ROOT_DIR}/build/rv64-debian-rootfs/rootfs" ]] || \
  fail 'unexpected SD1 rootfs path; never image an arbitrary host directory'
[[ -x "${ROOTFS_DIR}/sbin/init" && -x "${ROOTFS_DIR}/bin/sh" ]] || \
  fail 'real native Debian init and shell are required on SD'
[[ ! -e "${ROOTFS_DIR}/init" ]] || fail 'SD1 root must not contain an initramfs wrapper'

# A real Debian installation can be much larger than the old 96MiB
# initramfs allowance. Size the offline SD image from the actual tree, with
# ample ext4 metadata/service headroom. Never silently crop/resize it to fit
# the 256MiB DDR memory aperture: RAM and persistent SD are different media.
rootfs_bytes="$(sudo du -sb "${ROOTFS_DIR}" | awk '{print $1}')"
[[ "${rootfs_bytes}" =~ ^[0-9]+$ && "${rootfs_bytes}" -gt 0 ]] || \
  fail 'invalid SD1 rootfs size'
rootfs_recorded="$(sed -n 's/^rootfs_bytes=//p' "${ROOTFS_RESULT}" | head -n 1)"
[[ "${rootfs_bytes}" == "${rootfs_recorded}" ]] || \
  fail 'SD1 rootfs tree size changed since the offline pinned-source check'
rootfs_tree_recorded="$(sed -n 's/^rootfs_tree_sha256=//p' "${ROOTFS_RESULT}" | head -n 1)"
[[ "${rootfs_tree_recorded}" =~ ^[[:xdigit:]]{64}$ ]] || \
  fail 'SD1 rootfs source did not record a complete tree digest'
rootfs_tree_actual="$(sudo python3 "${ROOT_DIR}/tools/ci/rv64_debian_sd1_tree_digest.py" "${ROOTFS_DIR}")"
[[ "${rootfs_tree_actual}" == "${rootfs_tree_recorded}" ]] || \
  fail 'SD1 full Debian directory tree changed after source qualification'
rootfs_result_sha="$(sha256sum "${ROOTFS_RESULT}" | awk '{print $1}')"
min_disk_mib=$(( (rootfs_bytes * 3 / 2 + 128 * 1024 * 1024 + 1048575) / 1048576 + 1 ))
(( min_disk_mib < 512 )) && min_disk_mib=512
disk_mib="${RV64_SD1_SD_IMAGE_MIB:-${min_disk_mib}}"
[[ "${disk_mib}" =~ ^[0-9]+$ ]] || fail 'RV64_SD1_SD_IMAGE_MIB must be integer MiB'
(( disk_mib >= min_disk_mib && disk_mib <= 2097152 )) || \
  fail "SD1 SD disk must be ${min_disk_mib}..2097152 MiB (DOS/MBR size limit)"
[[ "${PART_UUID}" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]] || \
  fail 'RV64_SD1_ROOTFS_UUID must be UUID string'

disk_bytes=$((disk_mib * 1048576))
disk_sectors=$((disk_bytes / SECTOR_BYTES))
part_sectors=$((disk_sectors - FIRST_LBA))
part_bytes=$((part_sectors * SECTOR_BYTES))
(( part_sectors > 0 && part_sectors <= 4294967295 )) || \
  fail 'SD1 MBR partition sector count overflows DOS/MBR'

mkdir -p "${BUILD_DIR}" "${EVIDENCE_DIR}"
[[ ! -e "${SD_IMAGE}" && ! -e "${PART_IMAGE}" ]] || \
  fail 'SD1 SD image already exists; remove exact generated artifacts deliberately before re-building'
truncate -s "${part_bytes}" "${PART_IMAGE}"
truncate -s "${disk_bytes}" "${SD_IMAGE}"

# mke2fs -d copies the validated Debian tree into a regular ext4 file;
# sudo permits ownership, protected files and device nodes to be preserved.
# This builds media OFFLINE without using loop devices or host mounting.
sudo mkfs.ext4 -q -F -L "${PART_LABEL}" -U "${PART_UUID}" \
  -d "${ROOTFS_DIR}" "${PART_IMAGE}"
dd if="${PART_IMAGE}" of="${SD_IMAGE}" bs=1M seek=1 \
  conv=sparse,notrunc status=none

# A concurrent modification of the staged rootfs while mke2fs imports its
# contents would otherwise yield a valid MBR/ext4 image with no consistent
# provenance. Fail before publishing any READY manifest in that case.
rootfs_tree_after="$(sudo python3 "${ROOT_DIR}/tools/ci/rv64_debian_sd1_tree_digest.py" "${ROOTFS_DIR}")"
[[ "${rootfs_tree_after}" == "${rootfs_tree_recorded}" ]] || \
  fail 'SD1 rootfs was modified while building the raw ext4 SD image'

# A deliberately minimal MBR with one Linux (0x83) partition. CHS values are
# advisory legacy fields; Linux uses the explicit little-endian LBA ranges.
python3 - "${SD_IMAGE}" "${FIRST_LBA}" "${part_sectors}" <<'PY'
import struct
import sys

image = sys.argv[1]
start = int(sys.argv[2])
sectors = int(sys.argv[3])
entry = struct.pack('<BBBBBBBBII',
                    0x00, 0x00, 0x02, 0x00,  # not active; starting CHS
                    0x83, 0xfe, 0xff, 0xff,  # Linux partition; ending CHS
                    start, sectors)
with open(image, 'r+b') as disk:
    disk.seek(446)
    disk.write(entry)
    disk.seek(510)
    disk.write(b'\x55\xaa')
PY

[[ "$(stat -c %s "${SD_IMAGE}")" == "${disk_bytes}" ]] || \
  fail 'SD1 raw SD image actual byte length differs from MBR contract'
disk_sha="$(sha256sum "${SD_IMAGE}" | awk '{print $1}')"
part_sha="$(sha256sum "${PART_IMAGE}" | awk '{print $1}')"
{
  printf 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED\n'
  printf 'stage=sd1\n'
  printf 'partition_table=MBR\n'
  printf 'partition_count=1\n'
  printf 'partition1_type=0x83\n'
  printf 'partition1_fs=ext4\n'
  printf 'partition1_label=%s\n' "${PART_LABEL}"
  printf 'partition1_uuid=%s\n' "${PART_UUID}"
  printf 'partition1_first_lba=%s\n' "${FIRST_LBA}"
  printf 'partition1_sector_count=%s\n' "${part_sectors}"
  printf 'sector_bytes=%s\n' "${SECTOR_BYTES}"
  printf 'rootfs_bytes=%s\n' "${rootfs_bytes}"
  printf 'rootfs_tree_sha256=%s\n' "${rootfs_tree_actual}"
  printf 'rootfs_result_sha256=%s\n' "${rootfs_result_sha}"
  printf 'disk_bytes=%s\n' "${disk_bytes}"
  printf 'sd_image=%s\n' "${SD_IMAGE}"
  printf 'SD_IMAGE=%s\n' "${SD_IMAGE}"
  printf 'sd_image_sha256=%s\n' "${disk_sha}"
  printf 'partition_ext4_sha256=%s\n' "${part_sha}"
  printf 'bootargs=root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init\n'
} | tee "${BUILD_DIR}/result.txt"
sha256sum "${SD_IMAGE}" "${PART_IMAGE}" > "${EVIDENCE_DIR}/sha256.txt"
