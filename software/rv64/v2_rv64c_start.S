.section .text.start, "ax", @progbits
.option rvc
.globl _start
.type _start, @function
_start:
    li a0, 11
    call rv64c_workload
    ebreak
1:
    j 1b
.size _start, .-_start
