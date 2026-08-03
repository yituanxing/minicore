#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BUILD_DIR="$ROOT/build/freertos-qualification"
LOG_DIR="$ROOT/build/ci/logs"
EVIDENCE_DIR="$BUILD_DIR/evidence"
LOCK_FILE="$ROOT/software/freertos/FreeRTOS-Kernel.lock"
TOOLCHAIN_ROOT="${AETHERCORE_RISCV_NONE_ELF_ROOT:-${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}/xpack-riscv-none-elf-gcc-15.2.0-1}"
TOOLCHAIN_SHA256="aaaa8060c914851a3e5ee1ba82cc3d6f80972f90638a05c6e823a37557a33758"
JOBS="${AETHERCORE_JOBS:-0}"
if [[ "$JOBS" == "0" ]]; then
  JOBS="$(nproc)"
fi
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: invalid AETHERCORE_JOBS=$JOBS" >&2; exit 1; }

rm -rf "$BUILD_DIR"
mkdir -p "$LOG_DIR" "$EVIDENCE_DIR"

test ! -e "$ROOT/software/freertos/aethercore/freestanding/stdlib.h"
test ! -e "$ROOT/software/freertos/aethercore/freestanding/string.h"
test -x "$TOOLCHAIN_ROOT/bin/riscv-none-elf-gcc"
test "$(cat "$TOOLCHAIN_ROOT/.aethercore-archive-sha256")" = "$TOOLCHAIN_SHA256"
test "$(readlink -f "$(command -v riscv-none-elf-gcc)")" = \
  "$(readlink -f "$TOOLCHAIN_ROOT/bin/riscv-none-elf-gcc")"
test "$(riscv-none-elf-gcc -dumpmachine)" = "riscv-none-elf"
test "$(riscv-none-elf-gcc -dumpfullversion)" = "15.2.0"

