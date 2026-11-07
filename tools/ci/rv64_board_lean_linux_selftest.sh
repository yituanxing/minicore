#!/usr/bin/env bash
set -euo pipefail

# Cheap contract test. It never compiles a kernel or runs a guest; dynamic
# Kconfig/ELF/PID1 qualifications happen in the build action on a provisioned
# runner with the pinned 6.6.143 source and GCC 13.3.0.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/tools/ci/rv64_board_lean_linux_build.sh"
bash -n "${SCRIPT}"

plan="$(bash "${SCRIPT}" plan)"
for expected in \
  'linux=6.6.143' \
  'base=qualified-RAM-initramfs' \
  'object_seed=qualified-full-baseline-Kbuild' \
  'no_debian=1' \
  'disable=PCI' \
  'disable=USB' \
  'disable=SCSI' \
  'disable=ATA' \
  'disable=DRM' \
  'disable=FB' \
  'disable=VIRTIO_MMIO' \
  'preserve=MMU' \
  'preserve=RISCV_SBI' \
  'preserve=RISCV_TIMER' \
  'preserve=SIFIVE_PLIC' \
  'preserve=SERIAL_8250_CONSOLE' \
  'preserve=BLK_DEV_INITRD' \
  'preserve=DEVTMPFS_MOUNT' \
  'preserve=NET' \
  'preserve=SPI' \
  'preserve=MMC_SPI'; do
  grep -Fq "${expected}" <<< "${plan}" || {
    echo "RV64_BOARD_LEAN_SELFTEST_FAIL missing_plan=${expected}" >&2
    exit 1
  }
done

# A restored G4 Image cache is sparse: make accidental regression to copying
# its obj directory a source-level rejection even on a runner without Linux.
grep -Fq 'cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"' "${SCRIPT}" || {
  echo 'RV64_BOARD_LEAN_SELFTEST_FAIL missing_full_baseline_object_seed' >&2
  exit 1
}
grep -Fq 'BASELINE_OBJECT_COUNT' "${SCRIPT}" || {
  echo 'RV64_BOARD_LEAN_SELFTEST_FAIL missing_sparse_object_guard' >&2
  exit 1
}

if bash "${SCRIPT}" invalid-action >/dev/null 2>&1; then
  echo 'RV64_BOARD_LEAN_SELFTEST_FAIL accepted_invalid_action' >&2
  exit 1
fi
if env AETHERCORE_RV64_LINUX_CROSS_COMPILE=/does-not-exist-rv64- \
   bash "${SCRIPT}" build >/dev/null 2>&1; then
  echo 'RV64_BOARD_LEAN_SELFTEST_FAIL accepted_missing_toolchain' >&2
  exit 1
fi

preserved="$(grep -c '^preserve=' <<< "${plan}")"
disabled="$(grep -c '^disable=' <<< "${plan}")"
[[ "${preserved}" == 24 && "${disabled}" == 26 ]] || {
  echo "RV64_BOARD_LEAN_SELFTEST_FAIL profile_shape_preserved=${preserved}_disabled=${disabled}" >&2
  exit 1
}
echo "RV64_BOARD_LEAN_SELFTEST_PASS preserved=${preserved} disabled=${disabled} rejected=2 full_object_guard=1 guest_cycles=0 no_linux_build=1"
