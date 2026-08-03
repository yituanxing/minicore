#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

from rv32_priv_asm import PrivilegedProgram, li32

BASE = 0x80000000
KERNEL_LIMIT = 0x80001000
USER_TEXT = 0x80001000
USER_DATA = 0x80002000
USER_LIMIT = 0x80003000
USER_STACK = USER_LIMIT - 16
UART = 0x10000000
EXIT = 0x10000008
MTIME = 0x0200BFF8

MSTATUS = 0x300
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
SYS_EXIT = 3

INSTRUCTION_ACCESS_FAULT = 1
LOAD_ACCESS_FAULT = 5
STORE_ACCESS_FAULT = 7
USER_ECALL = 8

# entry0=[0, user text) ---; entry1=user text R-X; entry2=user data RW-.
PMPCFG0_VALUE = 0x000B0D08
EXPECTED_FAULT_STAGES = 6
MESSAGE = b"PMP isolation via SYS_WRITE\n"
ATTACK_BYTE = ord("@")
MARKER_BYTE = 0x5A
STACK_WORD = 0x123


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


def emit_expected_fault(
    program: PrivilegedProgram,
    *,
    label: str,
    cause: int,
    value: int | str,
    resume_from_a0: bool,
) -> None:
    program.label(label)
    program.addi(6, 0, cause)
    program.bne(5, 6, "bad_trap")
    program.csrr(7, MTVAL)
    if isinstance(value, str):
        program.la(6, value)
    else:
        absolute(program, 6, value)
    program.bne(7, 6, "bad_trap")
    program.addi(28, 28, 1)
    program.csrw(MSCRATCH, 28)
    if resume_from_a0:
        program.csrw(MEPC, 10)
    else:
        program.csrr(6, MEPC)
        program.addi(6, 6, 4)
        program.csrw(MEPC, 6)
    program.mret()


