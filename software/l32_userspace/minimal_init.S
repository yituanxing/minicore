/* L32-I: libc-free Linux PID 1 UART interrupt proof for RV32IMA/ILP32.
 *
 * The first userspace checkpoint already proved execve -> U-mode -> ECALL ->
 * S-mode syscall handling -> return. This stage deliberately makes the same
 * write(2) marker longer than one 16550 FIFO so Linux must refill ttyS0 through
 * the real THRE -> PLIC source 10 -> SEIP interrupt path.
 */

#define L32_INIT_MESSAGE_LEN 21

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
    .ascii  "L32 USER UART IRQ OK\n"

.section .note.GNU-stack,"",@progbits
