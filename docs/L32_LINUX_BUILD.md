# L32-C: Linux 6.6.143 RV32 build

This checkpoint starts from the frozen RV32 OpenSBI S-mode milestone and keeps the kernel source immutable while Linux itself becomes the next workload.

## Frozen inputs

- Linux 6.6.143 from kernel.org
- SHA256 `dace1f8dc9c0dbf5df14f47e3229cd62c298e83049681731ef229f2ba7592932`
- upstream `rv32_defconfig`
- pinned Bootlin RV32 Linux GCC already used by the L32 OpenSBI line

## Bounded ISA and platform overlay

The first kernel image must stay within the existing AetherCore baseline. Start from upstream `rv32_defconfig`, then disable UEFI runtime support, compressed instructions, FPU support, and the generic VGA text console.

Linux 6.6 RISC-V `CONFIG_EFI` selects `CONFIG_RISCV_ISA_C`, so disabling `RISCV_ISA_C` alone is not stable across `olddefconfig`. L32 already boots through OpenSBI with a direct FDT contract and does not need UEFI for this checkpoint; therefore the bounded overlay intentionally disables `EFI` before disabling `RISCV_ISA_C`. Do not add the C extension to the CPU merely to satisfy an unused default UEFI configuration.

The AetherCore L32 platform exposes an NS16550 serial console and no VGA device. The first real link reached `vmlinux` and failed only because the default `VGA_CONSOLE` pulled in `vgacon.o`, whose architecture contract requires `screen_info`. The L32 overlay therefore disables `VGA_CONSOLE` instead of adding a fake PC VGA platform object.

The Kbuild object directory is retained between bounded configuration fixes when the frozen Linux source SHA and toolchain identity are unchanged. Kbuild remains responsible for rebuilding objects affected by `.config` and generated-header changes; a source/toolchain change invalidates the object tree entirely.

## Acceptance

L32-C is complete only when CI produces and audits both `vmlinux` and `arch/riscv/boot/Image` as ELF32/RV32-compatible outputs, records the resolved config and immutable hashes, and marks the persistent software-build cache.

After this checkpoint, the same cached Image becomes the workload for the OpenSBI-to-Linux handoff and early-boot stages.