def build_image() -> PrivilegedProgram:
    p = PrivilegedProgram()

    # Machine bootstrap and three ordered TOR regions. Entries remain unlocked:
    # M-mode can service user buffers and platform MMIO, while U-mode is checked.
    p.la(5, "trap_handler")
    p.csrw(MTVEC, 5)
    absolute(p, 5, USER_TEXT >> 2)
    p.csrw(PMPADDR0, 5)
    absolute(p, 5, USER_DATA >> 2)
    p.csrw(PMPADDR1, 5)
    absolute(p, 5, USER_LIMIT >> 2)
    p.csrw(PMPADDR2, 5)
    absolute(p, 5, PMPCFG0_VALUE)
    p.csrw(PMPCFG0, 5)
    p.csrw(MSCRATCH, 0)
    p.la(5, "user_main")
    p.csrw(MEPC, 5)
    p.csrw(MSTATUS, 0)
    p.mret()

    p.label("bootstrap_returned")
    p.addi(10, 0, 80)
    emit_machine_exit(p, 10)

    p.label("trap_handler")
    p.csrr(5, MCAUSE)
    p.addi(6, 0, USER_ECALL)
    p.beq(5, 6, "syscall_dispatch")

    p.csrr(28, MSCRATCH)
    for stage, label in enumerate(
        (
            "fault_uart_store",
            "fault_kernel_load",
            "fault_kernel_store",
            "fault_text_store",
            "fault_kernel_execute",
            "fault_data_execute",
        )
    ):
        p.addi(6, 0, stage)
        p.beq(28, 6, label)
    p.jal(0, "bad_trap")

    emit_expected_fault(
        p,
        label="fault_uart_store",
        cause=STORE_ACCESS_FAULT,
        value=UART,
        resume_from_a0=False,
    )
    emit_expected_fault(
        p,
        label="fault_kernel_load",
        cause=LOAD_ACCESS_FAULT,
        value=BASE,
        resume_from_a0=False,
    )
    emit_expected_fault(
        p,
        label="fault_kernel_store",
        cause=STORE_ACCESS_FAULT,
        value=BASE,
        resume_from_a0=False,
    )
    emit_expected_fault(
        p,
        label="fault_text_store",
        cause=STORE_ACCESS_FAULT,
        value=USER_TEXT,
        resume_from_a0=False,
    )
    emit_expected_fault(
        p,
        label="fault_kernel_execute",
        cause=INSTRUCTION_ACCESS_FAULT,
        value="forbidden_kernel_target",
        resume_from_a0=True,
    )
    emit_expected_fault(
        p,
        label="fault_data_execute",
        cause=INSTRUCTION_ACCESS_FAULT,
        value="forbidden_data_target",
        resume_from_a0=True,
    )

    p.label("syscall_dispatch")
    p.addi(5, 0, SYS_WRITE)
    p.beq(17, 5, "sys_write")
    p.addi(5, 0, SYS_GET_TICKS)
    p.beq(17, 5, "sys_get_ticks")
    p.addi(5, 0, SYS_EXIT)
    p.beq(17, 5, "sys_exit")
    p.addi(10, 0, 81)
    emit_machine_exit(p, 10)

    p.label("sys_write")
    p.addi(29, 11, 0)
    absolute(p, 7, UART)
    p.label("write_loop")
    p.beq(11, 0, "write_done")
    p.lbu(6, 10, 0)
    p.sb(6, 7, 0)
    p.addi(10, 10, 1)
    p.addi(11, 11, -1)
    p.jal(0, "write_loop")
    p.label("write_done")
    p.addi(10, 29, 0)
    p.jal(0, "return_to_user")

    p.label("sys_get_ticks")
    absolute(p, 5, MTIME)
    p.lw(10, 5, 0)
    p.jal(0, "return_to_user")

    p.label("sys_exit")
    p.csrr(5, MSCRATCH)
    p.addi(6, 0, EXPECTED_FAULT_STAGES)
    p.bne(5, 6, "isolation_incomplete")
    p.la(5, "marker")
    p.lbu(6, 5, 0)
    p.addi(7, 0, MARKER_BYTE)
    p.bne(6, 7, "isolation_incomplete")
    absolute(p, 5, USER_STACK)
    p.lw(6, 5, 0)
    p.addi(7, 0, STACK_WORD)
    p.bne(6, 7, "isolation_incomplete")
    emit_machine_exit(p, 10)

    p.label("isolation_incomplete")
    p.addi(10, 0, 83)
    emit_machine_exit(p, 10)

    p.label("bad_trap")
    p.addi(10, 0, 82)
    emit_machine_exit(p, 10)

    p.label("return_to_user")
    p.csrr(5, MEPC)
    p.addi(5, 5, 4)
    p.csrw(MEPC, 5)
    p.mret()

    # If kernel-page execute permission accidentally leaks to U-mode, this code
    # reaches SYS_EXIT before all six expected fault stages and exits nonzero.
    p.label("forbidden_kernel_target")
    p.addi(10, 0, 90)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    if BASE + p.pc > KERNEL_LIMIT:
        raise ValueError(f"kernel image exceeded its PMP page: {BASE + p.pc:#x}")
    pad_to(p, USER_TEXT)

    p.label("user_main")
    absolute(p, 2, USER_STACK)

    # Legal U-mode RW accesses prove the data page and stack are usable.
    p.la(5, "marker")
    p.addi(6, 0, MARKER_BYTE)
    p.sb(6, 5, 0)
    p.lbu(7, 5, 0)
    p.bne(6, 7, "user_fail_data")
    p.addi(6, 0, STACK_WORD)
    p.sw(6, 2, 0)
    p.lw(7, 2, 0)
    p.bne(6, 7, "user_fail_data")

    p.la(10, "message")
    p.addi(11, 0, len(MESSAGE))
    p.addi(17, 0, SYS_WRITE)
    ecall(p)
    p.addi(5, 0, len(MESSAGE))
    p.bne(10, 5, "user_fail_write")

    p.addi(17, 0, SYS_GET_TICKS)
    ecall(p)
    p.beq(10, 0, "user_fail_ticks")

    # Trap entry preserves no GPRs in hardware. This minimal handler deliberately
    # uses temporary registers instead of building a full trap frame, so every
    # attack rematerializes its target after the preceding M-mode handler.
    absolute(p, 5, UART)
    p.addi(6, 0, ATTACK_BYTE)
    p.label("attack_uart_store")
    p.sb(6, 5, 0)

    absolute(p, 5, BASE)
    p.label("attack_kernel_load")
    p.lbu(6, 5, 0)

    absolute(p, 5, BASE)
    p.label("attack_kernel_store")
    p.sb(6, 5, 0)

    absolute(p, 5, USER_TEXT)
    p.label("attack_text_store")
    p.sw(6, 5, 0)

    p.la(10, "after_kernel_execute")
    p.la(5, "forbidden_kernel_target")
    p.label("attack_kernel_execute")
    p.jalr(0, 5, 0)
    p.label("after_kernel_execute")

    p.la(10, "after_data_execute")
    p.la(5, "forbidden_data_target")
    p.label("attack_data_execute")
    p.jalr(0, 5, 0)
    p.label("after_data_execute")

    p.addi(10, 0, 0)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    p.label("user_fail_data")
    p.addi(10, 0, 12)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    p.label("user_fail_write")
    p.addi(10, 0, 10)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    p.label("user_fail_ticks")
    p.addi(10, 0, 11)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    if BASE + p.pc > USER_DATA:
        raise ValueError(f"user text exceeded its PMP page: {BASE + p.pc:#x}")
    pad_to(p, USER_DATA)

    p.label("message")
    append_bytes(p, MESSAGE)
    p.label("marker")
    append_bytes(p, bytes((0, 0, 0, 0)))

    # Valid instructions deliberately stored in a non-executable RW page. If X
    # permission leaks, they request an early nonzero exit.
    p.label("forbidden_data_target")
    p.addi(10, 0, 91)
    p.addi(17, 0, SYS_EXIT)
    ecall(p)
    p.ebreak()

    if BASE + p.pc > USER_LIMIT:
        raise ValueError(f"user data exceeded its PMP page: {BASE + p.pc:#x}")
    return p


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/rv32imu-pmp/software")
    output.mkdir(parents=True, exist_ok=True)

    program = build_image()
    image = program.image()
    image_path = output / "pmp-isolation.bin"
    image_path.write_bytes(image)

    digest = hashlib.sha256(image).hexdigest()
    (output / "manifest.txt").write_text(
        "\n".join(
            (
                "march=rv32im_zicsr",
                "mabi=ilp32",
                "privileges=MU",
                "pmp_entries=4",
                "pmp_mode=TOR",
                f"pmpcfg0=0x{PMPCFG0_VALUE:08x}",
                f"kernel_region=0x{BASE:08x}-0x{KERNEL_LIMIT:08x}:---",
                f"user_text_region=0x{USER_TEXT:08x}-0x{USER_DATA:08x}:r-x",
                f"user_data_region=0x{USER_DATA:08x}-0x{USER_LIMIT:08x}:rw-",
                "user_default=deny",
                f"expected_fault_stages={EXPECTED_FAULT_STAGES}",
                f"message={MESSAGE.decode('ascii').rstrip()}",
                f"message_bytes={len(MESSAGE)}",
                f"attack_byte=0x{ATTACK_BYTE:02x}",
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
    print(f"pmp-isolation {len(image)} {len(image) // 4} {digest}")


if __name__ == "__main__":
    main()
