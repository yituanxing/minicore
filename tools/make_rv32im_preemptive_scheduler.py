#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

from rv32_priv_asm import PrivilegedProgram, li32

BASE = 0x80000000
CTX_A = 0x80001000
CTX_B = 0x80001100
STACK_A = 0x80002000
STACK_B = 0x80003000
SHARED = 0x80004000
MTIMECMP = 0x02004000
MTIME = 0x0200BFF8
EXIT = 0x10000008

MSTATUS = 0x300
MIE = 0x304
MTVEC = 0x305
MSCRATCH = 0x340
MEPC = 0x341
MCAUSE = 0x342

COUNTER_A = 0
COUNTER_B = 4
TICKS = 8
LOG = 16
MAX_TICKS = 8
PERIOD = 96

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
EXPECTED_LOG = (1, 0, 1, 0, 1, 0, 1, 0)


def absolute(program: PrivilegedProgram, rd: int, value: int) -> None:
    li32(program, rd, value)


def emit_exit(program: PrivilegedProgram, code: int) -> None:
    absolute(program, 30, EXIT)
    program.addi(31, 0, code)
    program.sw(31, 30, 0)
    program.ebreak()


def check_signature(
    program: PrivilegedProgram,
    register: int,
    value: int,
    failure: str = "fail_context",
) -> None:
    program.addi(7, 0, value)
    program.bne(register, 7, failure)


