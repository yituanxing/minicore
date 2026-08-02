  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

#ifndef MRET_CASE
#error "MRET_CASE must select one machine return workload"
#endif

#define CASE_ECALL_NEXT      1
#define CASE_EBREAK_REWRITE  2
#define CASE_LOAD_FAULT      3
#define CASE_DOUBLE_ECALL    4

main:
  la t0, trap_handler
  csrw mtvec, t0

  li t0, 8
  csrw mstatus, t0
  csrw mscratch, zero

  la s0, sentinel
  sw zero, 0(s0)
  li s1, 0x5a
  li s3, 0

#if MRET_CASE == CASE_ECALL_NEXT
trap_site_0:
  ecall
resume_target:
#elif MRET_CASE == CASE_EBREAK_REWRITE
trap_site_0:
  ebreak
bad_sequential_path:
  sw s1, 0(s0)
  li a0, 20
  j finish
resume_target:
#elif MRET_CASE == CASE_LOAD_FAULT
  li s2, 0x90000000
trap_site_0:
  lw t0, 0(s2)
resume_target:
#elif MRET_CASE == CASE_DOUBLE_ECALL
trap_site_0:
  ecall
resume_first:
  addi s3, s3, 1
trap_site_1:
  ecall
resume_target:
#else
#error "unsupported MRET_CASE"
#endif

  csrr t0, mstatus
  li t1, 0x1888
  bne t0, t1, fail_return_status

  csrr t0, mepc
  la t1, resume_target
  bne t0, t1, fail_return_pc

  lw t0, 0(s0)
  bnez t0, fail_younger_store

#if MRET_CASE == CASE_DOUBLE_ECALL
  li t0, 2
  csrr t1, mscratch
  bne t1, t0, fail_return_count
  li t0, 1
  bne s3, t0, fail_continuation
#else
  li t0, 1
  csrr t1, mscratch
  bne t1, t0, fail_return_count
#endif

  li a0, 0
  j finish

  .align 2
trap_handler:
  csrr t0, mcause
  csrr t1, mepc
  csrr t2, mtval
  csrr t3, mstatus
  csrr t4, mscratch

#if MRET_CASE == CASE_ECALL_NEXT
  li t5, 11
  bne t0, t5, fail_cause
  la t5, trap_site_0
  bne t1, t5, fail_epc
  bnez t2, fail_tval
#elif MRET_CASE == CASE_EBREAK_REWRITE
  li t5, 3
  bne t0, t5, fail_cause
  la t5, trap_site_0
  bne t1, t5, fail_epc
  bne t2, t5, fail_tval
#elif MRET_CASE == CASE_LOAD_FAULT
  li t5, 5
  bne t0, t5, fail_cause
  la t5, trap_site_0
  bne t1, t5, fail_epc
  li t5, 0x90000000
  bne t2, t5, fail_tval
#elif MRET_CASE == CASE_DOUBLE_ECALL
  li t5, 11
  bne t0, t5, fail_cause
  beqz t4, check_first_ecall
  li t5, 1
  bne t4, t5, fail_return_count
  la t5, trap_site_1
  j check_double_epc
check_first_ecall:
  la t5, trap_site_0
check_double_epc:
  bne t1, t5, fail_epc
  bnez t2, fail_tval
#endif

  li t5, 0x1880
  bne t3, t5, fail_trap_status

  addi t4, t4, 1
  csrw mscratch, t4

#if MRET_CASE == CASE_EBREAK_REWRITE
  la t5, resume_target
  csrw mepc, t5
#else
  addi t1, t1, 4
  csrw mepc, t1
#endif

  mret

  // These younger instructions occupy the sequential path behind MRET. The
  // Store must never escape before the return redirect flushes the pipeline.
  sw s1, 0(s0)
  li a0, 21
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
fail_trap_status:
  li a0, 4
  j finish
fail_return_status:
  li a0, 5
  j finish
fail_return_pc:
  li a0, 6
  j finish
fail_younger_store:
  li a0, 7
  j finish
fail_return_count:
  li a0, 8
  j finish
fail_continuation:
  li a0, 9

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
