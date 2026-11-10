#!/usr/bin/env bash
# Optional, tightly bounded G5 Product-fw QEMU pre-PID1 state capture only.
# This script never starts Product RTL or claims a QEMU -> RTL handoff PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ "${1:-}" == run && "${AETHERCORE_ALLOW_G5_QEMU_PRODUCT_EXPORT:-0}" == 1 ]] || {
  echo 'PRODUCT_G5_QEMU_ARCH_EXPORT_REJECTED: explicit opt-in required' >&2
  exit 3
}
[[ "${RV64_DEBIAN_STAGE:-}" == d2 ]] || {
  echo 'PRODUCT_G5_QEMU_ARCH_EXPORT_REJECTED: D2 only' >&2
  exit 3
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'PRODUCT_G5_QEMU_ARCH_EXPORT_REJECTED: external SD/DTB forbidden' >&2
  exit 3
}

QEMU="$(command -v "${AETHERCORE_G5_QEMU_SYSTEM_BIN:-qemu-system-riscv64}" || true)"
[[ -n "$QEMU" && -x "$QEMU" ]] || { echo 'missing system QEMU' >&2; exit 3; }
WALL="${AETHERCORE_G5_EXPORT_WALL_SECONDS:-180}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 10 && WALL <= 180 )) || {
  echo 'export wall budget must be 10..180 seconds' >&2
  exit 3
}

BOOT="$ROOT/build/rv64-debian-boot"
LINUX="$ROOT/build/rv64-debian-linux"
FW="$BOOT/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="$BOOT/aethercore-rv64-initramfs.dtb"
IMAGE="$LINUX/obj/arch/riscv/boot/Image"
MAP="$LINUX/obj/System.map"
CONFIG="$LINUX/evidence/resolved.config"
OUT="${PRODUCT_G5_QEMU_EXPORT_OUT:-$ROOT/build/product-g5-qemu-export}"

for file in "$FW" "$DTB" "$IMAGE" "$MAP" "$CONFIG"; do
  [[ -s "$file" ]] || { echo "missing required Product input: $file" >&2; exit 3; }
done
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$BOOT/result.txt"
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX/result.txt"
ORACLE="$ROOT/build/product-g5-qemu-product-fw-dtb/result.txt"
grep -Fxq 'qualification=QEMU_SYSTEM_PRODUCT_FW_DTB_D2_ORACLE_NOT_PRODUCT_RTL_PASS' \
  "$ORACLE" || { echo 'Product-fw QEMU D2 oracle must PASS before export' >&2; exit 3; }
for entry in "fw_sha256:$FW" "fdt_sha256:$DTB" "linux_image_sha256:$IMAGE"; do
  key="${entry%%:*}"
  file="${entry#*:}"
  prior="$(sed -n "s/^${key}=//p" "$ORACLE" | head -n 1)"
  current="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$prior" == "$current" ]] || {
    echo "Product-fw oracle identity drift: $key" >&2
    exit 3
  }
done
grep -Fxq '# CONFIG_RELOCATABLE is not set' "$CONFIG" || {
  echo 'relocatable kernel disabled prerequisite not met' >&2; exit 3;
}
grep -Fxq '# CONFIG_RANDOMIZE_BASE is not set' "$CONFIG" || {
  echo 'kernel KASLR disabled prerequisite not met' >&2; exit 3;
}
# No stale evidence or socket may be reused for a later commit/image.
if [[ -e "$OUT" && -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "refusing nonempty existing checkpoint directory: $OUT" >&2
  exit 3
fi
mkdir -p "$OUT"
SOCKET_GDB="$OUT/gdb.sock"
SOCKET_QMP="$OUT/qmp.sock"
START="$(date +%s)"

qemu_pid=''
cleanup() {
  if [[ -n "$qemu_pid" ]]; then
    kill "$qemu_pid" 2>/dev/null || true
    wait "$qemu_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo 'PRODUCT_G5_QEMU_ARCH_EXPORT_START Product-fw=1 Product-DTB=1 Product-RTL=0'
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "$QEMU" -machine virt -cpu rv64 -smp 1 -m 256M \
  -bios "$FW" -dtb "$DTB" \
  -device "loader,file=$DTB,addr=0x87f00000,force-raw=on" \
  -display none -monitor none -serial "file:$OUT/serial.log" \
  -no-reboot -no-shutdown -S \
  -chardev "socket,path=$SOCKET_GDB,server=on,wait=off,id=gdb0" \
  -gdb chardev:gdb0 -qmp "unix:$SOCKET_QMP,server=on,wait=off" \
  > "$OUT/qemu.log" 2>&1 &
qemu_pid=$!

# Socket readiness is intentionally bounded and distinct from the CPU boot.
until [[ -S "$SOCKET_GDB" && -S "$SOCKET_QMP" ]]; do
  kill -0 "$qemu_pid" 2>/dev/null || { echo 'QEMU exited before sockets' >&2; exit 3; }
  if (( $(date +%s) - START >= 15 )); then
    echo 'QEMU socket startup exceeded 15s' >&2
    exit 3
  fi
  sleep 0.1
done

REMAINING="$((WALL - ($(date +%s) - START)))"
(( REMAINING > 0 )) || { echo 'QEMU export time budget exhausted' >&2; exit 3; }
timeout --signal=TERM --kill-after=5s "${REMAINING}s" \
  python3 "$ROOT/tools/ci/product_g5_qemu_export/qemu_arch_export.py" \
    --qmp-socket "$SOCKET_QMP" --gdb-socket "$SOCKET_GDB" \
    --out-dir "$OUT/export" --system-map "$MAP" \
    --break-symbol run_init_process --drive-to-breakpoint \
    --wall-seconds "$REMAINING" \
    --firmware "$FW" --dtb "$DTB" --linux-image "$IMAGE" \
  > "$OUT/export.log" 2>&1

grep -Fq 'QEMU_PRODUCT_ARCH_EXPORT_CAPTURED_DIAGNOSTIC_ONLY' "$OUT/export.log"
python3 - "$OUT/export/manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["qualification"] == "QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS"
assert manifest["arch_restore_ready"] is False
assert manifest["rtl_cpu_injected"] is False
assert manifest["memory"]["bytes"] == 256 * 1024 * 1024
PY

# The explicitly labeled CI job uploads this RAM + manifest for later RTL
# handoff investigation. Generic Product QEMU oracle does not upload RAM.
sha256sum "$FW" "$DTB" "$IMAGE" "$MAP" "$CONFIG" \
  "$OUT/export/manifest.json" "$OUT/export/ram-0x80000000-0x10000000.bin" \
  > "$OUT/sha256sums.txt"
printf 'qualification=QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS\nproduct_rtl=0\n' \
  > "$OUT/result.txt"
echo 'PRODUCT_G5_QEMU_ARCH_EXPORT_DIAGNOSTIC_PASS Product-RTL=0'
