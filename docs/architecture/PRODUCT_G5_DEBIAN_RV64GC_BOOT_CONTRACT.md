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
- G5 is a **manual-only** long-running CI gate. Source/architecture changes
  must not automatically consume another billion-cycle run. Cache keys and
  simulation acceleration are separately reviewed before any explicitly
  requested execution; its existence does not replace G1/G2/G3/G4/FPGA PPA.
- G5 requires compressed instructions to be retired in OpenSBI and Linux.
  The shared runner uses the actual RV64 handoff `0x80200000` only for G5;
  the frozen RV32 `0x80400000` classification remains unchanged.

## Milestones and what may be claimed

1. **D1**: G5 sees Linux 6.6.143 and its own Debian dynamic PID1 marker,
   with the actual Docker image digest, LP64D readelf, QEMU result and RTL
   log attached. This demonstrates genuine Debian glibc/dash/dpkg under
   Product RV64GC. **This does not qualify complete Debian boot.**
2. **D2**: Select `RV64_DEBIAN_STAGE=d2`. Keep official Debian shell, dpkg,
   mount/coreutils; the rootfs builder **fails before simulation** if required
   real Debian commands are missing. Its `/init` mounts proc/sysfs/tmpfs,
   checks real distro executables, fork/exec/wait and signal delivery. It also
   runs `aethercore-d2-native-probe`, an auxiliary RV64GC/LP64D *glibc*
   program compiled using the already pinned GCC13.3 toolchain, which
   exercises IEEE F/D/fflags, POSIX threads, atomic RMW, Linux futex,
   mmap/mprotect, and signal handling. The supplementary program is explicitly
   **not** a substitute for any unmodified official Debian ELF.
3. **D3**: Select `RV64_DEBIAN_STAGE=d3` with
   `RV64_DEBIAN_FULL_ROOTFS_ARCHIVE` and its pinned
   `RV64_DEBIAN_FULL_ROOTFS_SHA256`. Prepare a rootfs including Debian's
   genuine `/sbin/init` and
   services, boot that init and reach a serial login. The slim OCI image is
   not a full systemd-based installation and does not satisfy D3 by itself.
   The builder verifies native RISC-V LP64D ELF/loader, real getty/login and
   a systemd serial-getty unit (or real SysV inittab), and configures ttyS0;
   then the initramfs `/init` **execs the actual Debian `/sbin/init`** as PID1.
   Archive SHA alone does not establish official Debian package provenance:
   the archive's manifest, origin and package list must also be supplied.
   External archive extraction is preceded by digest verification and a full
   member/link traversal check; **normal guest-absolute** Debian links
   (including `/etc/alternatives` paths) are safely rebased into equivalent
   rootfs-relative symlinks in a derived tar with a separate SHA256, while
   escaping member paths, link cycles and out-of-root links are rejected
   before root-owned unpacking. No foreign ELF executes during this step.
   The kernel stage separately asserts the actual
   upstream drivers needed for Product's planned storage transport:
   `CONFIG_SPI_OC_TINY`, `CONFIG_GPIO_ALTERA`, `CONFIG_MMC_SPI`, MMC block,
   ext4 and devtmpfs. D3 storage driver *configuration* is not evidence that
   the current board has a working SD card or that userspace is persistent.
   In fact the existing 256 MiB simulated RAM and fixed `0x87f00000` FDT
   placement do not have unlimited space for an embedded full Debian tree.
   D3 rootfs staging records a conservative 96 MiB embedded-root allowance
   (64 MiB reserved for kernel, 64 MiB for services, 32 MiB for boot staging)
   and **fails before Kbuild** if exceeded; the shared payload builder also
   checks the *actual* `fw_payload.bin` end against the FDT address. A larger
   official Debian installation therefore requires a separately engineered
   expanded RAM/DTB map or an SD-backed block-root pivot; neither can be
   claimed from a simulated initramfs serial-login prompt alone.
4. **D4**: Integrate the physical FPGA DDR3/SD controller, pins and timing,
   load real media, and repeat D3 on the board. The current pin-shell and
   256 MiB host RAM simulator are not evidence of physical FPGA execution.

The older V0/V2 CPU cannot be an RV64GC/LP64D golden executor because its
frozen ISA lacks F/D/C. Continue G4 IMAC differential comparisons against V2,
and use the same official Debian ELF under QEMU as the D1 userspace oracle.

## Implementation versus verified milestones

`ProductCoreConfig` now distinguishes `rtlElaboratable` (the implemented
**single-lane** Chisel core, both Small/Expanded capacity presets) from
`qualifiedDebianD1/D2/D3` and `qualifiedFpgaDistributionD4` (all initially
false pending actual independently recorded runs). Medium/Large multi-lane
profiles are proposals, not runnable RTL merely because a software profile
requests RV64GC. The old `rtlImplemented=false` field is a deprecated strict
qualification gate for historical callers; it does **not** say that the
current Product SmallOoO hardware does not exist. No milestone is claimed
here from merely preparing the above source and Kconfig paths.
