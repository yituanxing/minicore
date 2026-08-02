#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

from rv32_priv_asm import PrivilegedProgram, li32

BASE = 0x80000000
UART = 0x10000000
EXIT = 0x10000008
MTIME = 0x0200BFF8

MSTATUS = 0x300
MTVEC = 0x305
MEPC = 0x341
MCAUSE = 0x342

SYS_WRITE = 1
SYS_GET_TICKS = 2
SYS_EXIT = 3

MESSAGE = b"hello from U-mode via SYS_WRITE\n"


def absolute(program: PrivilegedProgram, rd: int, value: int) -> None:
    li32(program, rd, value)


def ecall(program: PrivilegedProgram) -> None:
    program.emit(0x00000073)


def append_bytes(program: PrivilegedProgram, data: bytes) -> None:
    for offset in range(0, len(data), 4):
        chunk = data[offset : offset + 4].ljust(4, b"\0")
        program.emit(int.from_bytes(chunk, "little"))


def emit_machine_exit(program: PrivilegedProgram, code_register: int) -> None:
    absolute(program, 5, EXIT)
    program.sw(code_register, 5, 0)
    program.ebreak()


def build_image() -> PrivilegedProgram:
    p = PrivilegedProgram()

    # Minimal M-mode bootstrap. mstatus.MPP=U is requested with zero, then MRET
    # changes the architectural current privilege and starts the user program.
    p.la(5, "trap_handler")
    p.csrw(MTVEC, 5)
    p.la(5, "user_main")
    p.csrw(MEPC, 5)
    p.csrw(MSTATUS, 0)
    p.mret()

    p.label("bootstrap_returned")
    p.addi(10, 0, 80)
    emit_machine_exit(p, 10)

    p.label("trap_handler")
    p.csrr(5, MCAUSE)
    p.addi(6, 0, 8)
    p.bne(5, 6, "bad_trap")

    p.addi(5, 0, SYS_WRITE)
    p.beq(17, 5, "sys_write")
    p.addi(5, 0, SYS_GET_TICKS)
    p.beq(17, 5, "sys_get_ticks")
    p.addi(5, 0, SYS_EXIT)
    p.beq(17, 5, "sys_exit")
    p.addi(10, 0, 81)
    emit_machine_exit(p, 10)

    p.label("sys_write")
    # a0=user pointer, a1=length. Only the M-mode handler touches UART MMIO.
    p.addi(28, 11, 0)
    absolute(p, 7, UART)
    p.label("write_loop")
    p.beq(11, 0, "write_done")
    p.lbu(6, 10, 0)
    p.sb(6, 7, 0)
    p.addi(10, 10, 1)
    p.addi(11, 11, -1)
    p.jal(0, "write_loop")
    p.label("write_done")
    p.addi(10, 28, 0)
    p.jal(0, "return_to_user")

    p.label("sys_get_ticks")
    absolute(p, 5, MTIME)
    p.lw(10, 5, 0)
    p.jal(0, "return_to_user")

    p.label("sys_exit")
    emit_machine_exit(p, 10)

    p.label("bad_trap")
    p.addi(10, 0, 82)
    emit_machine_exit(p, 10)

    p.label("return_to_user")
    p.csrr(5, MEPC)
    p.addi(5, 5, 4)
    p.csrw(MEPC, 5)
    p.mret()

    p.label("user_main")
    p.la(10, "message")
    p.addi(11, 0, len(MESSAGE))
    p.addi(17, 0, SYS_WRITE)
    ecall(p)
    p.addi(5, 0, len(MESSAGE))
    p.bne(10, 5, "user_fail_write")

    p.addi(17, 0, SYS_GET_TICKS)
    ecall(p)
    p.beq(10, 0, "user_fail_ticks")

    p.addi(10, 0, 0)
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

    p.label("message")
    append_bytes(p, MESSAGE)
    return p


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/rv32imu-syscalls/software")
    output.mkdir(parents=True, exist_ok=True)

    program = build_image()
    image = program.image()
    image_path = output / "user-syscalls.bin"
    image_path.write_bytes(image)

    digest = hashlib.sha256(image).hexdigest()
    (output / "manifest.txt").write_text(
        "\n".join(
            (
                "march=rv32im_zicsr",
                "mabi=ilp32",
                "privileges=MU",
                f"message={MESSAGE.decode('ascii').rstrip()}",
                f"message_bytes={len(MESSAGE)}",
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
    print(f"user-syscalls {len(image)} {len(image) // 4} {digest}")


if __name__ == "__main__":
    main()
