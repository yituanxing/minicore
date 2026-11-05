# Product G5 QEMU → RTL checkpoint: diagnostic export, **not** RTL handoff

This optional helper captures a **bounded, reproducible architectural snapshot**
at a Linux kernel symbol from the *same* Product G5 OpenSBI firmware, Product
DTB and Debian D2 payload used by
`minicore/tools/ci/product_g5_qemu_product_firmware_oracle.sh`.

Its **only** passing marker is
`QEMU_PRODUCT_ARCH_EXPORT_CAPTURED_DIAGNOSTIC_ONLY ... rtl_handoff_pass=0`.
It cannot presently restore RTL and never claims G5/Debian RTL qualification.

## Workflow (optional, manual)

Run on a host with `qemu-system-riscv64`, a built Product D2 payload and an
unstripped Linux `System.map`. Provide unique, **existing-free** socket paths
under a run-specific directory (examples use `/your/scratch`). Set a task-local
root; do not substitute a different firmware or FDT.

```bash
PRODUCT_DIR=/path/to/minicore
RUN_DIR=/your/scratch/product-qemu-checkpoint
mkdir -p "$RUN_DIR"

FW="$PRODUCT_DIR/build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin"
DTB="$PRODUCT_DIR/build/rv64-debian-boot/aethercore-rv64-initramfs.dtb"
IMAGE="$PRODUCT_DIR/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
SYSTEM_MAP="$PRODUCT_DIR/build/rv64-debian-linux/obj/System.map"

qemu-system-riscv64 -machine virt -cpu rv64 -smp 1 -m 256M \
  -bios "$FW" -dtb "$DTB" \
  -device "loader,file=$DTB,addr=0x87f00000,force-raw=on" \
  -display none -monitor none -serial file:"$RUN_DIR/serial.log" \
  -no-reboot -no-shutdown -S \
  -chardev "socket,path=$RUN_DIR/gdb.sock,server=on,wait=off,id=gdb0" \
  -gdb chardev:gdb0 \
  -qmp "unix:$RUN_DIR/qmp.sock,server=on,wait=off"
```

In a *second* terminal, while that QEMU is paused at reset:

```bash
python3 "$PRODUCT_DIR/tools/ci/product_g5_qemu_export/qemu_arch_export.py" \
  --qmp-socket "$RUN_DIR/qmp.sock" \
  --gdb-socket "$RUN_DIR/gdb.sock" \
  --out-dir "$RUN_DIR/export" \
  --system-map "$SYSTEM_MAP" --break-symbol run_init_process \
  --drive-to-breakpoint --wall-seconds 180 \
  --firmware "$FW" --dtb "$DTB" --linux-image "$IMAGE"
```

The Product kernel may not contain that exact symbol in its `System.map`; in
that case explicitly choose an existing, unique, non-KASLR symbol. The exporter
will reject an unexpected PC. It also rejects non-paused guests, CPU count other
than one, a missing physical RAM dump and invalid input paths.

The exporter writes `manifest.json` and a physical 256 MiB RAM dump.
`manifest.json` hashes **all four input files** (firmware, Product DTB, Linux
Image, System.map), hashes RAM, records PC, GPRs, FPRs, advertised CSRs, optional
PMP state and read-only CLINT `mtime`/`mtimecmp`, as available from QEMU GDB.
Absent registers appear in `missing_gdb_registers` rather than becoming zeros.

## What this explicitly does **not** capture or implement

1. QEMU gdbstub does **not guarantee** all privilege, CSR, FPR and PMP values
   are available. Some outputs will be partial by design. CSR coverage is
   detected rather than assumed; `priv` mode may be unavailable.
2. PLIC priority, enable, pending, threshold and claim/complete state; UART
   FIFO/configuration; reservation (`LR/SC`) state; exact CLINT timer offsets;
   and pending IRQ edge history are **not** completely captured.
3. Product RTL currently lacks an architectural **warm-restore injection port**.
   Existing `.rtl` snapshots serialize full Verilated microarchitectural state
   and **cannot** consume QEMU VM state or this manifest. Any new port must load
   PC/GPR/FPR/FCSR/priv/CSR/PMP into an **empty** pipeline/ROB/IQ/LSQ/rename
   machinery, invalidate TLB/cache or establish coherent contents, and align
   timers/peripherals before release.
4. This helper is diagnostic glue for preparing that future import. It has
   **not** been exercised against a real QEMU in this workspace (QEMU binary
   absent). The local selftests mock QMP/RSP and do not qualify end-to-end
   QEMU protocol compatibility or RTL resume.

At best, this export enables QEMU to execute expensive OpenSBI/Linux cold boot
once, then Product RTL to resume a short window around a known architectural
boundary **after** we implement and validate the warm-restore path. It never
proves prior QEMU-executed instructions were executed by G5 RTL.

## Test

```bash
python3 /path/to/minicore/tools/ci/product_g5_qemu_export/selftest.py
```

No 110-minute Product RTL run is initiated by either the exporter or selftest.

## Opt-in GitHub Actions integration

The workflow `.github/workflows/product-g5-qemu-d2-oracle.yml` includes an
explicit `product-g5-qemu-export` label and uses
`tools/ci/product_g5_qemu_arch_export.sh` plus the Python exporter in
`tools/ci/product_g5_qemu_export/`. It remains **unqualified** until a real
CI export confirms QMP/GDB protocol compatibility.

`product-g5-qemu-d2-oracle` and manual dispatch retain the existing QEMU
oracle behavior. The **additional** PR label `product-g5-qemu-export` first
requires the original native D2 QEMU and exact Product fw_payload/DTB QEMU D2
PASS, verifies unchanged firmware/DTB/Image hashes and disabled kernel KASLR,
then starts a separate Product QEMU `-S` with the supported GDB unix socket and
QMP interfaces. A single 180-second budget encompasses QEMU start, kernel
breakpoint, RAM dump and export.

The opt-in job uploads RAM **256 MiB**, the JSON manifest, serial and checksums
for only seven days. Uploading RAM is essential to any later actual RTL resume
and avoids paying for the same QEMU boot a second time. The opt-in capture can
fail independently (without changing existing-label job execution). Neither a
successful QEMU capture nor a successful QEMU D2 oracle indicates RTL success.

The shell and YAML syntax plus Python mock contract tests have been checked.
Actual QEMU export requires CI execution with the supported QEMU binary.

## Sources

- Product firmware/DTB QEMU oracle: `minicore/tools/ci/product_g5_qemu_product_firmware_oracle.sh`
- RTL save/restore: `minicore/sim/l32_opensbi_runtime.h`
- [QEMU GDB / `-S`, unix sockets and breakpoints](https://www.qemu.org/docs/master/system/gdb.html)
- [QEMU monitor `pmemsave`](https://www.qemu.org/docs/master/system/monitor.html)
- [QEMU QMP monitor](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html)
