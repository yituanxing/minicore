  .section .text.machine,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

  .equ EXIT_MMIO, 0x10000008
  .equ PMPCFG0, 0x3a0
  .equ PMPADDR0, 0x3b0
  .equ PMPADDR1, 0x3b1
  .equ PMPADDR2, 0x3b2
  .equ MSTATUS,  0x300
  .equ MTVEC,    0x305
  .equ MSCRATCH, 0x340
  .equ MEPC,     0x341
  .equ MCAUSE,   0x342
  .equ MTVAL,    0x343

  .equ CAUSE_INST_ACCESS,  1
  .equ CAUSE_LOAD_ACCESS,  5
  .equ CAUSE_STORE_ACCESS, 7
  .equ CAUSE_ECALL_U,      8

  // entry0=[0,user_text) ---, entry1=user text R-X, entry2=user data RW-.
  // The low three pmpcfg bytes have the same architectural placement in
  // RV32 pmpcfg0 and RV64 pmpcfg0, so one workload configures both XLENs.
  .equ PMPCFG0_VALUE, 0x000b0d08
#if __riscv_xlen == 64
  // RV64 additionally proves that two legal low PA targets do not become
  // aliases when architectural address bit 56 is set above the PA56 domain.
  .equ EXPECTED_FAULTS, 6
#else
  .equ EXPECTED_FAULTS, 4
#endif

main:
  la t0, machine_trap
  csrw MTVEC, t0

  la t0, user_entry
  srli t0, t0, 2
  csrw PMPADDR0, t0

  la t0, user_data_begin
  srli t0, t0, 2
  csrw PMPADDR1, t0

  la t0, user_data_end
  srli t0, t0, 2
  csrw PMPADDR2, t0

  li t0, PMPCFG0_VALUE
  csrw PMPCFG0, t0
  csrw MSCRATCH, zero

  la t0, user_entry
  csrw MEPC, t0
  // MPP=U. RV64 SXL/UXL remain fixed WARL state underneath this write.
  csrw MSTATUS, zero
  mret

  li a0, 90
  j machine_exit

  .align 2
machine_trap:
  csrr t0, MCAUSE
  csrr t1, MSCRATCH
  csrr t2, MTVAL

  li t3, 0
  beq t1, t3, trap_kernel_load
  li t3, 1
  beq t1, t3, trap_kernel_store
  li t3, 2
  beq t1, t3, trap_text_store
  li t3, 3
  beq t1, t3, trap_kernel_execute
#if __riscv_xlen == 64
  li t3, 4
  beq t1, t3, trap_pa56_alias_load
  li t3, 5
  beq t1, t3, trap_pa56_alias_execute
#endif
  li t3, EXPECTED_FAULTS
  beq t1, t3, trap_final_ecall
  li a0, 91
  j machine_exit

trap_kernel_load:
  li t3, CAUSE_LOAD_ACCESS
  bne t0, t3, fail_cause
  la t3, main
  bne t2, t3, fail_tval
  li t1, 1
  csrw MSCRATCH, t1
  la t3, after_kernel_load
  csrw MEPC, t3
  mret

trap_kernel_store:
  li t3, CAUSE_STORE_ACCESS
  bne t0, t3, fail_cause
  la t3, main
  bne t2, t3, fail_tval
  li t1, 2
  csrw MSCRATCH, t1
  la t3, after_kernel_store
  csrw MEPC, t3
  mret

trap_text_store:
  li t3, CAUSE_STORE_ACCESS
  bne t0, t3, fail_cause
  la t3, user_entry
  bne t2, t3, fail_tval
  li t1, 3
  csrw MSCRATCH, t1
  la t3, after_text_store
  csrw MEPC, t3
  mret

trap_kernel_execute:
  li t3, CAUSE_INST_ACCESS
  bne t0, t3, fail_cause
  la t3, forbidden_kernel_target
  bne t2, t3, fail_tval
  li t1, 4
  csrw MSCRATCH, t1
  la t3, after_kernel_execute
  csrw MEPC, t3
  mret

#if __riscv_xlen == 64
trap_pa56_alias_load:
  li t3, CAUSE_LOAD_ACCESS
  bne t0, t3, fail_cause
  la t3, marker
  li t4, 1
  slli t4, t4, 56
  add t3, t3, t4
  bne t2, t3, fail_tval
  li t1, 5
  csrw MSCRATCH, t1
  la t3, after_pa56_alias_load
  csrw MEPC, t3
  mret

trap_pa56_alias_execute:
  li t3, CAUSE_INST_ACCESS
  bne t0, t3, fail_cause
  la t3, pa_alias_exec_target
  li t4, 1
  slli t4, t4, 56
  add t3, t3, t4
  bne t2, t3, fail_tval
  li t1, 6
  csrw MSCRATCH, t1
  la t3, after_pa56_alias_execute
  csrw MEPC, t3
  mret
#endif

trap_final_ecall:
  li t3, CAUSE_ECALL_U
  bne t0, t3, fail_cause
  li a0, 0
  j machine_exit

fail_cause:
  li a0, 92
  j machine_exit
fail_tval:
  li a0, 93

machine_exit:
  li t0, EXIT_MMIO
  sw a0, 0(t0)
1:
  j 1b

  // If execute protection fails, this U-mode path reaches ECALL with the
  // stage counter still expecting an instruction-access fault, which makes
  // the machine handler fail closed.
forbidden_kernel_target:
  li a0, 94
  ecall
  j forbidden_kernel_target

  .size main, .-main

  .section .text.user,"ax",@progbits
  .align 2
  .globl user_entry
user_entry:
  // Legal RW access to the user data region proves the positive PMP path.
  la t0, marker
  li t1, 0x5a
  sb t1, 0(t0)
  lbu t2, 0(t0)
  bne t1, t2, user_fail

  // U-mode load from the machine region must fail without reaching memory.
  la t0, main
  lbu t1, 0(t0)
after_kernel_load:

  // U-mode store to the machine region must fail.
  la t0, main
  li t1, 0x6b
  sb t1, 0(t0)
after_kernel_store:

  // User text is executable/readable but not writable.
  la t0, user_entry
  sw t1, 0(t0)
after_text_store:

  // Jumping into the denied machine region must fault on instruction fetch.
  la t0, forbidden_kernel_target
  jalr zero, t0, 0
after_kernel_execute:

#if __riscv_xlen == 64
  // Both low targets are legal under PMP. Setting architectural bit 56 must
  // fault before the PA56 bus rather than silently aliasing those low targets.
  la t0, marker
  li t1, 1
  slli t1, t1, 56
  add t0, t0, t1
  lbu t2, 0(t0)
after_pa56_alias_load:

  la t0, pa_alias_exec_target
  li t1, 1
  slli t1, t1, 56
  add t0, t0, t1
  jalr zero, t0, 0
after_pa56_alias_execute:
#endif

  ecall

#if __riscv_xlen == 64
pa_alias_exec_target:
  // Reaching this low address proves that a high architectural address was
  // truncated and aliased instead of faulting at the architectural/PA seam.
  li a0, 96
  ecall
  j pa_alias_exec_target
#endif

user_fail:
  // An early positive-path failure reaches M mode with a stage/cause mismatch,
  // which is intentionally fail-closed and produces a nonzero machine exit.
  li a0, 95
  ecall
1:
  j 1b

  .option pop

  .section .data.user,"aw",@progbits
  .align 3
  .globl user_data_begin
user_data_begin:
marker:
  .byte 0
  .balign 8
  .space 64
  .globl user_data_end
user_data_end:
