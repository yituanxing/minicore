/* RV64 Linux libc-free PID 1 UART interrupt proof.
 *
 * This marker is longer than one 16550 FIFO. Linux must therefore refill the
 * real ttyS0 through THRE -> PLIC source 10 -> SEIP while PID 1 is in U-mode.
 */

#define RV64_INIT_MESSAGE_LEN 22

.section .text
.globl _start
.type _start, @function
_start:
    li      a0, 1
    la      a1, rv64_init_message
    li      a2, RV64_INIT_MESSAGE_LEN
    li      a7, 64              /* __NR_write */
    ecall

1:
    li      a7, 124             /* __NR_sched_yield */
    ecall
    j       1b
.size _start, . - _start

.section .rodata
.align 3
rv64_init_message:
    .ascii  "RV64 USER UART IRQ OK\n"

.section .note.GNU-stack,"",@progbits
