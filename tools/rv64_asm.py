#!/usr/bin/env python3
from __future__ import annotations

import struct
from dataclasses import dataclass


def _signed(value: int, bits: int) -> int:
    minimum = -(1 << (bits - 1))
    maximum = (1 << (bits - 1)) - 1
    if not minimum <= value <= maximum:
        raise ValueError(f"signed {bits}-bit immediate out of range: {value}")
    return value & ((1 << bits) - 1)


def _reg(value: int) -> int:
    if not 0 <= value < 32:
        raise ValueError(f"invalid register x{value}")
    return value


def r_type(opcode: int, rd: int, funct3: int, rs1: int, rs2: int, funct7: int = 0) -> int:
    return (
        ((funct7 & 0x7F) << 25)
        | (_reg(rs2) << 20)
        | (_reg(rs1) << 15)
        | ((funct3 & 0x7) << 12)
        | (_reg(rd) << 7)
        | (opcode & 0x7F)
    )


def i_type(opcode: int, rd: int, funct3: int, rs1: int, immediate: int) -> int:
    imm = _signed(immediate, 12)
    return (imm << 20) | (_reg(rs1) << 15) | ((funct3 & 0x7) << 12) | (_reg(rd) << 7) | opcode


def s_type(opcode: int, funct3: int, rs1: int, rs2: int, immediate: int) -> int:
    imm = _signed(immediate, 12)
    return (
        ((imm >> 5) << 25)
        | (_reg(rs2) << 20)
        | (_reg(rs1) << 15)
        | ((funct3 & 0x7) << 12)
        | ((imm & 0x1F) << 7)
        | opcode
    )


def b_type(funct3: int, rs1: int, rs2: int, offset: int) -> int:
    if offset % 2:
        raise ValueError(f"branch offset must be two-byte aligned: {offset}")
    imm = _signed(offset, 13)
    return (
        (((imm >> 12) & 1) << 31)
        | (((imm >> 5) & 0x3F) << 25)
        | (_reg(rs2) << 20)
        | (_reg(rs1) << 15)
        | ((funct3 & 0x7) << 12)
        | (((imm >> 1) & 0xF) << 8)
        | (((imm >> 11) & 1) << 7)
        | 0x63
    )


def u_type(opcode: int, rd: int, immediate20: int) -> int:
    if not 0 <= immediate20 < (1 << 20):
        raise ValueError(f"20-bit immediate out of range: {immediate20}")
    return (immediate20 << 12) | (_reg(rd) << 7) | opcode


def j_type(rd: int, offset: int) -> int:
    if offset % 2:
        raise ValueError(f"jump offset must be two-byte aligned: {offset}")
    imm = _signed(offset, 21)
    return (
        (((imm >> 20) & 1) << 31)
        | (((imm >> 1) & 0x3FF) << 21)
        | (((imm >> 11) & 1) << 20)
        | (((imm >> 12) & 0xFF) << 12)
        | (_reg(rd) << 7)
        | 0x6F
    )


@dataclass(frozen=True)
class Fixup:
    kind: str
    index: int
    label: str
    rd: int = 0
    funct3: int = 0
    rs1: int = 0
    rs2: int = 0


