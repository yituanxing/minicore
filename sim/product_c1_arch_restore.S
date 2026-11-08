/* Experimental Product C1 architecture replay stub, RV64IMAC, NOT SD1.
 *
 * The Host must enter here in M-mode with an independently reserved,
 * coherent backing page for this code and aether_c1_arch_state.  The host
 * must prove that the guest's original DRAM is not overwritten and that
 * any fetch overlay is removed before the final FENCE.I.  This file does
 * not implement a QEMU-to-RTL transport or qualify an RTL checkpoint.
 *
 * P0 restrictions: one hart, target S-mode, FS=Off, no in-flight MMIO,
 * no IRQ pending, no LR/SC reservation, no PMP policy to reconstruct,
 * no asynchronous device activity.  The mret transition changes mstatus,
 * mepc and other machine trap state: exact architectural equivalence
 * requires a separate WARL-aware qualification, not this stub alone.
 *
 * The Host fills the little-endian 408-byte state table at the symbol below.
 * Offsets are synchronized with tools/ci/product_c1_arch_relay/restore_abi.py.
 */

    .option push
    .option norvc
    .option norelax

    .equ C1_PC,          0
    .equ C1_MSTATUS,     8
    .equ C1_MEDELEG,    16
    .equ C1_MIDELEG,    24
    .equ C1_MIE,        32
    .equ C1_MTVEC,      40
    .equ C1_MSCRATCH,   48
    .equ C1_MEPC,       56
    .equ C1_MCAUSE,     64
    .equ C1_MTVAL,      72
    .equ C1_STVEC,      80
    .equ C1_SSCRATCH,   88
    .equ C1_SEPC,       96
    .equ C1_SCAUSE,    104
    .equ C1_STVAL,     112
    .equ C1_SATP,      120
    .equ C1_SCOUNTEREN,128
    .equ C1_MCOUNTEREN,136
    .equ C1_MIP,       144
    .equ C1_GPR,       152

    .section .text.c1_arch_restore,"ax",@progbits
    .balign 4
    .globl aether_c1_arch_restore
    .type aether_c1_arch_restore,@function
aether_c1_arch_restore:
    /* No guest stack, C runtime or RAM side effects. */
1:  auipc x5, %pcrel_hi(aether_c1_arch_state)
    addi  x5, x5, %pcrel_lo(1b)
    csrw  mie, zero

    ld x6, C1_MEDELEG(x5)
    csrw medeleg, x6
    ld x6, C1_MIDELEG(x5)
    csrw mideleg, x6
    ld x6, C1_MTVEC(x5)
    csrw mtvec, x6
    ld x6, C1_MSCRATCH(x5)
    csrw mscratch, x6
    ld x6, C1_MCAUSE(x5)
    csrw mcause, x6
    ld x6, C1_MTVAL(x5)
    csrw mtval, x6
    ld x6, C1_STVEC(x5)
    csrw stvec, x6
    ld x6, C1_SSCRATCH(x5)
    csrw sscratch, x6
    ld x6, C1_SEPC(x5)
    csrw sepc, x6
    ld x6, C1_SCAUSE(x5)
    csrw scause, x6
    ld x6, C1_STVAL(x5)
    csrw stval, x6
    ld x6, C1_SCOUNTEREN(x5)
    csrw scounteren, x6
    ld x6, C1_MCOUNTEREN(x5)
    csrw mcounteren, x6
    ld x6, C1_SATP(x5)
    csrw satp, x6
    sfence.vma zero, zero

    /* mret consumes mepc and mstatus.MPP. Machine mepc itself cannot
     * be preserved bit-for-bit by this instruction-based resume path. */
    ld x6, C1_PC(x5)
    csrw mepc, x6
    ld x6, C1_MIE(x5)
    csrw mie, x6
    ld x6, C1_MSTATUS(x5)
    /* Source is S-mode with MPP=U.  Transition through MRET requires
     * temporarily setting MPP=S; MRET clears MPP back to U. */
    li x7, -6145
    and x6, x6, x7
    li x7, 2048
    or x6, x6, x7
    csrw mstatus, x6

    /* Restore architectural GPRs. Keep x5=table and x6=scratch until last. */
    ld x1,  C1_GPR+8(x5)
    ld x2,  C1_GPR+16(x5)
    ld x3,  C1_GPR+24(x5)
    ld x4,  C1_GPR+32(x5)
    ld x7,  C1_GPR+56(x5)
    ld x8,  C1_GPR+64(x5)
    ld x9,  C1_GPR+72(x5)
    ld x10, C1_GPR+80(x5)
    ld x11, C1_GPR+88(x5)
    ld x12, C1_GPR+96(x5)
    ld x13, C1_GPR+104(x5)
    ld x14, C1_GPR+112(x5)
    ld x15, C1_GPR+120(x5)
    ld x16, C1_GPR+128(x5)
    ld x17, C1_GPR+136(x5)
    ld x18, C1_GPR+144(x5)
    ld x19, C1_GPR+152(x5)
    ld x20, C1_GPR+160(x5)
    ld x21, C1_GPR+168(x5)
    ld x22, C1_GPR+176(x5)
    ld x23, C1_GPR+184(x5)
    ld x24, C1_GPR+192(x5)
    ld x25, C1_GPR+200(x5)
    ld x26, C1_GPR+208(x5)
    ld x27, C1_GPR+216(x5)
    ld x28, C1_GPR+224(x5)
    ld x29, C1_GPR+232(x5)
    ld x30, C1_GPR+240(x5)
    ld x31, C1_GPR+248(x5)

    /* On real Product I-cache, this barrier is qualified only after the
     * Host proves its temporary boot-fetch overlay has been deactivated. */
    fence.i
    ld x6, C1_GPR+48(x5)
    ld x5, C1_GPR+40(x5)
    mret
    .size aether_c1_arch_restore, .-aether_c1_arch_restore

    .section .data.c1_arch_state,"aw",@progbits
    .balign 8
    .globl aether_c1_arch_state
aether_c1_arch_state:
    .zero 408

    .option pop
