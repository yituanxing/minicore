#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

from rv32_priv_asm import PrivilegedProgram, li32

BASE = 0x80000000
CTX_A = 0x80003A00
CTX_B = 0x80003B00
KSTATE = 0x80003C00
KERNEL_LIMIT = 0x80004000
TASK_A_TEXT = 0x80004000
TASK_A_DATA = 0x80005000
TASK_A_LIMIT = 0x80006000
TASK_B_TEXT = 0x80006000
TASK_B_DATA = 0x80007000
TASK_B_LIMIT = 0x80008000
TASK_A_STACK = TASK_A_LIMIT - 16
TASK_B_STACK = TASK_B_LIMIT - 16

UART = 0x10000000
EXIT = 0x10000008
MTIMECMP = 0x02004000
MTIME = 0x0200BFF8

MSTATUS = 0x300
MIE = 0x304
MTVEC = 0x305
MSCRATCH = 0x340
MEPC = 0x341
MCAUSE = 0x342
MTVAL = 0x343
PMPCFG0 = 0x3A0
PMPADDR0 = 0x3B0
PMPADDR1 = 0x3B1
PMPADDR2 = 0x3B2

SYS_WRITE = 1
SYS_GET_TICKS = 2
SYS_YIELD = 3
SYS_EXIT = 4

USER_ECALL = 8
LOAD_ACCESS_FAULT = 5
MACHINE_TIMER_INTERRUPT = 0x80000007

PMPCFG0_VALUE = 0x000B0D08
PERIOD = 96

STATE_RUNNABLE = 0
STATE_EXITED = 1
STATE_KILLED = 2

K_TICKS = 0
K_SCHEDULES = 4
K_A_STATE = 8
K_B_STATE = 12
K_A_FAULTS = 16
K_B_FAULTS = 20

A_COUNTER = TASK_A_DATA
A_MESSAGE = TASK_A_DATA + 0x100
B_COUNTER = TASK_B_DATA
B_READY = TASK_B_DATA + 4
B_MESSAGE = TASK_B_DATA + 0x100

MESSAGE_A = b"task A isolated\n"
MESSAGE_B = b"task B survived\n"

A_SIGNATURES = {
    8: 0x18,
    9: 0x19,
    18: 0x22,
    19: 0x23,
    20: 0x24,
    21: 0x25,
    22: 0x26,
    23: 0x27,
    24: 0x28,
    25: 0x29,
    26: 0x2A,
    27: 0x2B,
}
B_SIGNATURES = {
    8: 0x48,
    9: 0x49,
    18: 0x52,
    19: 0x53,
    20: 0x54,
    21: 0x55,
    22: 0x56,
    23: 0x57,
    24: 0x58,
    25: 0x59,
    26: 0x5A,
    27: 0x5B,
}


def absolute(program: PrivilegedProgram, rd: int, value: int) -> None:
    li32(program, rd, value)


def ecall(program: PrivilegedProgram) -> None:
    program.emit(0x00000073)


def append_bytes(program: PrivilegedProgram, data: bytes) -> None:
    for offset in range(0, len(data), 4):
        chunk = data[offset : offset + 4].ljust(4, b"\0")
        program.emit(int.from_bytes(chunk, "little"))


def pad_to(program: PrivilegedProgram, address: int) -> None:
    target_words = (address - BASE) // 4
    if BASE + target_words * 4 != address:
        raise ValueError(f"unaligned section address: {address:#x}")
    if len(program.words) > target_words:
        raise ValueError(
            f"section overflow: pc={BASE + program.pc:#x}, target={address:#x}"
        )
    while len(program.words) < target_words:
        program.emit(0)


def emit_machine_exit(program: PrivilegedProgram, code_register: int) -> None:
    absolute(program, 5, EXIT)
    program.sw(code_register, 5, 0)
    program.ebreak()


def emit_fail_exit(program: PrivilegedProgram, code: int) -> None:
    program.addi(10, 0, code)
    program.addi(17, 0, SYS_EXIT)
    ecall(program)
    program.ebreak()


def check_signatures(
    program: PrivilegedProgram,
    signatures: dict[int, int],
    failure: str,
) -> None:
    for register, value in signatures.items():
        program.addi(7, 0, value)
        program.bne(register, 7, failure)


