#!/usr/bin/env python3
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

from rv64_asm import Program

RESET_PC = 0x8000_0000
NO_VALUE = "-"


@dataclass(frozen=True)
class FaultCase:
    program: Program
    fault_index: int
    expected_commits: int
    stall_period: int = 0
    forbidden_rd: int | None = None
    expected_memory_address: int | None = None
    expected_memory_value: int | None = None

    def manifest_line(self, name: str) -> str:
        words = self.program.resolve()
        fault_pc = RESET_PC + self.fault_index * 4
        fault_inst = words[self.fault_index]
        forbidden = NO_VALUE if self.forbidden_rd is None else str(self.forbidden_rd)
        memory_address = (
            NO_VALUE
            if self.expected_memory_address is None
            else f"0x{self.expected_memory_address:x}"
        )
        memory_value = (
            NO_VALUE
            if self.expected_memory_value is None
            else f"0x{self.expected_memory_value:x}"
        )
        return (
            f"{name} {self.stall_period} 0x{fault_pc:x} 0x{fault_inst:08x} "
            f"{self.expected_commits} {forbidden} {memory_address} {memory_value}\n"
        )


def illegal_instruction() -> FaultCase:
    p = Program()
    p.lui(31, 0x10000)
    p.addi(30, 0, 1)
    fault_index = len(p.words)
    p.emit(0xFFFFFFFF)
    p.sd(30, 31, 8)   # immediately younger exit store must not escape
    p.addi(2, 0, 99)  # younger register write must not retire
    return FaultCase(p, fault_index, expected_commits=3, forbidden_rd=2)


def load_bus_fault() -> FaultCase:
    p = Program()
    p.lui(31, 0x10000)
    p.addi(30, 0, 1)
    p.lui(1, 0x10000)  # 0x10000000: MMIO only for writes, invalid as host RAM
    fault_index = len(p.words)
    p.ld(2, 1, 0)
    p.sd(30, 31, 8)   # immediately younger exit store must not escape
    p.addi(3, 0, 99)  # younger register write must not retire
    return FaultCase(
        p,
        fault_index,
        expected_commits=4,
        stall_period=3,
        forbidden_rd=3,
    )


def store_bus_fault() -> FaultCase:
    p = Program()
    p.auipc(10, 0)
    p.addi(10, 10, 0x200)
    p.sd(0, 10, 0)     # sentinel must remain zero

    p.addi(2, 0, 0x55)
    p.lui(1, 0x10000)
    p.addi(1, 1, 16)   # 0x10000010: neither RAM nor recognized MMIO
    fault_index = len(p.words)
    p.sd(2, 1, 0)

    p.sd(2, 10, 0)     # immediately younger valid-RAM store must be suppressed
    p.addi(3, 0, 99)   # younger register write must not retire
    return FaultCase(
        p,
        fault_index,
        expected_commits=7,
        stall_period=4,
        forbidden_rd=3,
        expected_memory_address=RESET_PC + 0x200,
        expected_memory_value=0,
    )


def build_cases() -> dict[str, FaultCase]:
    return {
        "illegal_instruction": illegal_instruction(),
        "load_bus_fault": load_bus_fault(),
        "store_bus_fault": store_bus_fault(),
    }


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/fault-regressions")
    output.mkdir(parents=True, exist_ok=True)

    manifest: list[str] = []
    for name, case in build_cases().items():
        image = case.program.image()
        path = output / f"{name}.bin"
        path.write_bytes(image)
        manifest.append(case.manifest_line(name))
        print(f"wrote {len(image):4d} bytes: {path}")

    (output / "manifest.txt").write_text("".join(manifest), encoding="utf-8")


if __name__ == "__main__":
    main()
