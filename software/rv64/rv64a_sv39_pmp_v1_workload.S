  .equ EXIT_MMIO,       0x10000008
  .equ MSTATUS_MPP_S,   0x00000800
  .equ CAUSE_ECALL_U,   8
  .equ CAUSE_ECALL_S,   9

  .equ PTE_V,           0x001
  .equ PTE_R,           0x002
  .equ PTE_W,           0x004
  .equ PTE_X,           0x008
  .equ PTE_U,           0x010
  .equ PTE_A,           0x040
  .equ PTE_D,           0x080
  .equ PTE_S_RX_A,      (PTE_V | PTE_R | PTE_X | PTE_A)
  .equ PTE_U_RX_A,      (PTE_V | PTE_R | PTE_X | PTE_U | PTE_A)
  .equ PTE_U_RW_AD,     (PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D)

  .section .text.init,"ax",@progbits
  .globl _start
  .type _start, @function
  .option push
  .option norvc

_start:
  // Reuse the production three-level Sv39 geometry from the RV64IM oracle.
  // The U data page below is where the A-extension traffic is exercised.
  la t0, __sv39_root
  la t1, __sv39_level1
  srli t1, t1, 2
  ori t1, t1, PTE_V
  sd t1, 16(t0)

  la t0, __sv39_level1
  la t1, __sv39_level0
  srli t1, t1, 2
  ori t1, t1, PTE_V
  sd t1, 0(t0)

  la t0, __sv39_level0
  la t1, supervisor_entry
  srli t1, t1, 2
  ori t1, t1, PTE_S_RX_A
  sd t1, 8(t0)

  la t1, user_entry
  srli t1, t1, 2
  ori t1, t1, PTE_U_RX_A
  sd t1, 16(t0)

  la t1, user_marker
  srli t1, t1, 2
  ori t1, t1, PTE_U_RW_AD
  sd t1, 24(t0)

  // One unlocked TOR entry permits the bounded executable and its implicit
  // page-table reads. Focused PMP tests retain denied-access ownership.
  li t0, -1
  csrw pmpaddr0, t0
  li t0, 0x0f
  csrw pmpcfg0, t0

  la t0, machine_trap_fail
  csrw mtvec, t0
  la t0, supervisor_trap
  csrw stvec, t0

  li t0, (1 << CAUSE_ECALL_U) | (1 << CAUSE_ECALL_S)
  csrw medeleg, t0
  csrw sscratch, zero

  li t0, 8
  slli t0, t0, 60
  la t1, __sv39_root
  srli t1, t1, 12
  or t0, t0, t1
  csrw satp, t0
  sfence.vma zero, zero

  la t0, supervisor_entry
  csrw mepc, t0
  li t0, MSTATUS_MPP_S
  csrw mstatus, t0
  mret

  li a0, 70
  j machine_exit

machine_trap_fail:
  li a0, 71
machine_exit:
  li t0, EXIT_MMIO
  sd a0, 0(t0)
1:
  j 1b

  .size _start, .-_start

  .section .text.supervisor,"ax",@progbits
  .align 2
  .globl supervisor_entry
supervisor_entry:
s_ecall_site:
  ecall

s_resume:
  csrr t0, sscratch
  li t1, 1
  bne t0, t1, supervisor_fail

  csrw sstatus, zero
  la t0, user_entry
  csrw sepc, t0
  sret

  li a0, 72
  j supervisor_fail_with_code

  .align 2
supervisor_trap:
  csrr t0, scause
  csrr t1, sepc
  csrr t2, stval
  csrr t3, sscratch

  li t4, CAUSE_ECALL_S
  beq t0, t4, handle_s_ecall
  li t4, CAUSE_ECALL_U
  beq t0, t4, handle_u_ecall
  li a0, 80
  j supervisor_fail_with_code

handle_s_ecall:
  bnez t3, supervisor_fail
  la t4, s_ecall_site
  bne t1, t4, supervisor_fail
  bnez t2, supervisor_fail
  li t3, 1
  csrw sscratch, t3
  la t4, s_resume
  csrw sepc, t4
  sret

  li a0, 81
  j supervisor_fail_with_code

