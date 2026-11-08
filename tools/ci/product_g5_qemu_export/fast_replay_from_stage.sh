#!/usr/bin/env bash
# Isolated Product D2 QEMU architecture export from one SHA-sealed CI artifact.
# Source is a real QEMU CPU with original Product-fw/D2 inputs, never RTL/SD1.
# This script avoids building Linux again and deliberately does not invent a
# resolved.config absent from the signed eight-input bundle.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"
fail() { echo "PRODUCT_C1_QEMU_FAST_SOURCE_REJECTED: $*" >&2; exit 3; }

[[ $# == 2 && "$1" == run && "${AETHERCORE_ALLOW_C1_QEMU_FAST_EXPORT:-0}" == 1 ]] ||
  fail 'explicit fast replay opt-in and stage directory required'
STAGE="$(realpath -e "$2")"
[[ -d "$STAGE" ]] || fail 'source input stage missing'

# This exact failed-export run first passed the full Product D2 QEMU oracle
# and published the source bytes. We validate both GitHub ZIP metadata in CI
# and the ORIGINAL SHA256SUMS/ORIGIN.txt inside this immutable bundle.
SOURCE_RUN=42484457962
SOURCE_SHA=f8490d3e0e1b0d7668335b50d8a8b435c76397ab
SOURCE_WORKFLOW=.github/workflows/product-g5-qemu-d2-oracle.yml
for field in "source_run_id=$SOURCE_RUN" 'source_run_attempt=1' \
  "source_sha=$SOURCE_SHA" "source_workflow=$SOURCE_WORKFLOW"; do
  grep -Fxq -- "$field" "$STAGE/ORIGIN.txt" || fail "origin mismatch: $field"
done
[[ -s "$STAGE/SHA256SUMS" ]] || fail 'origin SHA256SUMS missing'
(cd "$STAGE" && sha256sum --check --strict --status SHA256SUMS) ||
  fail 'source bundle byte identities do not match original SHA256SUMS'

FW="$STAGE/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="$STAGE/build/rv64-debian-boot/aethercore-rv64-initramfs.dtb"
IMAGE="$STAGE/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
MAP="$STAGE/build/rv64-debian-linux/obj/System.map"
ROOTFS_RESULT="$STAGE/build/rv64-debian-rootfs/result.txt"
LINUX_RESULT="$STAGE/build/rv64-debian-linux/result.txt"
BOOT_RESULT="$STAGE/build/rv64-debian-boot/result.txt"
ORACLE="$STAGE/build/product-g5-qemu-product-fw-dtb/result.txt"
for path in "$FW" "$DTB" "$IMAGE" "$MAP" "$ROOTFS_RESULT" \
  "$LINUX_RESULT" "$BOOT_RESULT" "$ORACLE"; do
  [[ -f "$path" && -s "$path" && ! -L "$path" ]] || fail "missing regular source file: $path"
done
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'D2 rootfs certificate absent'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || fail 'D2 Image certificate absent'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$BOOT_RESULT" || fail 'Product OpenSBI certificate absent'
grep -Fxq 'qualification=QEMU_SYSTEM_PRODUCT_FW_DTB_D2_ORACLE_NOT_PRODUCT_RTL_PASS' "$ORACLE" ||
  fail 'source native Product-fw QEMU oracle did not qualify'
grep -Fxq 'stage=d2' "$ORACLE" || fail 'source is not D2'
for pair in "fw_sha256:$FW" "fdt_sha256:$DTB" "linux_image_sha256:$IMAGE"; do
  key="${pair%%:*}"; path="${pair#*:}"
  original="$(sed -n "s/^${key}=//p" "$ORACLE" | head -1)"
  actual="$(sha256sum "$path" | cut -d ' ' -f1)"
  [[ "$original" == "$actual" ]] || fail "QEMU oracle SHA mismatch: $key"
done
[[ "$(sed -n 's/^image_sha256=//p' "$LINUX_RESULT" | head -1)" == \
   "$(sha256sum "$IMAGE" | cut -d ' ' -f1)" ]] || fail 'D2 Image drifted'

QEMU="$(command -v "${AETHERCORE_G5_QEMU_SYSTEM_BIN:-qemu-system-riscv64}" || true)"
[[ -n "$QEMU" && -x "$QEMU" ]] || fail 'system QEMU missing'
CPU="${AETHERCORE_C1_FAST_QEMU_CPU:-rv64}"
# Sv39 variant is source-derived, retaining the exact SHA-sealed Linux,
# OpenSBI and DTB binaries. Never accept arbitrary QEMU CPU overrides.
case "$CPU" in
  rv64|rv64,sv48=off,sv57=off|rv64,sv48=off,sv57=off,h=off) ;;
  *) fail "unqualified QEMU CPU profile: $CPU" ;;
