.section .text.start, "ax", @progbits
.align 2
.globl _start
.type _start, @function
_start:
    # OpenSBI enters the next stage in S-mode with a0=hartid and a1=FDT.
    mv      s0, a0
    mv      s1, a1
    bnez    s0, payload_fail
    li      t0, 0x87f00000
    bne     s1, t0, payload_fail

    # Exercise a real S->M SBI round-trip. SBI_EXT_BASE / GET_SPEC_VERSION is
    # mandatory in SBI v0.2+; a0 must return SBI_SUCCESS and a1 a non-zero
    # encoded specification version.
    li      a7, 0x10
    li      a6, 0
    ecall
    bnez    a0, payload_fail
    beqz    a1, payload_fail

    la      a0, pass_message
    j       uart_print

payload_fail:
    la      a0, fail_message

uart_print:
    li      t0, 0x10000000
1:
    lbu     t1, 0(a0)
    beqz    t1, payload_halt
2:
    lbu     t2, 5(t0)
    andi    t2, t2, 0x20
    beqz    t2, 2b
    sb      t1, 0(t0)
    addi    a0, a0, 1
    j       1b

payload_halt:
3:
    wfi
    j       3b
.size _start, . - _start

.section .rodata, "a", @progbits
.align 3
pass_message:
    .asciz "RV64 OpenSBI S-mode payload PASS\n"
fail_message:
    .asciz "RV64 OpenSBI S-mode payload FAIL\n"