handle_u_ecall:
  bnez t2, supervisor_fail
  li t4, 1
  beq t3, t4, handle_first_u_ecall
  li t4, 2
  beq t3, t4, handle_final_u_ecall
  li a0, 82
  j supervisor_fail_with_code

handle_first_u_ecall:
  la t4, u_ecall_site
  bne t1, t4, supervisor_fail
  li t3, 2
  csrw sscratch, t3
  la t4, u_resume
  csrw sepc, t4
  sret

  li a0, 83
  j supervisor_fail_with_code

handle_final_u_ecall:
  la t4, u_finish_ecall_site
  bne t1, t4, supervisor_fail
  li t3, 3
  csrw sscratch, t3

  csrw satp, zero
  sfence.vma zero, zero
  li a0, 0
  li t0, EXIT_MMIO
  sd a0, 0(t0)
1:
  j 1b

supervisor_fail:
  li a0, 84
supervisor_fail_with_code:
  csrw satp, zero
  sfence.vma zero, zero
  li t0, EXIT_MMIO
  sd a0, 0(t0)
1:
  j 1b

  .section .text.user,"ax",@progbits
  .align 2
  .globl user_entry
user_entry:
  // Full-width RV64A path: AMOADD.D plus a successful LR.D/SC.D pair.
  la t0, user_marker
  ld t1, 0(t0)
  li t2, 0x1122334455667788
  bne t1, t2, user_fail

  li t4, 1
  amoadd.d.aqrl t3, t4, (t0)
  bne t3, t2, user_fail
  addi t2, t2, 1
  ld t1, 0(t0)
  bne t1, t2, user_fail

  lr.d.aq t3, (t0)
  bne t3, t2, user_fail
  addi t4, t3, 1
  sc.d.rl t5, t4, (t0)
  bnez t5, user_fail
  ld t1, 0(t0)
  bne t1, t4, user_fail

  // RV64 AMO.W reads are sign-extended, while the write touches only the low
  // word. Signed and unsigned comparisons must therefore use 32-bit operands.
  la t0, user_atomic_word
  li t4, 1
  amomax.w t3, t4, (t0)
  li t2, -2147483648
  bne t3, t2, user_fail
  ld t1, 0(t0)
  li t2, 0x1122334400000001
  bne t1, t2, user_fail

  li t4, -1
  amomaxu.w t3, t4, (t0)
  li t2, 1
  bne t3, t2, user_fail
  ld t1, 0(t0)
  li t2, 0x11223344ffffffff
  bne t1, t2, user_fail

  // OpenSBI-style RV64 word locks need a matching LR.W/SC.W execution path,
  // not only decoder coverage inherited from the RV32A oracle.
  lr.w.aq t3, (t0)
  li t2, -1
  bne t3, t2, user_fail
  li t4, 2
  sc.w.rl t5, t4, (t0)
  bnez t5, user_fail
  ld t1, 0(t0)
  li t2, 0x1122334400000002
  bne t1, t2, user_fail

  // This implementation's bounded reservation policy pairs a reservation
  // with address plus access width. LR.W followed by SC.D therefore fails
  // without touching either word of the translated data location.
  lr.w.aq t3, (t0)
  li t2, 2
  bne t3, t2, user_fail
  li t4, 0
  sc.d.rl t5, t4, (t0)
  beqz t5, user_fail
  ld t1, 0(t0)
  li t2, 0x1122334400000002
  bne t1, t2, user_fail

  // A matching full-width reservation must then succeed.
  lr.d.aq t3, (t0)
  bne t3, t2, user_fail
  sc.d.rl t5, t4, (t0)
  bnez t5, user_fail
  ld t1, 0(t0)
  bnez t1, user_fail

u_ecall_site:
  ecall

u_resume:
  la t0, user_marker
  ld t1, 0(t0)
  li t2, 0x112233445566778a
  bne t1, t2, user_fail

  la t0, user_atomic_word
  ld t1, 0(t0)
  bnez t1, user_fail

u_finish_ecall_site:
  ecall

user_fail:
  ecall
1:
  j 1b

  .option pop

  .section .data.user,"aw",@progbits
  .align 3
  .globl user_marker
user_marker:
  .dword 0x1122334455667788
  .globl user_atomic_word
user_atomic_word:
  .dword 0x1122334480000000