def emit_pmp_for_task(
    program: PrivilegedProgram,
    text: int,
    data: int,
    limit: int,
) -> None:
    absolute(program, 6, text >> 2)
    program.csrw(PMPADDR0, 6)
    absolute(program, 6, data >> 2)
    program.csrw(PMPADDR1, 6)
    absolute(program, 6, limit >> 2)
    program.csrw(PMPADDR2, 6)


def emit_timer_deadline(program: PrivilegedProgram) -> None:
    absolute(program, 28, MTIME)
    program.lw(29, 28, 0)
    program.addi(29, 29, PERIOD)
    absolute(program, 28, MTIMECMP)
    program.addi(30, 0, -1)
    program.sw(30, 28, 4)
    program.sw(29, 28, 0)
    program.sw(0, 28, 4)


def build_image() -> PrivilegedProgram:
    p = PrivilegedProgram()

    # Kernel-owned state and task B's initial context are created with ordinary
    # stores so the DUT and every reference see identical memory.
    absolute(p, 28, KSTATE)
    for offset in (
        K_TICKS,
        K_SCHEDULES,
        K_A_STATE,
        K_B_STATE,
        K_A_FAULTS,
        K_B_FAULTS,
    ):
        p.sw(0, 28, offset)
    absolute(p, 28, A_COUNTER)
    p.sw(0, 28, 0)
    absolute(p, 28, B_COUNTER)
    p.sw(0, 28, 0)
    p.sw(0, 28, 4)

    absolute(p, 28, CTX_B)
    for register in range(1, 32):
        p.sw(0, 28, register * 4)
    absolute(p, 29, TASK_B_STACK)
    p.sw(29, 28, 2 * 4)
    for register, value in B_SIGNATURES.items():
        p.addi(29, 0, value)
        p.sw(29, 28, register * 4)
    p.la(29, "task_b_main")
    p.sw(29, 28, 128)

    p.la(5, "trap_handler")
    p.csrw(MTVEC, 5)
    absolute(p, 5, CTX_A)
    p.csrw(MSCRATCH, 5)

    absolute(p, 5, PMPCFG0_VALUE)
    p.csrw(PMPCFG0, 5)
    emit_pmp_for_task(p, TASK_A_TEXT, TASK_A_DATA, TASK_A_LIMIT)

    emit_timer_deadline(p)
    p.addi(5, 0, 0x80)
    p.csrw(MIE, 5)

    absolute(p, 2, TASK_A_STACK)
    for register, value in A_SIGNATURES.items():
        p.addi(register, 0, value)
    p.la(5, "task_a_main")
    p.csrw(MEPC, 5)
    # MPP=U and MPIE=1: MRET enters U-mode with timer interrupts enabled.
    p.addi(5, 0, 0x80)
    p.csrw(MSTATUS, 5)
    p.mret()

    p.label("bootstrap_returned")
    p.addi(10, 0, 80)
    emit_machine_exit(p, 10)

    p.label("trap_handler")
    # mscratch names the running task frame. Preserve all user registers before
    # reusing any temporary in M-mode.
    p.csrrw(5, MSCRATCH, 5)
    p.sw(6, 5, 6 * 4)
    p.csrr(6, MSCRATCH)
    p.sw(6, 5, 5 * 4)
    for register in (*range(1, 5), 7, *range(8, 32)):
        p.sw(register, 5, register * 4)
    p.csrr(6, MEPC)
    p.sw(6, 5, 128)

    p.csrr(6, MCAUSE)
    li32(p, 7, MACHINE_TIMER_INTERRUPT)
    p.beq(6, 7, "handle_timer")
    p.addi(7, 0, USER_ECALL)
    p.beq(6, 7, "handle_syscall")
    p.addi(7, 0, LOAD_ACCESS_FAULT)
    p.beq(6, 7, "handle_expected_fault")
    p.jal(0, "bad_trap")

    p.label("handle_timer")
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_TICKS)
    p.addi(29, 29, 1)
    p.sw(29, 28, K_TICKS)
    emit_timer_deadline(p)
    p.jal(0, "schedule_other")

    p.label("handle_syscall")
    p.lw(6, 5, 17 * 4)
    p.addi(7, 0, SYS_WRITE)
    p.beq(6, 7, "sys_write")
    p.addi(7, 0, SYS_GET_TICKS)
    p.beq(6, 7, "sys_get_ticks")
    p.addi(7, 0, SYS_YIELD)
    p.beq(6, 7, "sys_yield")
    p.addi(7, 0, SYS_EXIT)
    p.beq(6, 7, "sys_exit")
    p.jal(0, "bad_syscall")

    p.label("sys_write")
    p.lw(28, 5, 10 * 4)
    p.lw(29, 5, 11 * 4)
    p.addi(31, 29, 0)
    absolute(p, 6, CTX_A)
    p.beq(5, 6, "write_bounds_a")
    absolute(p, 6, TASK_B_DATA)
    absolute(p, 7, TASK_B_LIMIT)
    p.jal(0, "validate_write")
    p.label("write_bounds_a")
    absolute(p, 6, TASK_A_DATA)
    absolute(p, 7, TASK_A_LIMIT)
    p.label("validate_write")
    p.bltu(28, 6, "bad_syscall")
    p.add(30, 28, 29)
    p.bltu(30, 28, "bad_syscall")
    p.bltu(7, 30, "bad_syscall")
    absolute(p, 6, UART)
    p.label("write_loop")
    p.beq(29, 0, "write_done")
    p.lbu(7, 28, 0)
    p.sb(7, 6, 0)
    p.addi(28, 28, 1)
    p.addi(29, 29, -1)
    p.jal(0, "write_loop")
    p.label("write_done")
    p.sw(31, 5, 10 * 4)
    p.jal(0, "advance_epc_restore")

    p.label("sys_get_ticks")
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_TICKS)
    p.sw(29, 5, 10 * 4)
    p.jal(0, "advance_epc_restore")

    p.label("sys_yield")
    p.lw(6, 5, 128)
    p.addi(6, 6, 4)
    p.sw(6, 5, 128)
    p.jal(0, "schedule_other")

    p.label("sys_exit")
    p.lw(6, 5, 10 * 4)
    p.bne(6, 0, "exit_nonzero")
    absolute(p, 7, CTX_A)
    p.beq(5, 7, "mark_a_exited")
    absolute(p, 28, KSTATE)
    p.addi(29, 0, STATE_EXITED)
    p.sw(29, 28, K_B_STATE)
    p.jal(0, "schedule_other")
    p.label("mark_a_exited")
    absolute(p, 28, KSTATE)
    p.addi(29, 0, STATE_EXITED)
    p.sw(29, 28, K_A_STATE)
    p.jal(0, "schedule_other")
    p.label("exit_nonzero")
    emit_machine_exit(p, 6)

    p.label("handle_expected_fault")
    absolute(p, 6, CTX_A)
    p.bne(5, 6, "bad_trap")
    p.csrr(6, MTVAL)
    absolute(p, 7, TASK_B_DATA)
    p.bne(6, 7, "bad_trap")
    absolute(p, 28, KSTATE)
    p.addi(29, 0, STATE_KILLED)
    p.sw(29, 28, K_A_STATE)
    p.lw(29, 28, K_A_FAULTS)
    p.addi(29, 29, 1)
    p.sw(29, 28, K_A_FAULTS)
    absolute(p, 28, B_READY)
    p.addi(29, 0, 1)
    p.sw(29, 28, 0)
    p.jal(0, "schedule_other")

    p.label("advance_epc_restore")
    p.lw(6, 5, 128)
    p.addi(6, 6, 4)
    p.sw(6, 5, 128)
    p.jal(0, "restore_frame")

    p.label("schedule_other")
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_SCHEDULES)
    p.addi(29, 29, 1)
    p.sw(29, 28, K_SCHEDULES)
    absolute(p, 6, CTX_A)
    p.beq(5, 6, "try_b")
    p.jal(0, "try_a")

    p.label("try_a")
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_A_STATE)
    p.beq(29, 0, "select_a")
    p.lw(29, 28, K_B_STATE)
    p.beq(29, 0, "select_b")
    p.jal(0, "finish_kernel")

    p.label("try_b")
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_B_STATE)
    p.beq(29, 0, "select_b")
    p.lw(29, 28, K_A_STATE)
    p.beq(29, 0, "select_a")
    p.jal(0, "finish_kernel")

    p.label("select_a")
    absolute(p, 5, CTX_A)
    emit_pmp_for_task(p, TASK_A_TEXT, TASK_A_DATA, TASK_A_LIMIT)
    p.jal(0, "restore_frame")

    p.label("select_b")
    absolute(p, 5, CTX_B)
    emit_pmp_for_task(p, TASK_B_TEXT, TASK_B_DATA, TASK_B_LIMIT)

    p.label("restore_frame")
    p.csrw(MSCRATCH, 5)
    p.lw(6, 5, 128)
    p.csrw(MEPC, 6)
    for register in (*range(1, 5), 7, *range(8, 32)):
        p.lw(register, 5, register * 4)
    p.lw(6, 5, 6 * 4)
    p.lw(5, 5, 5 * 4)
    p.mret()

    p.label("finish_kernel")
    absolute(p, 28, MTIMECMP)
    p.addi(29, 0, -1)
    p.sw(29, 28, 0)
    p.sw(29, 28, 4)
    absolute(p, 28, KSTATE)
    p.lw(29, 28, K_A_STATE)
    p.addi(30, 0, STATE_KILLED)
    p.bne(29, 30, "finish_fail")
    p.lw(29, 28, K_B_STATE)
    p.addi(30, 0, STATE_EXITED)
    p.bne(29, 30, "finish_fail")
    p.lw(29, 28, K_A_FAULTS)
    p.addi(30, 0, 1)
    p.bne(29, 30, "finish_fail")
    p.lw(29, 28, K_TICKS)
    p.beq(29, 0, "finish_fail")
    p.lw(29, 28, K_SCHEDULES)
    p.beq(29, 0, "finish_fail")
    absolute(p, 28, A_COUNTER)
    p.lw(29, 28, 0)
    p.addi(30, 0, 3)
    p.bltu(29, 30, "finish_fail")
    absolute(p, 28, B_COUNTER)
    p.lw(29, 28, 0)
    p.beq(29, 0, "finish_fail")
    p.addi(10, 0, 0)
    emit_machine_exit(p, 10)

    p.label("finish_fail")
    p.addi(10, 0, 84)
    emit_machine_exit(p, 10)
    p.label("bad_syscall")
    p.addi(10, 0, 83)
    emit_machine_exit(p, 10)
    p.label("bad_trap")
    p.addi(10, 0, 82)
    emit_machine_exit(p, 10)

    if BASE + p.pc > CTX_A:
        raise ValueError(f"kernel code exceeded reserved code area: {BASE + p.pc:#x}")
    pad_to(p, TASK_A_TEXT)

    p.label("task_a_main")
    absolute(p, 5, TASK_A_STACK)
    p.bne(2, 5, "task_a_fail")
    check_signatures(p, A_SIGNATURES, "task_a_fail")
    p.addi(6, 0, 0x123)
    p.sw(6, 2, 0)
    p.lw(7, 2, 0)
    p.bne(6, 7, "task_a_fail")
    p.la(10, "task_a_message")
    p.addi(11, 0, len(MESSAGE_A))
    p.addi(17, 0, SYS_WRITE)
    ecall(p)
    p.addi(6, 0, len(MESSAGE_A))
    p.bne(10, 6, "task_a_fail")
    p.addi(17, 0, SYS_YIELD)
    ecall(p)

    p.label("task_a_loop")
    check_signatures(p, A_SIGNATURES, "task_a_fail")
    absolute(p, 5, A_COUNTER)
    p.lw(6, 5, 0)
    p.addi(6, 6, 1)
    p.sw(6, 5, 0)
    p.addi(7, 0, 3)
    p.bltu(6, 7, "task_a_loop")
    absolute(p, 5, TASK_B_DATA)
    p.label("task_a_attack_b_data")
    p.lw(6, 5, 0)
    emit_fail_exit(p, 91)

    p.label("task_a_fail")
    emit_fail_exit(p, 12)

    if BASE + p.pc > TASK_A_DATA:
        raise ValueError(f"task A text overflow: {BASE + p.pc:#x}")
    pad_to(p, TASK_A_DATA)
    p.label("task_a_counter")
    p.emit(0)
    pad_to(p, A_MESSAGE)
    p.label("task_a_message")
    append_bytes(p, MESSAGE_A)

    if BASE + p.pc > TASK_A_LIMIT:
        raise ValueError(f"task A data overflow: {BASE + p.pc:#x}")
    pad_to(p, TASK_B_TEXT)

    p.label("task_b_main")
    absolute(p, 5, TASK_B_STACK)
    p.bne(2, 5, "task_b_fail")
    check_signatures(p, B_SIGNATURES, "task_b_fail")
    p.addi(6, 0, 0x456)
    p.sw(6, 2, 0)
    p.lw(7, 2, 0)
    p.bne(6, 7, "task_b_fail")
    p.la(10, "task_b_message")
    p.addi(11, 0, len(MESSAGE_B))
    p.addi(17, 0, SYS_WRITE)
    ecall(p)
    p.addi(6, 0, len(MESSAGE_B))
    p.bne(10, 6, "task_b_fail")

    p.label("task_b_loop")
    check_signatures(p, B_SIGNATURES, "task_b_fail")
    absolute(p, 5, B_COUNTER)
    p.lw(6, 5, 0)
    p.addi(6, 6, 1)
    p.sw(6, 5, 0)
    p.lw(7, 5, 4)
    p.beq(7, 0, "task_b_loop")
    p.addi(17, 0, SYS_GET_TICKS)
    ecall(p)
    p.beq(10, 0, "task_b_fail")
    p.addi(10, 0, 0)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    p.label("task_b_fail")
    emit_fail_exit(p, 13)

    if BASE + p.pc > TASK_B_DATA:
        raise ValueError(f"task B text overflow: {BASE + p.pc:#x}")
    pad_to(p, TASK_B_DATA)
    p.label("task_b_counter")
    p.emit(0)
    p.label("task_b_ready")
    p.emit(0)
    pad_to(p, B_MESSAGE)
    p.label("task_b_message")
    append_bytes(p, MESSAGE_B)

    if BASE + p.pc > TASK_B_LIMIT:
        raise ValueError(f"task B data overflow: {BASE + p.pc:#x}")
    return p


