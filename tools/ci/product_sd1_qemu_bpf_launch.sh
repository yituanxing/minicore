#!/usr/bin/env bash
# Separate bounded QEMU/GDB icount probe for the exact sd_bind6 BPF bytecode.
# Runs only the QEMU virt kernel against immutable signed Debian media. Never RTL.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/software/rv64/linux_early.env"
ORACLE_DIR="$ROOT/build/rv64-debian-sd1-qemu-oracle"
OBJ_DIR="$ORACLE_DIR/obj"
LINUX_IMAGE="$OBJ_DIR/arch/riscv/boot/Image"
SYSTEM_MAP="$OBJ_DIR/System.map"
ORACLE_RESULT="$ORACLE_DIR/result.txt"
ORACLE_BOOT_RESULT="$ORACLE_DIR/boot-result.txt"
PRODUCT_CONFIG="$ROOT/build/rv64-debian-linux/obj/.config"
SD_RESULT="$ROOT/build/rv64-debian-sd1-sd/result.txt"
SD_IMAGE="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
GDB_SOCKET="$ORACLE_DIR/bpf-gdb.sock"
QMP_SOCKET="$ORACLE_DIR/bpf-qmp.sock"
QEMU_UART="$ORACLE_DIR/bpf-qemu-uart.log"
QEMU_STDERR="$ORACLE_DIR/bpf-qemu-stderr.log"
OUTPUT="$ORACLE_DIR/bpf-icount-probe.json"
EXPECTED_BPF_SHA=afb497f885ce0b5e185a292562bc2f56c8c1a2544f82545d24a23b68c0da9d06

fail() { printf 'SD1_QEMU_BPF_REJECTED: %s\n' "$*" >&2; exit 16; }
field() {
  local key="$1" file="$2" value
  [[ -s "$file" ]] || fail "missing manifest $file"
  [[ "$(grep -Ec "^$key=" "$file")" == 1 ]] || fail "missing/duplicate $key"
  value="$(sed -n "s/^$key=//p" "$file")"
  [[ -n "$value" ]] || fail "empty manifest field $key"
  printf '%s\n' "$value"
}
[[ -s "$LINUX_IMAGE" && -s "$SYSTEM_MAP" && -s "$SD_IMAGE" &&
   -s "$PRODUCT_CONFIG" ]] ||
  fail 'QEMU kernel, System.map, Product config or exact signed SD media missing'
grep -Fxq 'PRODUCT_SD1_QEMU_ORACLE_KERNEL_RESULT: status=READY_UNVERIFIED' "$ORACLE_RESULT" ||
  fail 'QEMU virtual kernel has not passed preparation'
grep -Fxq 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "$SD_RESULT" ||
  fail 'original SD image not prepared'
grep -Fxq 'PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL' "$ORACLE_BOOT_RESULT" ||
  fail 'original serial-native QEMU Debian login oracle did not pass'
[[ "$(field qualification "$ORACLE_RESULT")" == QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL &&
   "$(field stage "$ORACLE_RESULT")" == sd1 &&
   "$(field stage "$SD_RESULT")" == sd1 &&
   "$(field source_kernel_sha256 "$ORACLE_RESULT")" == "$RV64_LINUX_SHA256" &&
   "$(field product_config_sha256 "$ORACLE_RESULT")" == "$(sha256sum "$PRODUCT_CONFIG" | cut -d' ' -f1)" &&
   "$(field oracle_config_sha256 "$ORACLE_RESULT")" == "$(sha256sum "$OBJ_DIR/.config" | cut -d' ' -f1)" ]] ||
  fail 'Linux source/config identity drift or non-SD1 stage'
[[ "$(sha256sum "$LINUX_IMAGE" | cut -d' ' -f1)" == "$(field oracle_image_sha256 "$ORACLE_RESULT")" ]] ||
  fail 'QEMU Image SHA drift'
[[ "$(sha256sum "$SD_IMAGE" | cut -d' ' -f1)" == "$(field sd_image_sha256 "$ORACLE_RESULT")" &&
   "$(field sd_image_sha256 "$ORACLE_RESULT")" == "$(field sd_image_sha256 "$SD_RESULT")" ]] ||
  fail 'signed Debian SD SHA drift'
command -v qemu-system-riscv64 >/dev/null || fail 'qemu-system-riscv64 unavailable'
[[ ! -e "$OUTPUT" && ! -e "$GDB_SOCKET" && ! -e "$QMP_SOCKET" &&
   ! -e "$QEMU_UART" && ! -e "$QEMU_STDERR" ]] ||
  fail 'prior QEMU BPF probe evidence exists; refusing overwrite'
qemu_pid=''
cleanup() {
  if [[ -n "$qemu_pid" ]] && kill -0 "$qemu_pid" 2>/dev/null; then
    kill "$qemu_pid" 2>/dev/null || true
    wait "$qemu_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

qemu-system-riscv64 \
  -machine virt -m 256M -smp 1 \
  -nographic -monitor none -serial "file:$QEMU_UART" \
  -bios default -kernel "$LINUX_IMAGE" \
  -append 'earlycon=sbi console=ttyS0,115200 root=/dev/vda1 rootfstype=ext4 rootwait rw init=/sbin/init systemd.unit=multi-user.target' \
  -drive "file=$SD_IMAGE,format=raw,if=none,id=sd1,snapshot=on" \
  -device virtio-blk-device,drive=sd1 \
  -S -icount shift=6,align=off,sleep=off \
  -gdb "unix:$GDB_SOCKET,server=on,wait=off" \
  -qmp "unix:$QMP_SOCKET,server=on,wait=off" \
  -no-reboot > /dev/null 2> "$QEMU_STDERR" &
qemu_pid=$!
for ((attempt=0; attempt<100; attempt++)); do
  [[ -S "$GDB_SOCKET" && -S "$QMP_SOCKET" ]] && break
  kill -0 "$qemu_pid" 2>/dev/null || fail 'QEMU exited before debug sockets appeared'
  sleep 0.1
done
[[ -S "$GDB_SOCKET" && -S "$QMP_SOCKET" ]] || fail 'QEMU debug sockets missing'

timeout --signal=TERM --kill-after=10s 8m \
  python3 "$ROOT/tools/ci/product_sd1_qemu_bpf_icount_probe.py" \
    --gdb-socket "$GDB_SOCKET" --qmp-socket "$QMP_SOCKET" \
    --system-map "$SYSTEM_MAP" --kernel "$LINUX_IMAGE" \
    --expected-bpf-sha "$EXPECTED_BPF_SHA" \
    --icount-shift 6 --wall-seconds 420 \
    --serial-log "$QEMU_UART" --output "$OUTPUT"
[[ -s "$OUTPUT" ]] || fail 'QEMU BPF probe produced no authenticated evidence'
[[ "$(sha256sum "$LINUX_IMAGE" | cut -d' ' -f1)" == "$(field oracle_image_sha256 "$ORACLE_RESULT")" ]] ||
  fail 'QEMU kernel changed during BPF probe'
[[ "$(sha256sum "$SD_IMAGE" | cut -d' ' -f1)" == "$(field sd_image_sha256 "$SD_RESULT")" ]] ||
  fail 'signed source media changed during QEMU BPF probe'
echo 'PRODUCT_SD1_QEMU_BPF_SAME_BYTECODE_ICOUNT_ONLY_NOT_RTL_PASS'
