.section .text
.option norvc
.align 2
.globl _start
.type _start, @function
_start:
    li      s0, 0x80010000
    li      s1, 0x13579bdf
    li      s2, 0
    sd      s1, 0(s0)

1:
    addi    s2, s2, 1
    andi    t0, s2, 63
    slli    t0, t0, 3
    add     t1, s0, t0

    ld      t2, 0(t1)
    add     t2, t2, s2
    xor     t2, t2, s1
    sd      t2, 0(t1)
    ld      t3, 0(t1)

    xor     s1, s1, t3
    slli    t4, t3, 1
    add     t5, t4, s2
    xor     t6, t5, s1
    beqz    t6, 2f
    addi    s1, s1, 3
2:
    j       1b
.size _start, .-_start
