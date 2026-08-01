#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

from rv64_asm import Program

EXIT_REGISTER = 31
CODE_REGISTER = 30


def emit_exit(program: Program, code: int) -> None:
    program.lui(EXIT_REGISTER, 0x10000)
    program.addi(EXIT_REGISTER, EXIT_REGISTER, 8)
    program.addi(CODE_REGISTER, 0, code)
    program.sd(CODE_REGISTER, EXIT_REGISTER, 0)
    program.ebreak()


def forwarding() -> Program:
    p = Program()
    p.addi(1, 0, 7)
    p.addi(2, 1, 5)       # EX/MEM forwarding: x2 = 12
    p.add(3, 2, 1)        # mixed EX/MEM + MEM/WB forwarding: x3 = 19
    p.addi(4, 3, -19)
    p.bne(4, 0, "fail")
    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 1)
    return p


def load_use() -> Program:
    p = Program()
    p.auipc(10, 0)
    p.addi(10, 10, 0x100)
    p.addi(1, 0, 21)
    p.sd(1, 10, 0)
    p.ld(2, 10, 0)
    p.add(3, 2, 2)        # immediate consumer requires one load-use bubble
    p.addi(4, 3, -42)
    p.bne(4, 0, "fail")
    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 2)
    return p


def branch_flush() -> Program:
    p = Program()
    p.auipc(10, 0)
    p.addi(10, 10, 0x180)
    p.sd(0, 10, 0)
    p.beq(0, 0, "target")
    p.addi(1, 0, 99)      # wrong path
    p.sd(1, 10, 0)        # wrong-path store must never become visible
    p.label("target")
    p.ld(2, 10, 0)
    p.bne(2, 0, "fail")
    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 3)
    return p


def jal_jalr() -> Program:
    p = Program()
    p.jal(1, "function")
    p.label("return")
    p.addi(6, 0, 7)
    p.jal(0, "check")
    p.label("function")
    p.addi(5, 0, 35)
    p.jalr(0, 1, 0)
    p.label("check")
    p.add(7, 5, 6)
    p.addi(8, 7, -42)
    p.bne(8, 0, "fail")
    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 4)
    return p


def memory_widths() -> Program:
    p = Program()
    p.auipc(10, 0)
    p.addi(10, 10, 0x300)
    p.addi(1, 0, -1)

    p.sb(1, 10, 0)
    p.sh(1, 10, 8)
    p.sw(1, 10, 16)
    p.sd(1, 10, 24)

    p.lb(2, 10, 0)
    p.bne(2, 1, "fail")
    p.lh(3, 10, 8)
    p.bne(3, 1, "fail")
    p.lw(4, 10, 16)
    p.bne(4, 1, "fail")
    p.ld(5, 10, 24)
    p.bne(5, 1, "fail")

    p.addi(20, 0, 255)
    p.lbu(6, 10, 0)
    p.bne(6, 20, "fail")

    p.lui(21, 0x10)
    p.addi(21, 21, -1)    # 65535
    p.lhu(7, 10, 8)
    p.bne(7, 21, "fail")

    p.addi(22, 0, -1)
    p.srli(22, 22, 32)    # 0x00000000ffffffff
    p.lwu(8, 10, 16)
    p.bne(8, 22, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 5)
    return p


def word_operations() -> Program:
    p = Program()
    p.lui(1, 0x80000)
    p.addi(1, 1, -1)      # low word = 0x7fffffff
    p.lui(2, 0x80000)     # sign-extended 0xffffffff80000000
    p.addi(3, 0, 1)
    p.addi(7, 0, -1)

    p.addiw(4, 1, 1)
    p.bne(4, 2, "fail")
    p.slliw(5, 3, 31)
    p.bne(5, 2, "fail")
    p.srliw(6, 2, 31)
    p.bne(6, 3, "fail")
    p.sraiw(8, 2, 31)
    p.bne(8, 7, "fail")

    p.addw(9, 1, 3)
    p.bne(9, 2, "fail")
    p.subw(10, 3, 2)
    p.addi(11, 2, 1)      # 0xffffffff80000001
    p.bne(10, 11, "fail")

    p.addi(12, 0, 31)
    p.sllw(13, 3, 12)
    p.bne(13, 2, "fail")
    p.srlw(14, 2, 12)
    p.bne(14, 3, "fail")
    p.sraw(15, 2, 12)
    p.bne(15, 7, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 6)
    return p


def branch_matrix() -> Program:
    p = Program()
    p.addi(1, 0, -1)
    p.addi(2, 0, 1)

    for name, branch, rs1, rs2 in (
        ("beq_taken", p.beq, 2, 2),
        ("bne_taken", p.bne, 1, 2),
        ("blt_taken", p.blt, 1, 2),
        ("bge_taken", p.bge, 2, 1),
        ("bltu_taken", p.bltu, 2, 1),
        ("bgeu_taken", p.bgeu, 1, 2),
    ):
        branch(rs1, rs2, name)
        p.jal(0, "fail")
        p.label(name)

    p.beq(1, 2, "fail")
    p.bne(2, 2, "fail")
    p.blt(2, 1, "fail")
    p.bge(1, 2, "fail")
    p.bltu(1, 2, "fail")
    p.bgeu(2, 1, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 7)
    return p


def x0_writeback() -> Program:
    p = Program()
    p.addi(0, 0, 123)     # must be discarded
    p.addi(1, 0, 0)
    p.bne(0, 1, "fail")

    p.addi(5, 0, 11)      # producer
    p.addi(2, 0, 2)       # independent instruction 1
    p.addi(3, 0, 3)       # independent instruction 2
    p.addi(6, 5, 1)       # reads x5 while producer writes back
    p.addi(7, 0, 12)
    p.bne(6, 7, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 8)
    return p


def build_programs() -> dict[str, tuple[Program, int]]:
    return {
        "forwarding": (forwarding(), 0),
        "load_use": (load_use(), 3),
        "branch_flush": (branch_flush(), 0),
        "jal_jalr": (jal_jalr(), 0),
        "memory_widths": (memory_widths(), 4),
        "word_operations": (word_operations(), 0),
        "branch_matrix": (branch_matrix(), 0),
        "x0_writeback": (x0_writeback(), 0),
    }


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/regressions")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, (program, stall_period) in build_programs().items():
        image = program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(f"{name} {stall_period}\n")
        print(f"wrote {len(image):4d} bytes: {path}")

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