def build_scheduler() -> PrivilegedProgram:
    p = PrivilegedProgram()

    # The initial execution context is task A. The second context is prepared
    # entirely through ordinary RV32 Stores so the reference memory observes
    # exactly the same task frame as the DUT.
    absolute(p, 2, STACK_A)
    absolute(p, 5, SHARED)
    for offset in (
        COUNTER_A,
        COUNTER_B,
        TICKS,
        *(LOG + index * 4 for index in range(MAX_TICKS)),
    ):
        p.sw(0, 5, offset)

    absolute(p, 5, CTX_B)
    absolute(p, 6, STACK_B)
    p.sw(6, 5, 2 * 4)
    for register, value in B_SIGNATURES.items():
        p.addi(6, 0, value)
        p.sw(6, 5, register * 4)
    p.la(6, "task_b")
    p.sw(6, 5, 128)

    p.la(5, "trap_handler")
    p.csrw(MTVEC, 5)
    absolute(p, 5, CTX_A)
    p.csrw(MSCRATCH, 5)

    for register, value in A_SIGNATURES.items():
        p.addi(register, 0, value)

    # Program the first timer deadline using the safe RV32 high/low comparator
    # sequence, then enable the source and global Machine interrupt gates.
    absolute(p, 5, MTIME)
    p.lw(6, 5, 0)
    p.addi(6, 6, PERIOD)
    absolute(p, 5, MTIMECMP)
    p.addi(7, 0, -1)
    p.sw(7, 5, 4)
    p.sw(6, 5, 0)
    p.sw(0, 5, 4)
    p.addi(5, 0, 0x80)
    p.csrw(MIE, 5)
    p.addi(5, 0, 8)
    p.csrw(MSTATUS, 5)
    p.jal(0, "task_a")

    p.label("task_a")
    absolute(p, 5, STACK_A)
    p.bne(2, 5, "fail_context")
    for register, value in A_SIGNATURES.items():
        check_signature(p, register, value)
    absolute(p, 5, SHARED)
    p.lw(6, 5, COUNTER_A)
    p.addi(6, 6, 1)
    p.sw(6, 5, COUNTER_A)
    p.lw(6, 5, TICKS)
    p.addi(7, 0, MAX_TICKS)
    p.bgeu(6, 7, "finish")
    p.jal(0, "task_a")

    p.label("task_b")
    absolute(p, 5, STACK_B)
    p.bne(2, 5, "fail_context")
    for register, value in B_SIGNATURES.items():
        check_signature(p, register, value)
    absolute(p, 5, SHARED)
    p.lw(6, 5, COUNTER_B)
    p.addi(6, 6, 1)
    p.sw(6, 5, COUNTER_B)
    p.jal(0, "task_b")

    p.label("trap_handler")
    # mscratch always points to the running task frame. CSRRW acquires that
    # pointer while preserving the interrupted x5 value in the CSR. x6 is
    # stored before it is used to recover x5. Every x1..x31 value is then saved.
    p.csrrw(5, MSCRATCH, 5)
    p.sw(6, 5, 6 * 4)
    p.csrr(6, MSCRATCH)
    p.sw(6, 5, 5 * 4)
    for register in (*range(1, 5), 7, *range(8, 32)):
        p.sw(register, 5, register * 4)
    p.csrr(6, MEPC)
    p.sw(6, 5, 128)

    p.csrr(6, MCAUSE)
    li32(p, 7, 0x80000007)
    p.bne(6, 7, "handler_fail")

    absolute(p, 28, SHARED)
    p.lw(7, 28, TICKS)
    p.addi(7, 7, 1)
    p.sw(7, 28, TICKS)

    # Alternate contexts on every tick. The selected task ID is written to a
    # permanent log before any register restoration starts.
    absolute(p, 6, CTX_A)
    p.beq(5, 6, "switch_to_b")
    absolute(p, 5, CTX_A)
    p.addi(29, 0, 0)
    p.jal(0, "selected")

    p.label("switch_to_b")
    absolute(p, 5, CTX_B)
    p.addi(29, 0, 1)

    p.label("selected")
    p.addi(6, 7, -1)
    p.slli(6, 6, 2)
    p.addi(30, 28, LOG)
    p.add(30, 30, 6)
    p.sw(29, 30, 0)

    p.addi(6, 0, MAX_TICKS)
    p.bgeu(7, 6, "disable_timer")
    absolute(p, 28, MTIME)
    p.lw(6, 28, 0)
    p.addi(6, 6, PERIOD)
    absolute(p, 28, MTIMECMP)
    p.addi(29, 0, -1)
    p.sw(29, 28, 4)
    p.sw(6, 28, 0)
    p.sw(0, 28, 4)
    p.jal(0, "restore_next")

    p.label("disable_timer")
    absolute(p, 28, MTIMECMP)
    p.addi(29, 0, -1)
    p.sw(29, 28, 0)
    p.sw(29, 28, 4)

    p.label("restore_next")
    # x5 is the next frame pointer. Publish it to mscratch, restore mepc, then
    # restore every register. x6 and x5 are deliberately last so the frame base
    # remains available until the final Load.
    p.csrw(MSCRATCH, 5)
    p.lw(6, 5, 128)
    p.csrw(MEPC, 6)
    for register in (*range(1, 5), 7, *range(8, 32)):
        p.lw(register, 5, register * 4)
    p.lw(6, 5, 6 * 4)
    p.lw(5, 5, 5 * 4)
    p.mret()

    p.label("finish")
    p.addi(5, 0, 0)
    p.csrw(MSTATUS, 5)
    absolute(p, 5, SHARED)
    p.lw(6, 5, TICKS)
    p.addi(7, 0, MAX_TICKS)
    p.bne(6, 7, "fail_ticks")
    p.lw(6, 5, COUNTER_A)
    p.beq(6, 0, "fail_counter")
    p.lw(6, 5, COUNTER_B)
    p.beq(6, 0, "fail_counter")
    for index, value in enumerate(EXPECTED_LOG):
        p.lw(6, 5, LOG + index * 4)
        p.addi(7, 0, value)
        p.bne(6, 7, "fail_log")
    absolute(p, 5, STACK_A)
    p.bne(2, 5, "fail_context")
    for register, value in A_SIGNATURES.items():
        check_signature(p, register, value)
    emit_exit(p, 0)

    p.label("handler_fail")
    absolute(p, 28, MTIMECMP)
    p.addi(29, 0, -1)
    p.sw(29, 28, 0)
    p.sw(29, 28, 4)
    emit_exit(p, 11)

    p.label("fail_context")
    emit_exit(p, 12)
    p.label("fail_ticks")
    emit_exit(p, 13)
    p.label("fail_counter")
    emit_exit(p, 14)
    p.label("fail_log")
    emit_exit(p, 15)
    return p


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/rv32im-scheduler/software")
    output.mkdir(parents=True, exist_ok=True)

    program = build_scheduler()
    image = program.image()
    image_path = output / "preemptive-scheduler.bin"
    image_path.write_bytes(image)

    digest = hashlib.sha256(image).hexdigest()
    words = len(image) // 4
    (output / "manifest.txt").write_text(
        f"preemptive-scheduler {len(image)} {words} {digest}\n",
        encoding="utf-8",
    )
    (output / "labels.txt").write_text(
        "".join(
            f"{name} 0x{BASE + index * 4:08x}\n"
            for name, index in sorted(program.labels.items(), key=lambda item: item[1])
        ),
        encoding="utf-8",
    )
    print(f"preemptive-scheduler {len(image)} {words} {digest}")


if __name__ == "__main__":
    main()
