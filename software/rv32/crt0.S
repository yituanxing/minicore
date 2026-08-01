  .section .text.init,"ax",@progbits
  .globl _start
  .type _start, @function
  .option push
  .option norvc

_start:
  la sp, __stack_top

  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b

2:
  call main

  li t0, 0x10000008
  sw a0, 0(t0)
  ebreak

3:
  j 3b

  .size _start, .-_start
  .option pop
