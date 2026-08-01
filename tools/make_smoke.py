#!/usr/bin/env python3
from __future__ import annotations

import struct
import sys
from pathlib import Path


def encode_u(opcode: int, rd: int, imm20: int) -> int:
    return ((imm20 & 0xFFFFF) << 12) | (rd << 7) | opcode


def encode_i(opcode: int, rd: int, funct3: int, rs1: int, imm: int) -> int:
    return ((imm & 0xFFF) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode


def encode_r(opcode: int, rd: int, funct3: int, rs1: int, rs2: int, funct7: int = 0) -> int:
    return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode


def encode_s(opcode: int, funct3: int, rs1: int, rs2: int, imm: int) -> int:
    value = imm & 0xFFF
    return ((value >> 5) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((value & 0x1F) << 7) | opcode


def build() -> bytes:
    words = [
        encode_u(0x37, 5, 0x10000),
        encode_i(0x13, 6, 0, 0, ord('A')),
        encode_s(0x23, 0, 5, 6, 0),
        encode_i(0x13, 1, 0, 0, 7),
        encode_i(0x13, 2, 0, 0, 5),
        encode_r(0x33, 3, 0, 1, 2),
        0x00100073,
    ]
    return b''.join(struct.pack('<I', word) for word in words)


def main() -> None:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/software/smoke.bin')
    output.parent.mkdir(parents=True, exist_ok=True)
    image = build()
    output.write_bytes(image)
    print(f'wrote {len(image)} bytes to {output}')


if __name__ == '__main__':
    main()