esac
WALL="${AETHERCORE_C1_FAST_QEMU_WALL_SECONDS:-180}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 10 && WALL <= 180 )) || fail 'wall budget outside 10..180s'
OUT="${AETHERCORE_C1_FAST_QEMU_OUT:-$ROOT/build/product-c1-qemu-fast-export}"
[[ "$OUT" == /* && "$OUT" != *' '* ]] || fail 'output must be absolute and space-free'
if [[ -e "$OUT" && -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  fail 'refusing nonempty output; no prior checkpoint overwritten'
fi
mkdir -p "$OUT" "$OUT/export"
START="$(date +%s)"
SOCKET_GDB="$OUT/gdb.sock"
SOCKET_QMP="$OUT/qmp.sock"
qemu_pid=''
cleanup() {
  if [[ -n "$qemu_pid" ]]; then
    kill "$qemu_pid" 2>/dev/null || true
    wait "$qemu_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "PRODUCT_C1_QEMU_FAST_START source_run=$SOURCE_RUN source_sha=$SOURCE_SHA cpu=$CPU product_rtl=0 sd1=0"
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "$QEMU" -machine virt -cpu "$CPU" -smp 1 -m 256M \
  -bios "$FW" -dtb "$DTB" \
  -device "loader,file=$DTB,addr=0x87f00000,force-raw=on" \
  -display none -monitor none -serial "file:$OUT/serial.log" \
  -no-reboot -no-shutdown -S \
  -chardev "socket,path=$SOCKET_GDB,server=on,wait=off,id=gdb0" \
  -gdb chardev:gdb0 -qmp "unix:$SOCKET_QMP,server=on,wait=off" \
  > "$OUT/qemu.log" 2>&1 &
qemu_pid=$!

until [[ -S "$SOCKET_GDB" && -S "$SOCKET_QMP" ]]; do
  kill -0 "$qemu_pid" 2>/dev/null || fail 'QEMU exited before sockets'
  (( $(date +%s) - START < 15 )) || fail 'QEMU socket startup timeout'
  sleep 0.1
done
REMAINING=$((WALL - ($(date +%s) - START)))
(( REMAINING > 0 )) || fail 'QEMU export budget exhausted'
if ! timeout --signal=TERM --kill-after=5s "${REMAINING}s" \
  python3 "$ROOT/tools/ci/product_g5_qemu_export/qemu_arch_export.py" \
    --qmp-socket "$SOCKET_QMP" --gdb-socket "$SOCKET_GDB" \
    --out-dir "$OUT/export" --system-map "$MAP" \
    --break-symbol run_init_process --drive-to-breakpoint \
    --wall-seconds "$REMAINING" \
    --firmware "$FW" --dtb "$DTB" --linux-image "$IMAGE" \
  > "$OUT/export.log" 2>&1; then
  echo 'PRODUCT_C1_QEMU_FAST_CAPTURE_FAILED (see export.log)' >&2
  tail -n 45 "$OUT/export.log" >&2 || true
  exit 4
fi
grep -Fq 'QEMU_PRODUCT_ARCH_EXPORT_CAPTURED_DIAGNOSTIC_ONLY' "$OUT/export.log" ||
  fail 'QEMU exporter did not record diagnostic-only capture marker'

RAM="$OUT/export/ram-0x80000000-0x10000000.bin"
[[ -f "$RAM" && "$(stat -c %s "$RAM")" == 268435456 ]] ||
  fail 'exact 256MiB QEMU physical memory absent'
sha256sum "$FW" "$DTB" "$IMAGE" "$MAP" "$RAM" \
  "$OUT/export/manifest.json" > "$OUT/source-sha256sums.txt"

# The review intentionally reports incompleteness as rc=3 while still
# preserving a JSON missing-CSR/FPR/PMP matrix. Never treat it as RTL PASS.
set +e
python3 "$ROOT/tools/ci/product_c1_arch_relay/preflight.py" \
  --manifest "$OUT/export/manifest.json" --ram "$RAM" \
  --firmware "$FW" --dtb "$DTB" --linux-image "$IMAGE" \
  --system-map "$MAP" --report "$OUT/source-review.json" \
  > "$OUT/review.log" 2>&1
review_rc=$?
set -e
if [[ "$review_rc" != 0 && "$review_rc" != 3 ]]; then
  echo 'PRODUCT_C1_QEMU_FAST_REVIEW_REJECTED' >&2
  tail -n 40 "$OUT/review.log" >&2 || true
  exit 5
fi
[[ -s "$OUT/source-review.json" ]] || fail 'preflight failed to preserve source report'
printf 'qualification=QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS\n' > "$OUT/result.txt"
printf 'source_run=%s\nsource_sha=%s\nqemu_cpu=%s\nreview_rc=%s\n' "$SOURCE_RUN" "$SOURCE_SHA" "$CPU" "$review_rc" >> "$OUT/result.txt"
printf 'product_rtl=0\nsd1=0\n' >> "$OUT/result.txt"
echo "PRODUCT_C1_QEMU_FAST_CAPTURED_DIAGNOSTIC_NOT_RTL_PASS review_rc=$review_rc"
