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


def build_programs() -> dict[str, tuple[Program, int]]:
    return {
        "forwarding": (forwarding(), 0),
        "load_use": (load_use(), 3),
        "branch_flush": (branch_flush(), 0),
        "jal_jalr": (jal_jalr(), 0),
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
