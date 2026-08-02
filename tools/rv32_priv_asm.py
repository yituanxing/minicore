#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass

from rv64_asm import Program, i_type, u_type


def csr_type(address: int, source: int, funct3: int, rd: int) -> int:
    if not 0 <= address < (1 << 12):
        raise ValueError(f"invalid CSR address: {address:#x}")
    if not 0 <= source < 32 or not 0 <= rd < 32:
        raise ValueError("CSR register fields must name x0..x31")
    return (
        (address << 20)
        | (source << 15)
        | ((funct3 & 0x7) << 12)
        | (rd << 7)
        | 0x73
    )


@dataclass(frozen=True)
class AddressFixup:
    index: int
    label: str
    rd: int


class PrivilegedProgram(Program):
    """Small deterministic RV32/RV64 image builder with Zicsr and MRET."""

    def __init__(self) -> None:
        super().__init__()
        self.address_fixups: list[AddressFixup] = []

    def la(self, rd: int, label: str) -> None:
        """Emit a near AUIPC+ADDI address materialization."""
        self.address_fixups.append(AddressFixup(len(self.words), label, rd))
        self.emit(0)
        self.emit(0)

    def csrrw(self, rd: int, address: int, rs1: int) -> None:
        self.emit(csr_type(address, rs1, 1, rd))

    def csrrs(self, rd: int, address: int, rs1: int = 0) -> None:
        self.emit(csr_type(address, rs1, 2, rd))

    def csrw(self, address: int, rs1: int) -> None:
        self.csrrw(0, address, rs1)

    def csrr(self, rd: int, address: int) -> None:
        self.csrrs(rd, address, 0)

    def mret(self) -> None:
        self.emit(0x30200073)

    def resolve(self) -> list[int]:
        result = super().resolve()
        for fixup in self.address_fixups:
            if fixup.label not in self.labels:
                raise ValueError(f"undefined address label: {fixup.label}")
            offset = (self.labels[fixup.label] - fixup.index) * 4
            high = (offset + 0x800) >> 12
            low = offset - (high << 12)
            if not -2048 <= low <= 2047:
                raise ValueError(f"address low immediate out of range: {low}")
            result[fixup.index] = u_type(0x17, fixup.rd, high & 0xFFFFF)
            result[fixup.index + 1] = i_type(0x13, fixup.rd, 0, fixup.rd, low)
        return result


def li32(program: Program, rd: int, value: int) -> None:
    value &= 0xFFFFFFFF
    signed_value = value if value < 0x80000000 else value - (1 << 32)
    if -2048 <= signed_value <= 2047:
        program.addi(rd, 0, signed_value)
        return

    high = (value + 0x800) >> 12
    low = value - (high << 12)
    if low >= 2048:
        low -= 4096
    program.lui(rd, high & 0xFFFFF)
    program.addi(rd, rd, low)
