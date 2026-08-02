  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

#ifndef TIMER_CASE
#error "TIMER_CASE must select one machine timer workload"
#endif

#define CASE_BASIC        1
#define CASE_GLOBAL_MASK  2
#define CASE_SOURCE_MASK  3
#define CASE_DOUBLE       4

#define MTIMECMP 0x02004000

main:
  la t0, trap_handler
  csrw mtvec, t0
  csrw mscratch, zero
  li s1, 0

#if TIMER_CASE == CASE_BASIC
  li a1, 240
#elif TIMER_CASE == CASE_GLOBAL_MASK
  li a1, 120
#elif TIMER_CASE == CASE_SOURCE_MASK
  li a1, 120
#elif TIMER_CASE == CASE_DOUBLE
  li a1, 220
#else
#error "unsupported TIMER_CASE"
#endif
  call program_timer

#if TIMER_CASE == CASE_BASIC || TIMER_CASE == CASE_DOUBLE
  li t0, 0x80
  csrw mie, t0
  li t0, 8
interrupt_window_start:
  csrw mstatus, t0
#elif TIMER_CASE == CASE_GLOBAL_MASK
  li t0, 0x80
  csrw mie, t0
  li t1, 128
masked_delay:
  addi t1, t1, -1
  bnez t1, masked_delay
  csrr t2, mscratch
  bnez t2, fail_mask
  li t0, 8
interrupt_window_start:
  csrw mstatus, t0
#elif TIMER_CASE == CASE_SOURCE_MASK
  li t0, 8
  csrw mstatus, t0
  li t1, 128
masked_delay:
  addi t1, t1, -1
  bnez t1, masked_delay
  csrr t2, mscratch
  bnez t2, fail_mask
  li t0, 0x80
interrupt_window_start:
  csrw mie, t0
#endif

wait_for_interrupt:
#if TIMER_CASE == CASE_DOUBLE
  li t0, 2
  bne s1, t0, wait_for_interrupt
#else
  beqz s1, wait_for_interrupt
#endif
interrupt_window_end:

  csrr t0, mscratch
#if TIMER_CASE == CASE_DOUBLE
  li t1, 2
#else
  li t1, 1
#endif
  bne t0, t1, fail_count

  csrr t0, mstatus
  li t1, 0x1888
  bne t0, t1, fail_return_status

  csrr t0, mie
  li t1, 0x80
  bne t0, t1, fail_mie

  csrr t0, mip
  andi t0, t0, 0x80
  bnez t0, fail_pending

  csrr t0, mepc
  la t1, interrupt_window_start
  bltu t0, t1, fail_epc
  la t1, interrupt_window_end
  bgtu t0, t1, fail_epc

  li a0, 0
  j finish

  .align 2
program_timer:
  li t0, MTIMECMP
  li t1, -1
  sw t1, 4(t0)
  sw a1, 0(t0)
  sw zero, 4(t0)
  ret

  .align 2
trap_handler:
  csrr t0, mcause
  li t1, 0x80000007
  bne t0, t1, fail_cause

  csrr t0, mstatus
  li t1, 0x1880
  bne t0, t1, fail_trap_status

  csrr t0, mip
  andi t0, t0, 0x80
  beqz t0, fail_missing_pending

  csrr t0, mie
  andi t0, t0, 0x80
  beqz t0, fail_missing_enable

  csrr t0, mepc
  la t1, interrupt_window_start
  bltu t0, t1, fail_epc
  la t1, interrupt_window_end
  bgtu t0, t1, fail_epc

  csrr t2, mscratch
  addi t2, t2, 1
  csrw mscratch, t2
  mv s1, t2

#if TIMER_CASE == CASE_DOUBLE
  li t3, 1
  bne t2, t3, disable_timer
  li a1, 520
  call program_timer
  j return_from_interrupt
#endif

disable_timer:
  li t0, MTIMECMP
  li t1, -1
  sw t1, 4(t0)
  sw t1, 0(t0)

return_from_interrupt:
  mret

  // The sequential path behind MRET must be flushed.
  li a0, 15
  j finish

fail_cause:
  li a0, 1
  j finish
fail_trap_status:
  li a0, 2
  j finish
fail_missing_pending:
  li a0, 3
  j finish
fail_missing_enable:
  li a0, 4
  j finish
fail_epc:
  li a0, 5
  j finish
fail_mask:
  li a0, 6
  j finish
fail_count:
  li a0, 7
  j finish
fail_return_status:
  li a0, 8
  j finish
fail_mie:
  li a0, 9
  j finish
fail_pending:
  li a0, 10

finish:
  li t0, 0x10000008
  sw a0, 0(t0)
1:
  j 1b

  .size main, .-main
  .option pop
