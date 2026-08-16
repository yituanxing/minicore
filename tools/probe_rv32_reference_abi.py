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
    reference.new_space.argtypes = [ctypes.c_int]
    reference.new_space.restype = ctypes.POINTER(ctypes.c_uint8)
    reference.add_mmio_map.argtypes = [
        ctypes.c_char_p,
        ctypes.c_uint32,
        ctypes.POINTER(ctypes.c_uint8),
        ctypes.c_int,
        ctypes.c_void_p,
    ]
    reference.add_mmio_map.restype = None


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


def probe_regcpy_canonicalization(reference: ctypes.CDLL) -> None:
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

    print("rv32_regcpy_canonicalization=sign_extended")
    print("mixed_provenance_bne=fallthrough")
    print(f"branch_final_pc=0x{after_branch.pc:08x}")


def probe_multiple_passive_mmio(reference: ctypes.CDLL) -> None:
    reference.difftest_init()
    first = reference.new_space(16)
    second = reference.new_space(16)
    reference.add_mmio_map(b"uart", 0x10000000, first, 16, None)
    reference.add_mmio_map(b"mtime", 0x0200B000, second, 16, None)

    first_write = (ctypes.c_uint8 * 4)(0x46, 0x52, 0x45, 0x45)
    second_write = (ctypes.c_uint8 * 4)(0x54, 0x49, 0x4D, 0x45)
    reference.difftest_memcpy(
        0x10000000, ctypes.cast(first_write, ctypes.c_void_p), 4, TO_REF
    )
    reference.difftest_memcpy(
        0x0200B000, ctypes.cast(second_write, ctypes.c_void_p), 4, TO_REF
    )

    first_read = (ctypes.c_uint8 * 4)()
    second_read = (ctypes.c_uint8 * 4)()
    reference.difftest_memcpy(
        0x10000000, ctypes.cast(first_read, ctypes.c_void_p), 4, TO_DUT
    )
    reference.difftest_memcpy(
        0x0200B000, ctypes.cast(second_read, ctypes.c_void_p), 4, TO_DUT
    )
    if bytes(first_read) != bytes(first_write) or bytes(second_read) != bytes(second_write):
        raise SystemExit("ERROR: independent passive MMIO mappings did not retain their contents")

    print("passive_mmio_maps=2")
    print("passive_mmio_independent=PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()

    reference = ctypes.CDLL(str(args.reference.resolve()))
    configure(reference)
    probe_regcpy_canonicalization(reference)
    probe_multiple_passive_mmio(reference)
    print("status=PASS")


if __name__ == "__main__":
    main()