sysroot="$(riscv-none-elf-gcc --print-sysroot)"
test -n "$sysroot"
test -d "$sysroot"
case "$sysroot" in
  "$TOOLCHAIN_ROOT"/*) ;;
  *) echo "ERROR: FreeRTOS compiler escaped the pinned sysroot: $sysroot" >&2; exit 1 ;;
esac

test -f "$sysroot/include/stdlib.h"
test -f "$sysroot/include/string.h"
test "$(riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-multi-directory)" = \
  "rv32im/ilp32"
test -f "$(riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-file-name=libc.a)"
test -f "$(riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-libgcc-file-name)"

printf '%s\n' '#include <stddef.h>' '#include <stdint.h>' \
  '#include <stdlib.h>' '#include <string.h>' | \
  riscv-none-elf-gcc --sysroot="$sysroot" -march=rv32im_zicsr -mabi=ilp32 \
    -ffreestanding -fno-builtin -E -H -Wp,-v -x c - \
    >/dev/null 2> "$LOG_DIR/freertos-toolchain-include-search.log"

awk '/^\.+ \/.*$/ { sub(/^\.+ /, ""); print }' \
  "$LOG_DIR/freertos-toolchain-include-search.log" \
  > "$LOG_DIR/freertos-toolchain-header-paths.txt"
: > "$LOG_DIR/freertos-toolchain-header-paths-real.txt"
while IFS= read -r header; do
  test -f "$header" || continue
  readlink -f "$header" >> "$LOG_DIR/freertos-toolchain-header-paths-real.txt"
done < "$LOG_DIR/freertos-toolchain-header-paths.txt"
sort -u -o "$LOG_DIR/freertos-toolchain-header-paths-real.txt" \
  "$LOG_DIR/freertos-toolchain-header-paths-real.txt"

expected_stdlib="$(readlink -f "$sysroot/include/stdlib.h")"
expected_string="$(readlink -f "$sysroot/include/string.h")"
grep -Fxq "$expected_stdlib" "$LOG_DIR/freertos-toolchain-header-paths-real.txt"
grep -Fxq "$expected_string" "$LOG_DIR/freertos-toolchain-header-paths-real.txt"
! grep -Eq '^(/usr/include|/usr/local/include)(/|$)' \
  "$LOG_DIR/freertos-toolchain-header-paths-real.txt"
! grep -Fq "$ROOT/software/freertos/aethercore/freestanding" \
  "$LOG_DIR/freertos-toolchain-header-paths-real.txt"

set -o pipefail
make -j"$JOBS" -f Makefile.freertos JOBS="$JOBS" TRACE=1 run-local \
  2>&1 | tee "$LOG_DIR/freertos-rv32-local.log"

contract="$BUILD_DIR/port-contract.json"
elf="$BUILD_DIR/aethercore-freertos.elf"
binary="$BUILD_DIR/aethercore-freertos.bin"
disassembly="$BUILD_DIR/aethercore-freertos.dis"
attributes="$BUILD_DIR/aethercore-freertos.attributes"

for path in "$contract" "$elf" "$binary" "$disassembly" "$attributes"; do
  test -s "$path"
done

grep -q '"status": "PASS"' "$contract"
grep -q '"release": "V11.3.0"' "$contract"
grep -q '"mhartid": 0' "$contract"
grep -q '<freertos_risc_v_trap_handler>:' "$disassembly"
grep -Eq '\bcsrr[[:space:]].*mhartid' "$disassembly"
grep -q '\bmret\b' "$disassembly"
grep -Eq '\bwfi\b' "$disassembly"
grep -Fq 'FREERTOS BOOT V11.3.0 RV32IM' "$LOG_DIR/freertos-rv32-local.log"
grep -Fq 'FREERTOS IDLE PASS wfi>=1 wake>=1' "$LOG_DIR/freertos-rv32-local.log"
grep -Fq 'FREERTOS PASS queue=64 semaphore=8 ticks>=16' "$LOG_DIR/freertos-rv32-local.log"
grep -Fq 'PASS: self-check exit=0' "$LOG_DIR/freertos-rv32-local.log"
! grep -Fq 'FAIL:' "$LOG_DIR/freertos-rv32-local.log"

revision="$(sed -n 's/^revision=//p' "$LOCK_FILE")"
binary_sha="$(sha256sum "$binary" | awk '{print $1}')"
binary_bytes="$(stat -c %s "$binary")"
stdlib_sha="$(sha256sum "$sysroot/include/stdlib.h" | awk '{print $1}')"
string_sha="$(sha256sum "$sysroot/include/string.h" | awk '{print $1}')"
libc_path="$(riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-file-name=libc.a)"
libgcc_path="$(riscv-none-elf-gcc -march=rv32im -mabi=ilp32 -print-libgcc-file-name)"

cat > "$EVIDENCE_DIR/result.txt" <<EOF
status=PASS
release=V11.3.0
revision=$revision
profile=rv32im_zicsr_m
mhartid=0
mtime=0x0200bff8
mtimecmp=0x02004000
workload_messages=64
workload_semaphore_batches=8
minimum_ticks=16
idle_wfi=true
idle_wfi_wake=true
parallel_jobs=$JOBS
toolchain=xpack-riscv-none-elf-gcc-15.2.0-1
toolchain_archive_sha256=$TOOLCHAIN_SHA256
toolchain_target=riscv-none-elf
toolchain_sysroot=$sysroot
toolchain_multilib=rv32im/ilp32
sysroot_stdlib=$expected_stdlib
sysroot_string=$expected_string
sysroot_stdlib_sha256=$stdlib_sha
sysroot_string_sha256=$string_sha
rv32_libc=$libc_path
rv32_libgcc=$libgcc_path
project_standard_header_shims=false
binary_bytes=$binary_bytes
binary_sha256=$binary_sha
local_stall_period=5
EOF

cp "$LOCK_FILE" "$EVIDENCE_DIR/FreeRTOS-Kernel.lock"
cp "$contract" "$EVIDENCE_DIR/port-contract.json"
cp "$attributes" "$EVIDENCE_DIR/elf-attributes.txt"
cp "$LOG_DIR/freertos-toolchain-include-search.log" \
  "$EVIDENCE_DIR/toolchain-include-search.txt"
cp "$LOG_DIR/freertos-toolchain-header-paths-real.txt" \
  "$EVIDENCE_DIR/toolchain-header-paths-real.txt"
riscv-none-elf-gcc --version > "$EVIDENCE_DIR/toolchain-version.txt"
riscv-none-elf-gcc -print-multi-lib > "$EVIDENCE_DIR/toolchain-multilib.txt"
sha256sum "$elf" "$binary" > "$EVIDENCE_DIR/artifacts.sha256"
cat "$EVIDENCE_DIR/result.txt"