def main() -> None:
    output = Path(
        sys.argv[1]
        if len(sys.argv) > 1
        else "build/rv32imu-isolated-scheduler/software"
    )
    output.mkdir(parents=True, exist_ok=True)

    program = build_image()
    image = program.image()
    image_path = output / "isolated-scheduler.bin"
    image_path.write_bytes(image)

    digest = hashlib.sha256(image).hexdigest()
    (output / "manifest.txt").write_text(
        "\n".join(
            (
                "march=rv32im_zicsr",
                "mabi=ilp32",
                "privileges=MU",
                "pmp_entries=4",
                "pmp_mode=dynamic-tor",
                f"pmpcfg0=0x{PMPCFG0_VALUE:08x}",
                f"kernel_region=0x{BASE:08x}-0x{KERNEL_LIMIT:08x}:---",
                f"task_a_text=0x{TASK_A_TEXT:08x}-0x{TASK_A_DATA:08x}:r-x",
                f"task_a_data=0x{TASK_A_DATA:08x}-0x{TASK_A_LIMIT:08x}:rw-",
                f"task_b_text=0x{TASK_B_TEXT:08x}-0x{TASK_B_DATA:08x}:r-x",
                f"task_b_data=0x{TASK_B_DATA:08x}-0x{TASK_B_LIMIT:08x}:rw-",
                "syscalls=write,get_ticks,yield,exit",
                "expected_task_a_state=killed",
                "expected_task_b_state=exited",
                "expected_cross_task_faults=1",
                f"message_a={MESSAGE_A.decode('ascii').rstrip()}",
                f"message_b={MESSAGE_B.decode('ascii').rstrip()}",
                f"binary_bytes={len(image)}",
                f"binary_words={len(image) // 4}",
                f"binary_sha256={digest}",
            )
        )
        + "\n",
        encoding="utf-8",
    )
    (output / "labels.txt").write_text(
        "".join(
            f"{name} 0x{BASE + index * 4:08x}\n"
            for name, index in sorted(program.labels.items(), key=lambda item: item[1])
        ),
        encoding="utf-8",
    )
    print(f"isolated-scheduler {len(image)} {len(image) // 4} {digest}")


if __name__ == "__main__":
    main()
