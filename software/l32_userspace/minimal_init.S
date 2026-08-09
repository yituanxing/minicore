/* L32-H: libc-free Linux PID 1 proof for RV32IMA/ILP32.
 *
 * Keep the first userspace workload intentionally tiny: one Linux write(2)
 * syscall proves execve -> U-mode -> ECALL -> S-mode syscall handling, then
 * sched_yield keeps PID 1 alive without introducing libc or a userspace ABI
 * beyond the frozen soft-float ILP32 ISA contract.
 */

#define L32_INIT_MESSAGE_LEN 28

.section .text
.globl _start
.type _start, @function
_start:
    li      a0, 1
    la      a1, l32_init_message
    li      a2, L32_INIT_MESSAGE_LEN
    li      a7, 64              /* __NR_write */
    ecall

1:
    li      a7, 124             /* __NR_sched_yield */
    ecall
    j       1b
.size _start, . - _start

.section .rodata
.align 2
l32_init_message:
    .ascii  "AetherCore L32 init running\n"

.section .note.GNU-stack,"",@progbits
