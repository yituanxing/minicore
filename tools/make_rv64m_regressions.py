#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

from make_regressions import emit_exit
from rv64_asm import Program, r_type


def emit_m(
    program: Program,
    rd: int,
    funct3: int,
    rs1: int,
    rs2: int,
    *,
    word: bool = False,
) -> None:
    program.emit(r_type(0x3B if word else 0x33, rd, funct3, rs1, rs2, 0x01))


def expect_equal(
    program: Program,
    actual: int,
    expected: int,
    code: int,
    failures: list[tuple[str, int]],
) -> None:
    label = f"fail_{code}"
    program.bne(actual, expected, label)
    failures.append((label, code))


def finish(program: Program, failures: list[tuple[str, int]]) -> None:
    emit_exit(program, 0)
    for label, code in failures:
        program.label(label)
        emit_exit(program, code)


def multiply_variants() -> Program:
    p = Program()
    failures: list[tuple[str, int]] = []

    p.addi(1, 0, -2)
    p.addi(2, 0, 3)

    emit_m(p, 3, 0, 1, 2)  # MUL: -2 * 3 = -6
    p.addi(20, 0, -6)
    expect_equal(p, 3, 20, 1, failures)

    p.addi(4, 0, 1)
    p.slli(4, 4, 63)       # 0x8000000000000000

    emit_m(p, 5, 1, 1, 4)  # MULH: (-2) * (-2^63), high = 1
    p.addi(20, 0, 1)
    expect_equal(p, 5, 20, 2, failures)

    emit_m(p, 6, 2, 1, 4)  # MULHSU: (-2) * (2^63), high = -1
    p.addi(20, 0, -1)
    expect_equal(p, 6, 20, 3, failures)

    p.addi(7, 0, -1)
    p.addi(8, 0, 2)
    emit_m(p, 9, 3, 7, 8)  # MULHU: UINT64_MAX * 2, high = 1
    p.addi(20, 0, 1)
    expect_equal(p, 9, 20, 4, failures)

    # Back-to-back dependent multiplications exercise EX/MEM and MEM/WB paths.
    emit_m(p, 10, 0, 2, 2)   # 9
    emit_m(p, 11, 0, 10, 2)  # 27, immediate consumer of x10
    p.addi(20, 0, 27)
    expect_equal(p, 11, 20, 5, failures)

    finish(p, failures)
    return p


def divide_variants() -> Program:
    p = Program()
    failures: list[tuple[str, int]] = []

    p.addi(1, 0, -20)
    p.addi(2, 0, 3)

    emit_m(p, 3, 4, 1, 2)  # DIV = -6
    p.addi(20, 0, -6)
    expect_equal(p, 3, 20, 10, failures)

    emit_m(p, 4, 6, 1, 2)  # REM = -2
    p.addi(20, 0, -2)
    expect_equal(p, 4, 20, 11, failures)

    p.addi(5, 0, -1)
    p.addi(6, 0, 2)
    emit_m(p, 7, 5, 5, 6)  # DIVU(UINT64_MAX, 2)
    p.srli(20, 5, 1)
    expect_equal(p, 7, 20, 12, failures)

    emit_m(p, 8, 7, 5, 6)  # REMU(UINT64_MAX, 2) = 1
    p.addi(20, 0, 1)
    expect_equal(p, 8, 20, 13, failures)

    p.addi(9, 0, 1)
    p.slli(9, 9, 63)
    p.addi(9, 9, 5)        # 0x8000000000000005

    emit_m(p, 11, 4, 9, 0)  # DIV by zero = -1
    p.addi(20, 0, -1)
    expect_equal(p, 11, 20, 14, failures)

    emit_m(p, 12, 5, 9, 0)  # DIVU by zero = UINT64_MAX
    expect_equal(p, 12, 20, 15, failures)

    emit_m(p, 13, 6, 9, 0)  # REM by zero = dividend
    expect_equal(p, 13, 9, 16, failures)

    emit_m(p, 14, 7, 9, 0)  # REMU by zero = dividend
    expect_equal(p, 14, 9, 17, failures)

    p.addi(15, 0, 1)
    p.slli(15, 15, 63)       # signed minimum
    p.addi(16, 0, -1)

    emit_m(p, 17, 4, 15, 16)  # minimum / -1 = minimum
    expect_equal(p, 17, 15, 18, failures)

    emit_m(p, 18, 6, 15, 16)  # minimum % -1 = 0
    expect_equal(p, 18, 0, 19, failures)

    # Immediate use of a DIV result verifies normal forwarding behavior.
    emit_m(p, 21, 4, 1, 2)
    p.addi(22, 21, 6)
    expect_equal(p, 22, 0, 20, failures)

    finish(p, failures)
    return p


def word_variants() -> Program:
    p = Program()
    failures: list[tuple[str, int]] = []

    p.addi(1, 0, 1)
    p.slliw(1, 1, 30)       # 0x0000000040000000
    p.addi(2, 0, 2)
    emit_m(p, 3, 0, 1, 2, word=True)  # MULW => 0xffffffff80000000
    p.addi(20, 0, 1)
    p.slliw(20, 20, 31)
    expect_equal(p, 3, 20, 30, failures)

    p.addi(4, 0, -7)
    p.addi(5, 0, 2)
    emit_m(p, 6, 4, 4, 5, word=True)  # DIVW = -3
    p.addi(20, 0, -3)
    expect_equal(p, 6, 20, 31, failures)

    emit_m(p, 7, 6, 4, 5, word=True)  # REMW = -1
    p.addi(20, 0, -1)
    expect_equal(p, 7, 20, 32, failures)

    p.addi(8, 0, -1)
    emit_m(p, 9, 5, 8, 5, word=True)  # DIVUW(0xffffffff, 2)
    p.srliw(20, 8, 1)
    expect_equal(p, 9, 20, 33, failures)

    emit_m(p, 10, 7, 8, 5, word=True)  # REMUW = 1
    p.addi(20, 0, 1)
    expect_equal(p, 10, 20, 34, failures)

    p.addi(11, 0, 1)
    p.slliw(11, 11, 31)     # sign-extended 0x80000000

    emit_m(p, 13, 4, 11, 0, word=True)  # DIVW by zero = -1
    p.addi(20, 0, -1)
    expect_equal(p, 13, 20, 35, failures)

    emit_m(p, 14, 5, 11, 0, word=True)  # DIVUW by zero = 0xffffffff, sign-extended
    expect_equal(p, 14, 20, 36, failures)

    p.addi(15, 11, 1)
    emit_m(p, 16, 6, 15, 0, word=True)  # REMW by zero = low word dividend, sign-extended
    expect_equal(p, 16, 15, 37, failures)

    emit_m(p, 17, 7, 15, 0, word=True)  # REMUW by zero, same architectural extension
    expect_equal(p, 17, 15, 38, failures)

    p.addi(18, 0, -1)
    emit_m(p, 19, 4, 11, 18, word=True)  # minimum word / -1 = minimum word
    expect_equal(p, 19, 11, 39, failures)

    emit_m(p, 21, 6, 11, 18, word=True)  # minimum word % -1 = 0
    expect_equal(p, 21, 0, 40, failures)

    finish(p, failures)
    return p


def build_programs() -> dict[str, Program]:
    return {
        "rv64m_multiply": multiply_variants(),
        "rv64m_divide": divide_variants(),
        "rv64m_word": word_variants(),
    }


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/rv64m-regressions")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, program in build_programs().items():
        words = program.resolve()
        image = program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(f"{name} {len(words)}\n")
        print(f"wrote {len(image):4d} bytes ({len(words):3d} words): {path}")

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
