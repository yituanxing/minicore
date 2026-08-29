# L32: RV32 OpenSBI + Linux bring-up

L32 starts from the frozen N5 checkpoint and keeps the software inputs fixed while real OpenSBI/Linux execution drives any additional CPU or platform work.

## Frozen software inputs

- Linux: 6.6.143
- OpenSBI: v1.6 (`bd613dd92113f683052acfb23d9dc8ba60029e0a`)
- ISA baseline inherited from N5: RV32IMA + Zicsr + Zifencei + M/S/U + Sv32 + Sstc

The Linux source version remains fixed through the RV32 `/bin/sh` checkpoint and should also be reused when the later RV64/Sv39 line begins, so L32/L64 behavior can be compared without changing the kernel version.

## Bounded ladder

1. **L32-A — OpenSBI build:** produce and audit a real ELF32 `generic/fw_payload.elf` and `fw_payload.bin` at `0x80000000`, with the frozen single-hart AetherCore FDT embedded and a PIE-capable LLVM/LLD or Linux-target RISC-V toolchain.
2. **L32-B — OpenSBI first execution:** run the unmodified firmware on AetherCore until the OpenSBI v1.6 banner and built-in S-mode test payload, or the first precise architectural/platform failure.
3. **L32-C — Linux image build:** build Linux 6.6.143 RV32 from pinned sources and freeze the resulting kernel image/config/hash.
4. **L32-D — OpenSBI -> Linux handoff:** reach the Linux RISC-V entry point with the real hartid/FDT contract.
5. **L32-E — Linux early boot:** establish early console, DT parsing, memory discovery, SBI services, interrupt/timer plumbing and Sv32 under the real kernel.
6. **L32-F — initramfs/userspace:** enter `/init`, execute U-mode syscalls, mount the minimal root filesystem and reach BusyBox `/bin/sh`.
7. **L32 freeze:** rerun the frozen Fast Gate and L32 workload, record immutable binaries/hashes/evidence, then stop adding RV32 features unless a regression requires repair.

## Failure rule

Do not implement Linux conventions speculatively. For every red run:

`first real failure -> exact PC/instruction/cause/platform access -> focused regression -> minimal repair -> same frozen workload rerun`

L32-A intentionally does not build Linux yet; it isolates OpenSBI/toolchain/FDT reproducibility before firmware execution and kernel bring-up are mixed together.
