# L32-D: Linux 6.6.143 first real execution

L32-D starts from the frozen L32-C kernel image and changes the workload, not the CPU architecture speculatively.

## Frozen inputs

- Linux 6.6.143 `Image` SHA256 `5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048`
- OpenSBI v1.6 commit `bd613dd92113f683052acfb23d9dc8ba60029e0a`
- RV32IMA + Zicsr + Zifencei, ILP32
- 256 MiB RAM at `0x80000000`
- NS16550A at `0x10000000`
- single hart, Sv32, Sstc

## Handoff contract

The OpenSBI FW_PAYLOAD image embeds the exact frozen Linux `Image` at `0x80400000`, which is a 4 MiB boundary required by RV32 Linux. OpenSBI remains at `0x80000000` and hands off in S-mode.

The Linux-specific FDT is generated from the same frozen platform description but adds only serial boot arguments for observability:

`earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200`

The FDT is placed at `0x87f00000` before the payload handoff so Linux receives a concrete `a1` pointer outside the resident OpenSBI region and outside the kernel image.

## First acceptance boundary

The first runtime milestone is intentionally narrow:

- OpenSBI must still print its v1.6 banner;
- OpenSBI must hand off to the exact Linux 6.6.143 `Image`;
- the AetherCore simulator must observe `Linux version 6.6.143` on the NS16550 console.

A failure before that marker is evidence for the next CPU/platform fix. L32-D does not yet claim scheduler, interrupts, rootfs, userspace, or `/bin/sh`.
