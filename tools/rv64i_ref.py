from __future__ import annotations

from dataclasses import dataclass, field

MASK64 = (1 << 64) - 1
RAM_BASE = 0x8000_0000
UART = 0x1000_0000


def sext(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return ((value & (sign - 1)) - (value & sign)) & MASK64


@dataclass
class State:
    pc: int = RAM_BASE
    regs: list[int] = field(default_factory=lambda: [0] * 32)
    memory: bytearray = field(default_factory=lambda: bytearray(4096))
    uart: str = ""
    halted: bool = False
    commits: int = 0

    def load_program(self, image: bytes) -> None:
        self.memory[: len(image)] = image

    def read(self, address: int, size: int) -> int:
        offset = address - RAM_BASE
        if offset < 0 or offset + size > len(self.memory):
            raise ValueError(f"read outside RAM: 0x{address:x}")
        return int.from_bytes(self.memory[offset : offset + size], "little")

    def write(self, address: int, value: int, size: int) -> None:
        if address == UART:
            self.uart += chr(value & 0xFF)
            return
        offset = address - RAM_BASE
        if offset < 0 or offset + size > len(self.memory):
            raise ValueError(f"write outside RAM: 0x{address:x}")
        self.memory[offset : offset + size] = value.to_bytes(8, "little")[:size]


def step(state: State) -> None:
    inst = state.read(state.pc, 4)
    opcode = inst & 0x7F
    rd = (inst >> 7) & 0x1F
    funct3 = (inst >> 12) & 7
    rs1 = (inst >> 15) & 0x1F
    rs2 = (inst >> 20) & 0x1F
    funct7 = (inst >> 25) & 0x7F
    next_pc = (state.pc + 4) & MASK64
    write_rd: int | None = None

    if opcode == 0x37:
        write_rd = sext(inst & 0xFFFFF000, 32)
    elif opcode == 0x13 and funct3 == 0:
        imm = sext(inst >> 20, 12)
        write_rd = (state.regs[rs1] + imm) & MASK64
    elif opcode == 0x33 and funct3 == 0 and funct7 == 0:
        write_rd = (state.regs[rs1] + state.regs[rs2]) & MASK64
    elif opcode == 0x23 and funct3 == 0:
        imm = ((inst >> 25) << 5) | ((inst >> 7) & 0x1F)
        address = (state.regs[rs1] + sext(imm, 12)) & MASK64
        state.write(address, state.regs[rs2], 1)
    elif inst == 0x00100073:
        state.halted = True
    else:
        raise ValueError(f"unsupported instruction 0x{inst:08x} at 0x{state.pc:x}")

    if write_rd is not None and rd != 0:
        state.regs[rd] = write_rd
    state.regs[0] = 0
    state.pc = next_pc
    state.commits += 1


def run(image: bytes, max_steps: int = 100) -> State:
    state = State()
    state.load_program(image)
    for _ in range(max_steps):
        if state.halted:
            return state
        step(state)
    raise TimeoutError("reference model did not halt")
