#!/usr/bin/env python3
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

from make_regressions import emit_exit
from rv64_asm import Program, i_type, r_type

DATA_BASE_OFFSET = 0x780
DATA_BASE_REGISTER = 28
GENERAL_REGISTERS = tuple(range(1, 28))
MEMORY_SLOTS = 16


@dataclass(frozen=True)
class GeneratedCase:
    name: str
    seed: int
    operations: int
    stall_period: int


CASES = (
    GeneratedCase("seed_a37e0001", 0xA37E0001, 192, 0),
    GeneratedCase("seed_a37e0002", 0xA37E0002, 192, 3),
    GeneratedCase("seed_a37e0003", 0xA37E0003, 192, 4),
    GeneratedCase("seed_a37e0004", 0xA37E0004, 192, 5),
    GeneratedCase("seed_a37e0005", 0xA37E0005, 192, 7),
)


class XorShift64:
    """Small fixed algorithm so images do not depend on Python's random module."""

    _MASK = (1 << 64) - 1

    def __init__(self, seed: int) -> None:
        self.state = seed & self._MASK
        if self.state == 0:
            raise ValueError("generated DiffTest seed must be nonzero")

    def next_u64(self) -> int:
        value = self.state
        value ^= (value << 13) & self._MASK
        value ^= value >> 7
        value ^= (value << 17) & self._MASK
        self.state = value & self._MASK
        return self.state

    def bounded(self, limit: int) -> int:
        if limit <= 0:
            raise ValueError("bounded limit must be positive")
        return self.next_u64() % limit

    def choose(self, values: tuple[int, ...]) -> int:
        return values[self.bounded(len(values))]

    def signed12(self) -> int:
        return self.bounded(1 << 12) - (1 << 11)


def emit_r(
    program: Program,
    rd: int,
    funct3: int,
    rs1: int,
    rs2: int,
    funct7: int = 0,
    opcode: int = 0x33,
) -> None:
    program.emit(r_type(opcode, rd, funct3, rs1, rs2, funct7))


def emit_i(
    program: Program,
    rd: int,
    funct3: int,
    rs1: int,
    immediate: int,
    opcode: int = 0x13,
) -> None:
    program.emit(i_type(opcode, rd, funct3, rs1, immediate))


