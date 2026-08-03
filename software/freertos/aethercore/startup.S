.section .init, "ax", @progbits
.option norvc
.align 2
.global _start
.type _start, @function

.extern main
.extern freertos_risc_v_trap_handler
.extern __bss_start
.extern __bss_end
.extern __stack_top

_start:
    la sp, __stack_top

    la t0, __bss_start
    la t1, __bss_end
1:
    bgeu t0, t1, 2f
    sw zero, 0(t0)
    addi t0, t0, 4
    j 1b

2:
    la t0, freertos_risc_v_trap_handler
    csrw mtvec, t0
    call main

    li t0, 0x10000008
    sw a0, 0(t0)
3:
    ebreak
    j 3b

.size _start, . - _start
