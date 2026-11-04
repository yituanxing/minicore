# G5: Debian riscv64 on the same Product SmallOoO (2026-11-04)

## Why G5 is a separate workload

The retained G4 qualification is **real Linux**, but its unchanged kernel
disables `CONFIG_RISCV_ISA_C` and `CONFIG_FPU`, its firmware is RV64IMA/LP64,
and its `/init` is a static assembly payload. G4 is not a Debian userspace
test. Never reinterpret a successful G4 UART marker as Debian qualification.

G5 selects the already-described `smallOpenSbiDistroTarget` **on the same
Product SoC / SmallOoO implementation** and changes only the requested ISA,
ABI, kernel and userspace workload:

| Layer | Frozen G4 | G5 candidate |
| --- | --- | --- |
| Product hardware | engineering IMAC, no F/D | RV64GC + HardFloat F/D + RVC |
| ABI | LP64 | LP64D |
| OpenSBI/DTB | RV64IMA | RV64IMAFDC + Zicsr/Zifencei |
| Linux source | upstream 6.6.143 | same unchanged source, separate Kbuild output |
| Kernel settings | no C, no FPU | C + FPU + ELF + script + initramfs |
| PID1 | static hand-written assembly | official Debian `dash` + glibc, interpreted `/init` |
| Proof | `RV64 USER UART IRQ OK` | `DEBIAN_RISCV64_GLIBC_PID1_OK` |

## Traceable input and hard boundaries

- `rv64_debian_rootfs_build.sh` extracts the official
  `debian:trixie-20261027-slim` **linux/riscv64** OCI image without running
  foreign programs on the CI host. Its actual repository digest, release,
  size and executable SHA256 values are preserved as CI evidence.
- The Debian shell is checked as an ELF64 RISC-V **double-float ABI** binary
  with the exact glibc interpreter `/lib/ld-linux-riscv64-lp64d.so.1`.
- A host QEMU user-mode oracle runs the **same** Debian `dash` and `dpkg` ELF
  with the same rootfs-supplied loader. The DUT must then execute these native
  files through its own OpenSBI/Sv39/Linux hardware path.
- The frozen G4 payload builder remains the single OpenSBI/DTB/link-address
  implementation. G5 passes explicit image, ISA and ABI overrides; the
  original defaults and G4 markers remain intact. Machine-mode firmware uses
  LP64, independently of Debian userland's required LP64D ABI.
- Simulation memory is 256 MiB at `0x80000000`; firmware starts there and
  Linux is handed off at `0x80200000`. The payload builder rejects any
  firmware blob reaching the fixed DTB placement at `0x87f00000`.
- G5 is an opt-in long-running CI gate. The first PR modification to its
  workflow requests one real runtime qualification; it does not weaken or
  substitute for G1/G2/G3/G4 or the four-way FPGA PPA gate.
- G5 requires compressed instructions to be retired in OpenSBI and Linux.
  The shared runner uses the actual RV64 handoff `0x80200000` only for G5;
  the frozen RV32 `0x80400000` classification remains unchanged.

## Milestones and what may be claimed

1. **D1**: G5 sees Linux 6.6.143 and its own Debian dynamic PID1 marker,
   with the actual Docker image digest, LP64D readelf, QEMU result and RTL
   log attached. This demonstrates genuine Debian glibc/dash/dpkg under
   Product RV64GC. **This does not qualify complete Debian boot.**
2. **D2**: Extend the same rootfs with real Debian commands, filesystem,
   processes, signals, threading and floating-point work. Do not substitute
   compiler-authored stubs for the distribution binaries.
3. **D3**: Prepare a rootfs including Debian's genuine `/sbin/init` and
   services, boot that init and reach a serial login. The slim OCI image is
   not a full systemd-based installation and does not satisfy D3 by itself.
4. **D4**: Integrate the physical FPGA DDR3/SD controller, pins and timing,
   load real media, and repeat D3 on the board. The current pin-shell and
   256 MiB host RAM simulator are not evidence of physical FPGA execution.

The older V0/V2 CPU cannot be an RV64GC/LP64D golden executor because its
frozen ISA lacks F/D/C. Continue G4 IMAC differential comparisons against V2,
and use the same official Debian ELF under QEMU as the D1 userspace oracle.