def build_program(case: GeneratedCase) -> Program:
    rng = XorShift64(case.seed)
    program = Program()

    # Keep generated data well beyond the longest generated instruction image,
    # but inside a positive 12-bit AUIPC-relative offset.
    program.auipc(DATA_BASE_REGISTER, 0)
    program.addi(DATA_BASE_REGISTER, DATA_BASE_REGISTER, DATA_BASE_OFFSET)

    for register in GENERAL_REGISTERS:
        program.addi(register, 0, rng.signed12())

    for slot in range(MEMORY_SLOTS):
        program.sd(rng.choose(GENERAL_REGISTERS), DATA_BASE_REGISTER, slot * 8)

    last_destination = 1
    for _ in range(case.operations):
        selector = rng.bounded(36)
        rd = rng.choose(GENERAL_REGISTERS)
        rs1 = last_destination if rng.bounded(2) == 0 else rng.choose(GENERAL_REGISTERS)
        rs2 = rng.choose(GENERAL_REGISTERS)
        immediate = rng.signed12()
        shift64 = rng.bounded(64)
        shift32 = rng.bounded(32)
        slot_offset = rng.bounded(MEMORY_SLOTS) * 8

        if selector == 0:
            program.add(rd, rs1, rs2)
        elif selector == 1:
            program.sub(rd, rs1, rs2)
        elif selector == 2:
            emit_r(program, rd, 4, rs1, rs2)  # XOR
        elif selector == 3:
            emit_r(program, rd, 6, rs1, rs2)  # OR
        elif selector == 4:
            emit_r(program, rd, 7, rs1, rs2)  # AND
        elif selector == 5:
            emit_r(program, rd, 2, rs1, rs2)  # SLT
        elif selector == 6:
            emit_r(program, rd, 3, rs1, rs2)  # SLTU
        elif selector == 7:
            emit_r(program, rd, 1, rs1, rs2)  # SLL
        elif selector == 8:
            emit_r(program, rd, 5, rs1, rs2)  # SRL
        elif selector == 9:
            emit_r(program, rd, 5, rs1, rs2, 0x20)  # SRA
        elif selector == 10:
            program.addi(rd, rs1, immediate)
        elif selector == 11:
            emit_i(program, rd, 4, rs1, immediate)  # XORI
        elif selector == 12:
            emit_i(program, rd, 6, rs1, immediate)  # ORI
        elif selector == 13:
            emit_i(program, rd, 7, rs1, immediate)  # ANDI
        elif selector == 14:
            emit_i(program, rd, 2, rs1, immediate)  # SLTI
        elif selector == 15:
            emit_i(program, rd, 3, rs1, immediate)  # SLTIU
        elif selector == 16:
            program.slli(rd, rs1, shift64)
        elif selector == 17:
            program.srli(rd, rs1, shift64)
        elif selector == 18:
            program.srai(rd, rs1, shift64)
        elif selector == 19:
            program.addiw(rd, rs1, immediate)
        elif selector == 20:
            program.slliw(rd, rs1, shift32)
        elif selector == 21:
            program.srliw(rd, rs1, shift32)
        elif selector == 22:
            program.sraiw(rd, rs1, shift32)
        elif selector == 23:
            program.addw(rd, rs1, rs2)
        elif selector == 24:
            program.subw(rd, rs1, rs2)
        elif selector == 25:
            program.sllw(rd, rs1, rs2)
        elif selector == 26:
            program.srlw(rd, rs1, rs2)
        elif selector == 27:
            program.sraw(rd, rs1, rs2)
        elif selector == 28:
            program.sd(rs2, DATA_BASE_REGISTER, slot_offset)
            last_destination = rs2
            continue
        elif selector == 29:
            program.ld(rd, DATA_BASE_REGISTER, slot_offset)
        elif selector == 30:
            program.sw(rs2, DATA_BASE_REGISTER, slot_offset)
            program.lwu(rd, DATA_BASE_REGISTER, slot_offset)
        elif selector == 31:
            program.sh(rs2, DATA_BASE_REGISTER, slot_offset)
            program.lhu(rd, DATA_BASE_REGISTER, slot_offset)
        elif selector == 32:
            program.sb(rs2, DATA_BASE_REGISTER, slot_offset)
            program.lbu(rd, DATA_BASE_REGISTER, slot_offset)
        elif selector == 33:
            # Force an immediate load-use dependency in a generated stream.
            program.ld(rd, DATA_BASE_REGISTER, slot_offset)
            dependent = rng.choose(GENERAL_REGISTERS)
            program.add(dependent, rd, rs2)
            last_destination = dependent
            continue
        elif selector == 34:
            program.emit(0x0000000F if rng.bounded(2) == 0 else 0x0000100F)
            continue
        else:
            # Exercise x0 write suppression without changing generated state.
            program.addi(0, rs1, immediate)
            continue

        last_destination = rd

    emit_exit(program, 0)
    if program.pc >= DATA_BASE_OFFSET:
        raise ValueError(
            f"generated image overlaps data scratch: {program.pc:#x} >= {DATA_BASE_OFFSET:#x}"
        )
    return program


def build_cases() -> dict[str, tuple[GeneratedCase, Program]]:
    return {case.name: (case, build_program(case)) for case in CASES}


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/generated-difftest")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, (case, program) in build_cases().items():
        words = program.resolve()
        image = program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(
            f"{name} 0x{case.seed:016x} {case.stall_period} {case.operations} {len(words)}\n"
        )
        print(
            f"wrote {len(image):4d} bytes ({len(words):3d} words): {path} "
            f"seed=0x{case.seed:016x} stall={case.stall_period}"
        )

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
