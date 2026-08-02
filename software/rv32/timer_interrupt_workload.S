  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

#ifndef TIMER_CASE
#error "TIMER_CASE must select one machine timer interrupt workload"
#endif

#define CASE_SINGLE        1
#define CASE_MASKED_ENABLE 2
#define CASE_REPEAT        3
#define CASE_MEMORY_LOOP   4

#define MTIMECMP 0x02004000

main:
  la t0, timer_handler
  csrw mtvec, t0
  csrw mscratch, zero

  la s4, sentinel
  sw zero, 0(s4)
  la s5, progress
  sw zero, 0(s5)
  li s0, 0
  li s1, 0
  li s2, 0
  li s3, 0

#if TIMER_CASE == CASE_MASKED_ENABLE
  li a0, 1
#else
  li a0, 200
#endif
  li a1, 0
  call program_timer

#if TIMER_CASE == CASE_MASKED_ENABLE
  li s0, 64
mask_global_loop:
  addi s1, s1, 1
  addi s0, s0, -1
  bnez s0, mask_global_loop
  csrr t0, mscratch
  bnez t0, fail_early_interrupt

  li t0, 0x80
  csrw mie, t0
  li s0, 64
mask_local_loop:
  addi s1, s1, 1
  addi s0, s0, -1
  bnez s0, mask_local_loop
  csrr t0, mscratch
  bnez t0, fail_early_interrupt

  li t0, 8
  csrw mstatus, t0
wait_masked_release:
  addi s1, s1, 1
  csrr t0, mscratch
  beqz t0, wait_masked_release

#elif TIMER_CASE == CASE_MEMORY_LOOP
  li t0, 0x80
  csrw mie, t0
  li t0, 8
  csrw mstatus, t0

  la s6, buffer
  li s0, 0
  li s1, 96
memory_loop:
  sw s0, 0(s6)
  lw t0, 0(s6)
  bne t0, s0, fail_memory
  add s2, s2, t0
  addi s0, s0, 1
  addi s6, s6, 4
  addi s1, s1, -1
  bnez s1, memory_loop

  csrr t0, mscratch
  li t1, 1
  bne t0, t1, fail_count
  li t0, 4560
  bne s2, t0, fail_memory

#else
  li t0, 0x80
  csrw mie, t0
  li t0, 8
  csrw mstatus, t0

wait_interrupts:
  addi s0, s0, 1
  sw s0, 0(s5)
  lw t0, 0(s5)
  bne t0, s0, fail_memory
  csrr t1, mscratch
#if TIMER_CASE == CASE_REPEAT
  li t2, 2
#else
  li t2, 1
#endif
  blt t1, t2, wait_interrupts
#endif

  csrr t0, mstatus
  li t1, 0x1888
  bne t0, t1, fail_return_status

  csrr t0, mcause
  li t1, 0x80000007
  bne t0, t1, fail_cause

  csrr t0, mtval
  bnez t0, fail_tval

  lw t0, 0(s4)
  bnez t0, fail_younger_store

#if TIMER_CASE == CASE_REPEAT
  csrr t0, mscratch
  li t1, 2
  bne t0, t1, fail_count
#else
  csrr t0, mscratch
  li t1, 1
  bne t0, t1, fail_count
#endif

  li a0, 0
  j finish

  .align 2
timer_handler:
  csrr t0, mcause
  li t1, 0x80000007
  bne t0, t1, handler_fail_cause

  csrr t2, mtval
  bnez t2, handler_fail_tval

  csrr t3, mstatus
  li t4, 0x1880
  bne t3, t4, handler_fail_status

  csrr t5, mepc
  andi t6, t5, 3
  bnez t6, handler_fail_epc

  csrr t0, mscratch
  addi t0, t0, 1
  csrw mscratch, t0

#if TIMER_CASE == CASE_REPEAT
  li t1, 1
  bne t0, t1, disable_timer
  li a0, 600
  li a1, 0
  call program_timer
  j return_from_timer
#endif

disable_timer:
  call stop_timer

return_from_timer:
  mret

  // Sequential instructions younger than MRET must be flushed. In particular,
  // this Store must never reach the timer-interrupted program's sentinel.
  li t0, 0x5a
  sw t0, 0(s4)
  li a0, 20
  j finish

handler_fail_cause:
  li a0, 10
  j finish
handler_fail_tval:
  li a0, 11
  j finish
handler_fail_status:
  li a0, 12
  j finish
handler_fail_epc:
  li a0, 13
  j finish

program_timer:
  li t0, MTIMECMP
  li t1, -1
  sw t1, 0(t0)
  sw a1, 4(t0)
  sw a0, 0(t0)
  ret

stop_timer:
  li t0, MTIMECMP
  li t1, -1
  sw t1, 0(t0)
  sw t1, 4(t0)
  ret

fail_early_interrupt:
  li a0, 1
  j finish
fail_count:
  li a0, 2
  j finish
fail_return_status:
  li a0, 3
  j finish
fail_cause:
  li a0, 4
  j finish
fail_tval:
  li a0, 5
  j finish
fail_younger_store:
  li a0, 6
  j finish
fail_memory:
  li a0, 7

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
progress:
  .word 0
buffer:
  .space 384
