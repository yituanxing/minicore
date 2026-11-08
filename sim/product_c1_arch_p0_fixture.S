/* Isolated C1 P0 S-mode fixture, never a signed SD1 / Linux workload.
 * The restore shim enters here at 0x80100000 with SATP=0, PMP16 entry 0
 * granting RWX to [0x80000000, 0x90000000), and no pending interrupts.
 * No UART, CLINT, PLIC, storage or other MMIO is accessed.
 *
 * x5/x6 are the last GPRs restored by the M-mode shim; x10 points to the
 * fixture-owned DDR marker area, x11 is a recognizable source payload,
 * and x12 starts at 41. These stores let the host inspect guest execution.
 */
    .option push
    .option norvc
    .option norelax

    .section .text.c1_p0_target,"ax",@progbits
    .balign 4
    .globl aether_c1_p0_target
    .type aether_c1_p0_target,@function
aether_c1_p0_target:
    sd x5, 0(x10)
    sd x6, 8(x10)
    sd x11, 16(x10)
    addi x12, x12, 1
    sd x12, 24(x10)
    /* A machine-only CSR read must trap as illegal in real S-mode.
     * It is legal in M-mode, so a false MRET privilege transition cannot
     * pass the independent P0.1 proof in the RTL retirement stream. */
    csrr x13, mstatus
.Lidle:
    wfi
    j .Lidle
    .size aether_c1_p0_target, .-aether_c1_p0_target

    .section .data.c1_p0_markers,"aw",@progbits
    .balign 8
    .globl aether_c1_p0_markers
aether_c1_p0_markers:
    .zero 32

    .option pop
