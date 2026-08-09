# L32-C: Linux 6.6.143 RV32 build

This checkpoint starts from the frozen RV32 OpenSBI S-mode milestone and keeps the kernel source immutable while Linux itself becomes the next workload.

## Frozen inputs

- Linux 6.6.143 from kernel.org
- SHA256 `dace1f8dc9c0dbf5df14f47e3229cd62c298e83049681731ef229f2ba7592932`
- upstream `rv32_defconfig`
- pinned Bootlin RV32 Linux GCC already used by the L32 OpenSBI line

## Bounded ISA overlay

The first kernel image must stay within the existing AetherCore baseline. Start from upstream `rv32_defconfig`, then disable compressed instructions and FPU support. Do not add CPU features merely to satisfy the compiler.

## Acceptance

L32-C is complete only when CI produces and audits both `vmlinux` and `arch/riscv/boot/Image` as ELF32/RV32-compatible outputs, records the resolved config and immutable hashes, and marks the persistent software-build cache.

After this checkpoint, the same cached Image becomes the workload for the OpenSBI-to-Linux handoff and early-boot stages.
