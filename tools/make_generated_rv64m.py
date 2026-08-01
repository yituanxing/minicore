#!/usr/bin/env python3
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

from make_generated_difftest import XorShift64
from make_regressions import emit_exit
from make_rv64m_regressions import emit_m
from rv64_asm import Program

DATA_BASE_OFFSET = 0x780
DATA_BASE_REGISTER = 28
GENERAL_REGISTERS = tuple(range(1, 28))
MEMORY_SLOTS = 16


@dataclass(frozen=True)
class GeneratedMCase:
    name: str
    seed: int
    operations: int
    stall_period: int


CASES = (
    GeneratedMCase("mseed_64d10001", 0x64D10001, 224, 0),
    GeneratedMCase("mseed_64d10002", 0x64D10002, 224, 3),
    GeneratedMCase("mseed_64d10003", 0x64D10003, 224, 4),
    GeneratedMCase("mseed_64d10004", 0x64D10004, 224, 5),
    GeneratedMCase("mseed_64d10005", 0x64D10005, 224, 7),
)

# opcode, funct3 pairs for the complete RV64M register instruction set.
M_ENCODINGS = (
    (False, 0), (False, 1), (False, 2), (False, 3),
    (False, 4), (False, 5), (False, 6), (False, 7),
    (True, 0), (True, 4), (True, 5), (True, 6), (True, 7),
)


def build_program(case: GeneratedMCase) -> Program:
    rng = XorShift64(case.seed)
    program = Program()

    program.auipc(DATA_BASE_REGISTER, 0)
    program.addi(DATA_BASE_REGISTER, DATA_BASE_REGISTER, DATA_BASE_OFFSET)

    for register in GENERAL_REGISTERS:
        program.addi(register, 0, rng.signed12())

    for slot in range(MEMORY_SLOTS):
        program.sd(rng.choose(GENERAL_REGISTERS), DATA_BASE_REGISTER, slot * 8)

    # Force every image to contain the complete M encoding family before the
    # random body. Destinations form a dependency chain so these instructions
    # also exercise normal EX/MEM and MEM/WB forwarding.
    last_destination = 1
    for word, funct3 in M_ENCODINGS:
        rd = rng.choose(GENERAL_REGISTERS)
        rs2 = rng.choose(GENERAL_REGISTERS)
        emit_m(program, rd, funct3, last_destination, rs2, word=word)
        last_destination = rd

    for _ in range(case.operations):
        selector = rng.bounded(24)
        rd = rng.choose(GENERAL_REGISTERS)
        rs1 = last_destination if rng.bounded(2) == 0 else rng.choose(GENERAL_REGISTERS)
        rs2 = rng.choose(GENERAL_REGISTERS)
        slot_offset = rng.bounded(MEMORY_SLOTS) * 8

        if selector < len(M_ENCODINGS):
            word, funct3 = M_ENCODINGS[selector]
            emit_m(program, rd, funct3, rs1, rs2, word=word)
            last_destination = rd
        elif selector == 13:
            program.add(rd, rs1, rs2)
            last_destination = rd
        elif selector == 14:
            program.sub(rd, rs1, rs2)
            last_destination = rd
        elif selector == 15:
            program.xor(rd, rs1, rs2)
            last_destination = rd
        elif selector == 16:
            program.addi(rd, rs1, rng.signed12())
            last_destination = rd
        elif selector == 17:
            program.addw(rd, rs1, rs2)
            last_destination = rd
        elif selector == 18:
            program.sd(rs2, DATA_BASE_REGISTER, slot_offset)
            last_destination = rs2
        elif selector == 19:
            program.ld(rd, DATA_BASE_REGISTER, slot_offset)
            last_destination = rd
        elif selector == 20:
            # A load immediately consumed by an M operation checks that the
            # existing load-use interlock feeds the new arithmetic correctly.
            program.ld(rd, DATA_BASE_REGISTER, slot_offset)
            dependent = rng.choose(GENERAL_REGISTERS)
            word, funct3 = M_ENCODINGS[rng.bounded(len(M_ENCODINGS))]
            emit_m(program, dependent, funct3, rd, rs2, word=word)
            last_destination = dependent
        elif selector == 21:
            program.emit(0x0000000F if rng.bounded(2) == 0 else 0x0000100F)
        elif selector == 22:
            # Keep divide-by-zero and signed-overflow cases naturally present:
            # x0 is a legal divisor and the result is still compared to NEMU.
            word, funct3 = M_ENCODINGS[4 + rng.bounded(4)]
            emit_m(program, rd, funct3, rs1, 0, word=word)
            last_destination = rd
        else:
            program.addi(0, rs1, rng.signed12())

    emit_exit(program, 0)
    if program.pc >= DATA_BASE_OFFSET:
        raise ValueError(
            f"generated RV64M image overlaps data scratch: "
            f"{program.pc:#x} >= {DATA_BASE_OFFSET:#x}"
        )
    return program


def build_cases() -> dict[str, tuple[GeneratedMCase, Program]]:
    return {case.name: (case, build_program(case)) for case in CASES}


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/generated-rv64m")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, (case, program) in build_cases().items():
        words = program.resolve()
        image = program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(
            f"{name} 0x{case.seed:016x} {case.stall_period} "
            f"{case.operations} {len(words)}\n"
        )
        print(
            f"wrote {len(image):4d} bytes ({len(words):3d} words): {path} "
            f"seed=0x{case.seed:016x} stall={case.stall_period}"
        )

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
