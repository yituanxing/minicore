#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SCRIPT="$ROOT/tools/ci/product_g5_qemu_product_firmware_oracle.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-$ROOT}/.g5-qemu-product-test.XXXXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

fixture="$TMP_ROOT/serial.log"
printf '%s\n' \
  '[    0.000000] Linux version 6.6.143' \
  'AETHERCORE_DEBIAN_D2_START' \
  'AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK' \
  'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK' > "$fixture"
bash "$SCRIPT" check-log "$fixture" | grep -Fq 'PRODUCT_G5_QEMU_PRODUCT_FW_DTB_SELFTEST_PASS'

for missing in \
  'AETHERCORE_DEBIAN_D2_START' \
  'AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK' \
  'DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK'; do
  sed "/${missing}/d" "$fixture" > "$TMP_ROOT/negative.log"
  if bash "$SCRIPT" check-log "$TMP_ROOT/negative.log" >/dev/null 2>&1; then
    echo "ERROR: missing $missing was accepted" >&2; exit 1
  fi
done

cp "$fixture" "$TMP_ROOT/negative.log"
printf '%s\n' 'AETHERCORE_DEBIAN_D2_FAIL:futex' >> "$TMP_ROOT/negative.log"
if bash "$SCRIPT" check-log "$TMP_ROOT/negative.log" >/dev/null 2>&1; then
  echo 'ERROR: D2 failure marker was accepted' >&2; exit 1
fi

# Check that a normal test runner cannot silently execute a real guest.
if AETHERCORE_ALLOW_G5_QEMU_PRODUCT_FIRMWARE=0 bash "$SCRIPT" run >/dev/null 2>&1; then
  echo 'ERROR: implicit Product QEMU execution was accepted' >&2; exit 1
fi
echo 'PRODUCT_G5_QEMU_PRODUCT_FW_DTB_SELFTESTS_PASS positive=1 negatives=4 implicit_run_rejected=1 guest_executions=0'
