#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

from make_regressions import emit_exit
from rv64_asm import Program, i_type, r_type


def emit_r(program: Program, rd: int, funct3: int, rs1: int, rs2: int, funct7: int = 0) -> None:
    program.emit(r_type(0x33, rd, funct3, rs1, rs2, funct7))


def emit_i(program: Program, rd: int, funct3: int, rs1: int, immediate: int) -> None:
    program.emit(i_type(0x13, rd, funct3, rs1, immediate))


def alu_logic() -> Program:
    p = Program()
    p.addi(1, 0, 0x55)
    p.addi(2, 0, 0x0F)

    emit_r(p, 3, 4, 1, 2)   # XOR = 0x5a
    p.addi(20, 0, 0x5A)
    p.bne(3, 20, "fail")

    emit_r(p, 4, 6, 1, 2)   # OR = 0x5f
    p.addi(20, 0, 0x5F)
    p.bne(4, 20, "fail")

    emit_r(p, 5, 7, 1, 2)   # AND = 0x05
    p.addi(20, 0, 5)
    p.bne(5, 20, "fail")

    emit_i(p, 6, 4, 1, 0x0F)  # XORI
    p.addi(20, 0, 0x5A)
    p.bne(6, 20, "fail")

    emit_i(p, 7, 6, 1, 0x0F)  # ORI
    p.addi(20, 0, 0x5F)
    p.bne(7, 20, "fail")

    emit_i(p, 8, 7, 1, 0x0F)  # ANDI
    p.addi(20, 0, 5)
    p.bne(8, 20, "fail")

    p.sub(9, 1, 2)
    p.addi(20, 0, 0x46)
    p.bne(9, 20, "fail")

    p.addi(10, 0, -1)
    p.addi(11, 0, 1)
    emit_r(p, 12, 2, 10, 11)  # SLT: -1 < 1
    p.bne(12, 11, "fail")
    emit_r(p, 13, 3, 10, 11)  # SLTU: max < 1 is false
    p.bne(13, 0, "fail")
    emit_i(p, 14, 2, 10, 0)   # SLTI: -1 < 0
    p.bne(14, 11, "fail")
    emit_i(p, 15, 3, 11, -1)  # SLTIU: 1 < UINT64_MAX
    p.bne(15, 11, "fail")

    p.addi(16, 0, 1)
    p.addi(17, 0, 63)
    emit_r(p, 18, 1, 16, 17)  # SLL
    emit_r(p, 19, 5, 18, 17)  # SRL
    p.bne(19, 16, "fail")
    emit_r(p, 20, 5, 18, 17, 0x20)  # SRA
    p.bne(20, 10, "fail")

    p.slli(21, 16, 63)
    p.bne(21, 18, "fail")
    p.srli(22, 21, 63)
    p.bne(22, 16, "fail")
    p.srai(23, 21, 63)
    p.bne(23, 10, "fail")

    p.addi(24, 0, 2047)
    p.addi(25, 24, -2048)
    p.bne(25, 10, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 9)
    return p


def pc_relative() -> Program:
    p = Program()

    p.auipc(10, 0)         # reset PC
    p.jal(1, "jal_target")
    p.jal(0, "fail")      # wrong path
    p.label("jal_target")
    p.addi(11, 10, 8)     # JAL link = reset PC + 8
    p.bne(1, 11, "fail")

    p.auipc(12, 0)         # P
    p.addi(16, 12, 0)      # preserve P
    p.addi(12, 12, 21)     # target address P + 20, with low bit set
    p.jalr(13, 12, 0)      # link = P + 16
    p.jal(0, "fail")      # wrong path at P + 16
    p.label("jalr_target") # P + 20
    p.addi(17, 16, 16)
    p.bne(13, 17, "fail")

    p.auipc(18, 1)         # Q + 0x1000
    p.auipc(19, 0)         # Q + 4
    p.lui(20, 1)           # 0x1000
    p.add(21, 19, 20)
    p.addi(21, 21, -4)     # Q + 0x1000
    p.bne(18, 21, "fail")

    p.lui(22, 0x80000)
    p.addi(23, 0, 1)
    p.slliw(23, 23, 31)
    p.bne(22, 23, "fail")

    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 10)
    return p


def fence_retire() -> Program:
    p = Program()
    p.addi(1, 0, 10)
    p.emit(0x0000000F)     # FENCE with empty predecessor/successor sets
    p.addi(2, 1, 5)
    p.emit(0x0000100F)     # FENCE.I
    p.addi(3, 2, -15)
    p.bne(3, 0, "fail")
    emit_exit(p, 0)
    p.label("fail")
    emit_exit(p, 11)
    return p


def build_programs() -> dict[str, Program]:
    return {
        "alu_logic": alu_logic(),
        "pc_relative": pc_relative(),
        "fence_retire": fence_retire(),
    }


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/completion-regressions")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, program in build_programs().items():
        image = program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(f"{name}\n")
        print(f"wrote {len(image):4d} bytes: {path}")

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
