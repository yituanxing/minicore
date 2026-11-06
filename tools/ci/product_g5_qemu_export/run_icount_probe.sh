#!/usr/bin/env bash
set -euo pipefail

# One bounded, exact-Product-firmware QEMU run, with two kernel GDB breakpoints.
# Counts QEMU guest instructions only; never reports AetherCore RTL cycles.
# Intentionally opt-in and isolated from expensive Product RTL CI.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"
fail() { echo "G5_QEMU_ICOUNT_REJECTED: $*" >&2; exit 3; }

[[ "$#" == 1 && "$1" == run ]] || {
  echo 'usage: AETHERCORE_ALLOW_G5_QEMU_ICOUNT=1 run_icount_probe.sh run' >&2
  exit 2
}
[[ "${AETHERCORE_ALLOW_G5_QEMU_ICOUNT:-0}" == 1 ]] || fail 'requires explicit icount opt-in'
[[ "${RV64_DEBIAN_STAGE:-d2}" == d2 ]] || fail 'native Debian D2 required'
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'physical SD image and arbitrary DTB injection prohibited'

QEMU="${AETHERCORE_G5_QEMU_SYSTEM_BIN:-qemu-system-riscv64}"
QEMU_PATH="$(command -v "$QEMU" || true)"
[[ -n "$QEMU_PATH" && -x "$QEMU_PATH" ]] || fail 'no usable QEMU RISC-V binary'
WALL="${AETHERCORE_G5_QEMU_WALL_SECONDS:-300}"
SHIFT="${AETHERCORE_G5_QEMU_ICOUNT_SHIFT:-6}"
[[ "$WALL" =~ ^[0-9]+$ ]] && (( WALL >= 10 && WALL <= 600 )) || fail 'invalid WALL 10..600'
[[ "$SHIFT" =~ ^[0-9]+$ ]] && (( SHIFT <= 16 )) || fail 'invalid icount shift 0..16'

FW="$ROOT/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="$ROOT/build/rv64-debian-boot/aethercore-rv64-initramfs.dtb"
IMAGE="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
MAP="$ROOT/build/rv64-debian-linux/obj/System.map"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
PAYLOAD_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
for path in "$FW" "$DTB" "$IMAGE" "$MAP" "$ROOTFS_RESULT" "$LINUX_RESULT" "$PAYLOAD_RESULT"; do
  [[ -s "$path" ]] || fail "missing exact Product input: $path"
done
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || fail 'rootfs not qualified'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || fail 'Linux not qualified'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$PAYLOAD_RESULT" || fail 'Product payload not qualified'
grep -Fxq 'stage=d2' "$ROOTFS_RESULT" && grep -Fxq 'stage=d2' "$LINUX_RESULT" || fail 'stage drift'
grep -Fxq 'fdt_addr=0x87f00000' "$PAYLOAD_RESULT" || fail 'Product DTB address drift'
grep -Fxq "firmware_bin=$FW" "$PAYLOAD_RESULT" || fail 'firmware path mismatch'
grep -Fxq "fdt=$DTB" "$PAYLOAD_RESULT" || fail 'DTB path mismatch'
grep -Fxq "linux_image=$IMAGE" "$PAYLOAD_RESULT" || fail 'Linux Image path mismatch'
[[ "$(sed -n 's/^linux_image_sha256=//p' "$PAYLOAD_RESULT" | head -1)" == \
   "$(sha256sum "$IMAGE" | cut -d ' ' -f1)" ]] || fail 'Linux Image SHA drift'

OUT="${AETHERCORE_G5_QEMU_ICOUNT_OUT:-$ROOT/build/product-g5-qemu-icount}"
[[ ! -e "$OUT" && ! -L "$OUT" && "$OUT" != *' '* ]] ||
  fail 'output must be fresh, non-symlink, and contain no spaces'
mkdir -p -- "$OUT"
OUT="$(realpath -- "$OUT")"

# Same firmware/DTB/Linux Image QEMU-virt contract as
# tools/ci/product_g5_qemu_product_firmware_oracle.sh, with -S and icount.
# GDB and QMP bind Unix sockets; serial and diagnostics are separate files.
timeout --signal=TERM --kill-after=5s "${WALL}s" \
  "$QEMU_PATH" -machine virt -cpu rv64 -smp 1 -m 256M \
    -bios "$FW" -dtb "$DTB" \
    -device "loader,file=$DTB,addr=0x87f00000,force-raw=on" \
    -display none -monitor none -serial "file:$OUT/serial.log" \
    -no-reboot -no-shutdown -S \
    -icount "shift=$SHIFT,align=off,sleep=off" \
    -chardev "socket,path=$OUT/gdb.sock,server=on,wait=off,id=gdb0" \
    -gdb chardev:gdb0 \
    -qmp "unix:$OUT/qmp.sock,server=on,wait=off" \
    > "$OUT/qemu.log" 2>&1 &
QEMU_PID=$!
trap 'kill "$QEMU_PID" 2>/dev/null || true; wait "$QEMU_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 100); do
  [[ -S "$OUT/gdb.sock" && -S "$OUT/qmp.sock" ]] && break
  kill -0 "$QEMU_PID" 2>/dev/null || fail 'QEMU exited before opening sockets'
  sleep 0.1
done
[[ -S "$OUT/gdb.sock" && -S "$OUT/qmp.sock" ]] || fail 'QEMU sockets unavailable after 10s'

python3 "$ROOT/tools/ci/product_g5_qemu_export/qemu_icount_probe.py" \
  --gdb-socket "$OUT/gdb.sock" --qmp-socket "$OUT/qmp.sock" \
  --out-dir "$OUT" --firmware "$FW" --dtb "$DTB" \
  --linux-image "$IMAGE" --system-map "$MAP" \
  --icount-shift "$SHIFT" --wall-seconds "$WALL" \
  > "$OUT/probe.log" 2>&1 || {
    tail -40 "$OUT/probe.log" >&2
    fail 'QEMU instruction measurement failed; see probe.log/qemu.log/serial.log'
  }

grep -Fq 'Linux version 6.6.143' "$OUT/serial.log" || fail 'Linux banner absent'
grep -Fq 'G5_QEMU_ICOUNT_DIFFERENCE ' "$OUT/probe.log" || fail 'QEMU count absent'
cat "$OUT/probe.log"
printf 'qualification=QEMU_G5_D2_INSN_COUNT_NOT_RTL_CYCLES_NOT_PID1_PASS\n' > "$OUT/result.txt"
printf 'stage=d2\nproduct_rtl=0\ncycle_accurate=0\nqemu_icount_shift=%s\n' "$SHIFT" >> "$OUT/result.txt"
printf 'qemu_sha256=%s\n' "$(sha256sum "$QEMU_PATH" | cut -d ' ' -f1)" >> "$OUT/result.txt"
echo 'G5_QEMU_ICOUNT_PROBE_PASS guest_instructions_measured=1 Product_RTL_cycles_measured=0'
