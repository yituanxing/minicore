#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ctypes
import struct
from pathlib import Path


RAM_BASE = 0x80000000
TO_DUT = False
TO_REF = True


class State32(ctypes.Structure):
    _fields_ = [("gpr", ctypes.c_uint32 * 32), ("pc", ctypes.c_uint32)]


def configure(reference: ctypes.CDLL) -> None:
    reference.difftest_init.argtypes = []
    reference.difftest_init.restype = None
    reference.difftest_memcpy.argtypes = [
        ctypes.c_uint32,
        ctypes.c_void_p,
        ctypes.c_size_t,
        ctypes.c_bool,
    ]
    reference.difftest_memcpy.restype = None
    reference.difftest_regcpy.argtypes = [ctypes.c_void_p, ctypes.c_bool]
    reference.difftest_regcpy.restype = None
    reference.difftest_exec.argtypes = [ctypes.c_uint64]
    reference.difftest_exec.restype = None


def write_image(reference: ctypes.CDLL) -> None:
    # addi x15, x0, -1
    # bne  x15, x19, +8
    # nop
    # nop
    image = struct.pack("<IIII", 0xFFF00793, 0x01379463, 0x00000013, 0x00000013)
    buffer = ctypes.create_string_buffer(image)
    reference.difftest_memcpy(RAM_BASE, buffer, len(image), TO_REF)


def read_state(reference: ctypes.CDLL) -> State32:
    state = State32()
    reference.difftest_regcpy(ctypes.byref(state), TO_DUT)
    return state


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()

    reference = ctypes.CDLL(str(args.reference.resolve()))
    configure(reference)
    reference.difftest_init()
    write_image(reference)

    initial = State32()
    initial.pc = RAM_BASE
    # The injected -1 must acquire the same internal RV32 canonical host value
    # as the -1 produced by Spike's own ADDI below.
    initial.gpr[19] = 0xFFFFFFFF
    reference.difftest_regcpy(ctypes.byref(initial), TO_REF)

    reference.difftest_exec(1)
    after_addi = read_state(reference)
    if after_addi.pc != RAM_BASE + 4 or after_addi.gpr[15] != 0xFFFFFFFF:
        raise SystemExit(
            "ERROR: RV32 reference ADDI setup failed: "
            f"pc=0x{after_addi.pc:08x} x15=0x{after_addi.gpr[15]:08x}"
        )
    if after_addi.gpr[19] != 0xFFFFFFFF:
        raise SystemExit(
            f"ERROR: injected RV32 x19 changed unexpectedly: 0x{after_addi.gpr[19]:08x}"
        )

    reference.difftest_exec(1)
    after_branch = read_state(reference)
    expected = RAM_BASE + 8
    taken = RAM_BASE + 12
    if after_branch.pc != expected:
        raise SystemExit(
            "ERROR: RV32 regcpy canonicalization mismatch: "
            "x15=-1 produced by Spike and x19=-1 injected by regcpy compared unequal; "
            f"pc=0x{after_branch.pc:08x} expected=0x{expected:08x} taken=0x{taken:08x}"
        )

    print("status=PASS")
    print("rv32_regcpy_canonicalization=sign_extended")
    print("mixed_provenance_bne=fallthrough")
    print(f"final_pc=0x{after_branch.pc:08x}")


if __name__ == "__main__":
    main()
