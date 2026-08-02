  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

main:
  csrw mscratch, zero
  li t0, 0x55

  csrrw t1, mscratch, t0
  bnez t1, fail_1

  csrrs t2, mscratch, zero
  li t3, 0x55
  bne t2, t3, fail_2

  li t3, 2
  csrrs t4, mscratch, t3
  li t5, 0x55
  bne t4, t5, fail_3

  li t3, 1
  csrrc t6, mscratch, t3
  li t5, 0x57
  bne t6, t5, fail_4

  csrrwi a1, mscratch, 3
  li t5, 0x56
  bne a1, t5, fail_5

  csrrsi a2, mscratch, 4
  li t5, 3
  bne a2, t5, fail_6

  csrrci a3, mscratch, 2
  li t5, 7
  bne a3, t5, fail_7

  csrrs a4, mscratch, zero
  li t5, 5
  bne a4, t5, fail_8

  csrrwi zero, mscratch, 0
  csrrsi a5, mscratch, 0
  bnez a5, fail_9

  li t0, 0x80000100
  csrw mtvec, t0
  csrr t1, mtvec
  bne t1, t0, fail_10

  li t0, 0x80000203
  csrw mepc, t0
  csrr t1, mepc
  li t2, 0x80000200
  bne t1, t2, fail_11

  li t0, 0x12345678
  csrw mcause, t0
  csrr t1, mcause
  bne t1, t0, fail_12

  li t0, 0x89abcdef
  csrw mtval, t0
  csrr t1, mtval
  bne t1, t0, fail_13

  li a0, 0
  ret

fail_1:  li a0, 1;  ret
fail_2:  li a0, 2;  ret
fail_3:  li a0, 3;  ret
fail_4:  li a0, 4;  ret
fail_5:  li a0, 5;  ret
fail_6:  li a0, 6;  ret
fail_7:  li a0, 7;  ret
fail_8:  li a0, 8;  ret
fail_9:  li a0, 9;  ret
fail_10: li a0, 10; ret
fail_11: li a0, 11; ret
fail_12: li a0, 12; ret
fail_13: li a0, 13; ret

  .size main, .-main
  .option pop
