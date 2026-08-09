# L32-D: first real Linux 6.6.143 boot

L32-D starts from the frozen OpenSBI S-mode milestone and the frozen Linux 6.6.143 RV32 Image. It is the first checkpoint where the Linux kernel itself drives AetherCore.

## Frozen workload

- OpenSBI v1.6 at the already frozen L32 commit
- Linux 6.6.143 Image SHA256 `5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048`
- Image size `30147828` bytes
- physical payload entry `0x80400000`
- kernel virtual entry `0xc0000000`
- the same 256 MiB RAM, NS16550 console and ACLINT MTIMER FDT used by the frozen OpenSBI checkpoint

The firmware preparation step rejects any different `Image`, `vmlinux`, resolved config, image size, or OpenSBI payload placement before simulation starts.

## First-runtime policy

Do not add Linux-oriented hardware speculatively. The first run keeps the existing platform exactly as it was for OpenSBI: no PLIC, no MSIP, no extra bootargs, and no new CPU feature.

The probe records whether execution reaches the Linux physical entry, whether it later executes a high virtual kernel PC, the first post-Linux exception, repeated exception-event livelock, UART output, timer state, and recent retirement PCs.

## Acceptance

The first L32-D milestone passes only when all of the following are true:

1. the frozen OpenSBI banner is observed;
2. the CPU retires at physical address `0x80400000`;
3. UART contains `Linux version 6.6.143`.

A failure after the physical entry is treated as a Linux-driven architectural/platform requirement and is fixed only from the concrete PC/instruction/cause/address evidence. Later milestones will continue from this point toward timer/interrupt scheduling, initramfs and finally `/bin/sh`.
