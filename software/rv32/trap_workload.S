  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

#ifndef TRAP_CASE
#error "TRAP_CASE must select one synchronous exception workload"
#endif

#define CASE_ECALL       1
#define CASE_EBREAK      2
#define CASE_ILLEGAL     3
#define CASE_LOAD_FAULT  4
#define CASE_STORE_FAULT 5
#define CASE_FETCH_FAULT 6

main:
  la t0, trap_handler
  csrw mtvec, t0

  li t0, 8
  csrw mstatus, t0

  la t0, sentinel
  sw zero, 0(t0)
  li s1, 0x5a

#if TRAP_CASE == CASE_ECALL
trap_site:
  ecall
  sw s1, 0(t0)
#elif TRAP_CASE == CASE_EBREAK
trap_site:
  ebreak
  sw s1, 0(t0)
#elif TRAP_CASE == CASE_ILLEGAL
trap_site:
  .word 0xffffffff
  sw s1, 0(t0)
#elif TRAP_CASE == CASE_LOAD_FAULT
  li t1, 0x90000000
trap_site:
  lw t2, 0(t1)
  sw s1, 0(t0)
#elif TRAP_CASE == CASE_STORE_FAULT
  li t1, 0x90000000
trap_site:
  sw s1, 0(t1)
  sw s1, 0(t0)
#elif TRAP_CASE == CASE_FETCH_FAULT
  li t1, 0x90000000
trap_site:
  jr t1
  sw s1, 0(t0)
#else
#error "unsupported TRAP_CASE"
#endif

  li a0, 31
  j finish

  .align 2
trap_handler:
  csrr t0, mcause
  csrr t1, mepc
  csrr t2, mtval
  csrr t3, mstatus

#if TRAP_CASE == CASE_ECALL
  li t4, 11
  la t5, trap_site
  li t6, 0
#elif TRAP_CASE == CASE_EBREAK
  li t4, 3
  la t5, trap_site
  la t6, trap_site
#elif TRAP_CASE == CASE_ILLEGAL
  li t4, 2
  la t5, trap_site
  li t6, -1
#elif TRAP_CASE == CASE_LOAD_FAULT
  li t4, 5
  la t5, trap_site
  li t6, 0x90000000
#elif TRAP_CASE == CASE_STORE_FAULT
  li t4, 7
  la t5, trap_site
  li t6, 0x90000000
#elif TRAP_CASE == CASE_FETCH_FAULT
  li t4, 1
  li t5, 0x90000000
  li t6, 0x90000000
#endif

  bne t0, t4, fail_cause
  bne t1, t5, fail_epc
  bne t2, t6, fail_tval

  li t4, 0x1880
  bne t3, t4, fail_status

  la t4, sentinel
  lw t5, 0(t4)
  bnez t5, fail_younger_store

  li a0, 0
  j finish

fail_cause:
  li a0, 1
  j finish
fail_epc:
  li a0, 2
  j finish
fail_tval:
  li a0, 3
  j finish
fail_status:
  li a0, 4
  j finish
fail_younger_store:
  li a0, 5

finish:
  li t0, 0x10000008
  sw a0, 0(t0)
1:
  j 1b

  .size main, .-main
  .option pop

  .section .data,"aw",@progbits
  .align 2
sentinel:
  .word 0
