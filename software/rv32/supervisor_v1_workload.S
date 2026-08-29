  .section .text,"ax",@progbits
  .globl main
  .type main, @function
  .option push
  .option norvc

  .equ EXIT_MMIO, 0x10000008
  .equ MSTATUS_MPP_S, 0x00000800
  .equ MSTATUS_MPIE,  0x00000080
  .equ SSTATUS_SPIE,  0x00000020
  .equ SSTATUS_SPP,   0x00000100
  .equ CAUSE_ECALL_U, 8
  .equ CAUSE_ECALL_S, 9

#if __riscv_xlen == 64
  // This first RV64 M/S/U profile implements only 64-bit S/U XLEN, so the
  // WARL SXL/UXL fields are fixed at encoding 2. RV32 has no such fields.
  // 第一层 RV64 M/S/U 只实现 64 位 S/U XLEN，因此 SXL/UXL 固定为编码 2。
  .equ MSTATUS_XLEN_BASE, 0x0000000a00000000
  .equ SSTATUS_XLEN_BASE, 0x0000000200000000
#else
  .equ MSTATUS_XLEN_BASE, 0
  .equ SSTATUS_XLEN_BASE, 0
#endif

main:
  // The same executable contract must observe the correct reset status on
  // both XLENs before it starts changing writable privilege state.
  csrr t0, mstatus
  li t1, MSTATUS_XLEN_BASE
  bne t0, t1, fail_mstatus_reset

  // Fail closed if an exception unexpectedly reaches M mode after bring-up.
  la t0, machine_trap_fail
  csrw mtvec, t0

  la t0, supervisor_trap
  csrw stvec, t0

  // V1 delegates only the two ECALL classes exercised by this workload.
  li t0, (1 << CAUSE_ECALL_U) | (1 << CAUSE_ECALL_S)
  csrw medeleg, t0

  // mideleg is intentionally WARL-zero in V1: there is no genuine S-level
  // interrupt source yet. Prove the executable sees that boundary.
  li t0, -1
  csrw mideleg, t0
  csrr t1, mideleg
  bnez t1, fail_mideleg

  csrw sscratch, zero
  la s0, younger_sentinel
  sw zero, 0(s0)

  // Enter Supervisor mode through the architectural MRET path.
  la t0, supervisor_entry
  csrw mepc, t0
  li t0, MSTATUS_MPP_S | MSTATUS_MPIE
  csrw mstatus, t0
  csrr t1, mstatus
  li t2, MSTATUS_XLEN_BASE | MSTATUS_MPP_S | MSTATUS_MPIE
  bne t1, t2, fail_mstatus_write
  mret

  // MRET must redirect; this path must never execute.
  li a0, 10
  j finish

  .align 2
supervisor_entry:
  // We can only execute these S CSRs if the MRET really entered S mode.
  csrr t0, sstatus
  li t1, SSTATUS_XLEN_BASE
  bne t0, t1, fail_s_entry_status

s_ecall_site:
  ecall

  // The trap handler rewrites sepc to s_resume. If a younger store escapes
  // before trap redirect, the sentinel below makes the workload fail later.
  li t0, 0x5a
  sw t0, 0(s0)
  li a0, 11
  j finish

s_resume:
  csrr t0, sscratch
  li t1, 1
  bne t0, t1, fail_s_trap_count

  lw t0, 0(s0)
  bnez t0, fail_s_younger_store

  // SRET from an S-origin trap clears SPP, copies SPIE to SIE and sets SPIE.
  csrr t0, sstatus
  li t1, SSTATUS_XLEN_BASE | SSTATUS_SPIE
  bne t0, t1, fail_s_return_status

  // Enter U mode with a second SRET. Clearing writable sstatus state leaves
  // SPP=U and SIE=0 while RV64's read-only UXL field remains visible.
  csrw sstatus, zero
  csrr t0, sstatus
  li t1, SSTATUS_XLEN_BASE
  bne t0, t1, fail_sstatus_xlen
  la t0, user_entry
  csrw sepc, t0
  sret

  li a0, 12
  j finish

  .align 2
user_entry:
u_ecall_site:
  ecall

  li t0, 0x6b
  sw t0, 0(s0)
  li a0, 13
  j finish

u_resume:
  // Reaching this PC after an ECALL-from-U proves U -> S delegation and
  // SRET back to U. A wrong S-mode return would have produced cause 9 above.
  lw t0, 0(s0)
  bnez t0, fail_u_younger_store

  li a0, 0
  j finish

  .align 2
supervisor_trap:
  csrr t0, scause
  csrr t1, sepc
  csrr t2, stval
  csrr t3, sstatus
  csrr t4, sscratch

  li t5, CAUSE_ECALL_S
  beq t0, t5, handle_s_ecall
  li t5, CAUSE_ECALL_U
  beq t0, t5, handle_u_ecall
  li a0, 20
  j finish

handle_s_ecall:
  la t5, s_ecall_site
  bne t1, t5, fail_s_epc
  bnez t2, fail_s_tval
  li t5, SSTATUS_XLEN_BASE | SSTATUS_SPP
  bne t3, t5, fail_s_trap_status
  bnez t4, fail_s_trap_count
  li t4, 1
  csrw sscratch, t4
  la t5, s_resume
  csrw sepc, t5
  sret

  li a0, 21
  j finish

handle_u_ecall:
  la t5, u_ecall_site
  bne t1, t5, fail_u_epc
  bnez t2, fail_u_tval
  li t5, SSTATUS_XLEN_BASE
  bne t3, t5, fail_u_trap_status
  li t5, 1
  bne t4, t5, fail_u_trap_count
  li t4, 2
  csrw sscratch, t4
  la t5, u_resume
  csrw sepc, t5
  sret

  li a0, 22
  j finish

  .align 2
machine_trap_fail:
  // Any delegated ECALL arriving here means the V1 privilege routing failed.
  li a0, 30
  j finish

fail_mideleg:
  li a0, 1
  j finish
fail_s_entry_status:
  li a0, 2
  j finish
fail_s_epc:
  li a0, 3
  j finish
fail_s_tval:
  li a0, 4
  j finish
fail_s_trap_status:
  li a0, 5
  j finish
fail_s_trap_count:
  li a0, 6
  j finish
fail_s_return_status:
  li a0, 7
  j finish
fail_s_younger_store:
  li a0, 8
  j finish
fail_u_epc:
  li a0, 9
  j finish
fail_u_tval:
  li a0, 14
  j finish
fail_u_trap_status:
  li a0, 15
  j finish
fail_u_trap_count:
  li a0, 16
  j finish
fail_u_younger_store:
  li a0, 17
  j finish
fail_mstatus_reset:
  li a0, 40
  j finish
fail_mstatus_write:
  li a0, 41
  j finish
fail_sstatus_xlen:
  li a0, 42

finish:
  li t0, EXIT_MMIO
  sw a0, 0(t0)
1:
  j 1b

  .size main, .-main
  .option pop

  .section .data,"aw",@progbits
  .align 2
younger_sentinel:
  .word 0