class Program:
    def __init__(self) -> None:
        self.words: list[int] = []
        self.labels: dict[str, int] = {}
        self.fixups: list[Fixup] = []

    @property
    def pc(self) -> int:
        return len(self.words) * 4

    def label(self, name: str) -> None:
        if name in self.labels:
            raise ValueError(f"duplicate label: {name}")
        self.labels[name] = len(self.words)

    def emit(self, word: int) -> None:
        self.words.append(word & 0xFFFFFFFF)

    def lui(self, rd: int, immediate20: int) -> None:
        self.emit(u_type(0x37, rd, immediate20))

    def auipc(self, rd: int, immediate20: int = 0) -> None:
        self.emit(u_type(0x17, rd, immediate20))

    def addi(self, rd: int, rs1: int, immediate: int) -> None:
        self.emit(i_type(0x13, rd, 0, rs1, immediate))

    def slli(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 64:
            raise ValueError(f"invalid RV64 shift: {shift}")
        self.emit(i_type(0x13, rd, 1, rs1, shift))

    def srli(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 64:
            raise ValueError(f"invalid RV64 shift: {shift}")
        self.emit(i_type(0x13, rd, 5, rs1, shift))

    def srai(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 64:
            raise ValueError(f"invalid RV64 shift: {shift}")
        self.emit(i_type(0x13, rd, 5, rs1, 0x400 | shift))

    def addiw(self, rd: int, rs1: int, immediate: int) -> None:
        self.emit(i_type(0x1B, rd, 0, rs1, immediate))

    def slliw(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 32:
            raise ValueError(f"invalid RV64W shift: {shift}")
        self.emit(i_type(0x1B, rd, 1, rs1, shift))

    def srliw(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 32:
            raise ValueError(f"invalid RV64W shift: {shift}")
        self.emit(i_type(0x1B, rd, 5, rs1, shift))

    def sraiw(self, rd: int, rs1: int, shift: int) -> None:
        if not 0 <= shift < 32:
            raise ValueError(f"invalid RV64W shift: {shift}")
        self.emit(i_type(0x1B, rd, 5, rs1, 0x400 | shift))

    def add(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x33, rd, 0, rs1, rs2, 0))

    def sub(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x33, rd, 0, rs1, rs2, 0x20))

    def addw(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x3B, rd, 0, rs1, rs2, 0))

    def subw(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x3B, rd, 0, rs1, rs2, 0x20))

    def sllw(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x3B, rd, 1, rs1, rs2, 0))

    def srlw(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x3B, rd, 5, rs1, rs2, 0))

    def sraw(self, rd: int, rs1: int, rs2: int) -> None:
        self.emit(r_type(0x3B, rd, 5, rs1, rs2, 0x20))

    def _load(self, rd: int, rs1: int, funct3: int, immediate: int = 0) -> None:
        self.emit(i_type(0x03, rd, funct3, rs1, immediate))

    def lb(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 0, immediate)

    def lh(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 1, immediate)

    def lw(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 2, immediate)

    def ld(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 3, immediate)

    def lbu(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 4, immediate)

    def lhu(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 5, immediate)

    def lwu(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self._load(rd, rs1, 6, immediate)

    def _store(self, rs2: int, rs1: int, funct3: int, immediate: int = 0) -> None:
        self.emit(s_type(0x23, funct3, rs1, rs2, immediate))

    def sb(self, rs2: int, rs1: int, immediate: int = 0) -> None:
        self._store(rs2, rs1, 0, immediate)

    def sh(self, rs2: int, rs1: int, immediate: int = 0) -> None:
        self._store(rs2, rs1, 1, immediate)

    def sw(self, rs2: int, rs1: int, immediate: int = 0) -> None:
        self._store(rs2, rs1, 2, immediate)

    def sd(self, rs2: int, rs1: int, immediate: int = 0) -> None:
        self._store(rs2, rs1, 3, immediate)

    def jalr(self, rd: int, rs1: int, immediate: int = 0) -> None:
        self.emit(i_type(0x67, rd, 0, rs1, immediate))

    def _branch(self, funct3: int, rs1: int, rs2: int, label: str) -> None:
        self.fixups.append(
            Fixup("branch", len(self.words), label, funct3=funct3, rs1=rs1, rs2=rs2)
        )
        self.emit(0)

    def beq(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(0, rs1, rs2, label)

    def bne(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(1, rs1, rs2, label)

    def blt(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(4, rs1, rs2, label)

    def bge(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(5, rs1, rs2, label)

    def bltu(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(6, rs1, rs2, label)

    def bgeu(self, rs1: int, rs2: int, label: str) -> None:
        self._branch(7, rs1, rs2, label)

    def jal(self, rd: int, label: str) -> None:
        self.fixups.append(Fixup("jump", len(self.words), label, rd=rd))
        self.emit(0)

    def ebreak(self) -> None:
        self.emit(0x00100073)

    def resolve(self) -> list[int]:
        result = list(self.words)
        for fixup in self.fixups:
            if fixup.label not in self.labels:
                raise ValueError(f"undefined label: {fixup.label}")
            offset = (self.labels[fixup.label] - fixup.index) * 4
            if fixup.kind == "branch":
                result[fixup.index] = b_type(fixup.funct3, fixup.rs1, fixup.rs2, offset)
            elif fixup.kind == "jump":
                result[fixup.index] = j_type(fixup.rd, offset)
            else:
                raise ValueError(f"unknown fixup kind: {fixup.kind}")
        return result

    def image(self) -> bytes:
        return b"".join(struct.pack("<I", word) for word in self.resolve())
