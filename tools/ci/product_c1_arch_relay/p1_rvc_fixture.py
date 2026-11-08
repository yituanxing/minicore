#!/usr/bin/env python3
"""Bounded synthetic G5 Sv39 fixture for one observed Linux RVC opcode.

The first two opcode bytes and PC/SP reference match a separately verified
QEMU source oracle. The remaining RAM, page tables, CSR/GPR state and illegal
CSR sentinel are *synthetic*; this is NOT a Linux/QEMU state injection.
"""

import argparse
import json
from pathlib import Path
import struct

try:
    from . import restore_abi, p1_sv39_fixture as p1
    from .first_retire_oracle import c_addi16sp_imm
except ImportError:
    import restore_abi
    import p1_sv39_fixture as p1
    from first_retire_oracle import c_addi16sp_imm


RAM_BASE = p1.RAM_BASE
RAM_BYTES = p1.RAM_BYTES
STATE_OFFSET = p1.STATE_OFFSET
ROOT_PA = p1.ROOT_PA
HIGH_L1_PA = p1.HIGH_L1_PA
SATP = p1.SATP
VIRTUAL_PC = 0xFFFFFFFF808C3490
PHYSICAL_PC = 0x80AC3490
LEAF_PHYSICAL_BASE = 0x80A00000
INITIAL_SP = 0xFFFFFFC80000BEC0
FIRST_OPCODE = 0x7179
EXPECTED_SP = 0xFFFFFFC80000BE90
EXPECTED_NEXT_PC = VIRTUAL_PC + 2
S_MODE_CSR_TRAP = 0x300026F3  # csrr x13,mstatus, synthetic next instruction
INSTRUCTION_AND_SENTINEL = struct.pack('<HI', FIRST_OPCODE, S_MODE_CSR_TRAP)


def make_state():
    fields = {name: 0 for name in restore_abi.WORDS}
    fields.update({
        'pc': VIRTUAL_PC,
        'mepc': VIRTUAL_PC,
        'mstatus': 0x88,
        'satp': SATP,
        'pmpaddr0': restore_abi.P0_DDR_NAPOT_ADDR0,
        'pmpcfg0': restore_abi.P0_DDR_NAPOT_CFG0,
    })
    regs = [0] * 32
    regs[2] = INITIAL_SP
    state = struct.pack('<' + 'Q' * restore_abi.WORD_COUNT,
                        *[fields[name] for name in restore_abi.WORDS], *regs)
    if len(state) != restore_abi.BYTE_COUNT:
        raise ValueError('restore ABI size changed')
    return state


def table_pages():
    root, high = bytearray(4096), bytearray(4096)
    if (p1.vpn(VIRTUAL_PC, 2), p1.vpn(VIRTUAL_PC, 1)) != (510, 4):
        raise ValueError('QEMU source VA no longer matches expected Sv39 indices')
    if (VIRTUAL_PC & ((1 << 21) - 1)) != (PHYSICAL_PC & ((1 << 21) - 1)):
        raise ValueError('2 MiB leaf cannot implement observed source mapping')
    if LEAF_PHYSICAL_BASE & ((1 << 21) - 1):
        raise ValueError('Sv39 level-1 leaf must be 2 MiB aligned')
    struct.pack_into('<Q', root, 510 * 8,
                     ((HIGH_L1_PA >> 12) << 10) | 1)
    struct.pack_into('<Q', high, 4 * 8,
                     ((LEAF_PHYSICAL_BASE >> 12) << 10) | p1.LEAF_FLAGS)
    return root, high


def expected():
    if (c_addi16sp_imm(FIRST_OPCODE) != -48 or
            ((INITIAL_SP - 48) & ((1 << 64) - 1)) != EXPECTED_SP):
        raise ValueError('observed Linux first RVC/SP reference changed')
    return {
        'schema': 'aethercore.c1.g5-first-rvc.synthetic-sv39.v1',
        'qualification': 'SYNTHETIC_G5_RVC_ORACLE_OPCODE_ONLY_NOT_QEMU_HANDOFF_OR_SD1',
        'rtl_handoff_pass': False,
        'source_reference_opcode_only': 'observed QEMU source first 2 bytes: 7971',
        'real_product_top': 'AetherCoreProductDebianRV64SimTop',
        'virtual_pc': hex(VIRTUAL_PC),
        'physical_pc': hex(PHYSICAL_PC),
        'satp': hex(SATP),
        'initial_sp': hex(INITIAL_SP),
        'first_instruction_le': '7971',
        'first_instruction': hex(FIRST_OPCODE),
        'expected_sp_after': hex(EXPECTED_SP),
        'expected_next_pc': hex(EXPECTED_NEXT_PC),
        'followup_synthetic_illegal_machine_csr': hex(S_MODE_CSR_TRAP),
        'expected_retired_nonfaulting_instructions': 1,
        'expected_followup_exception_cause': 2,
    }


def finalize(out):
    state = make_state()
    restore = (out / 'restore-flat.bin').read_bytes()
    if (len(restore) > 1024 * 1024 or
            restore[STATE_OFFSET:STATE_OFFSET + len(state)] != state):
        raise ValueError('restore-flat is not the independent RVC state')
    pages = table_pages()
    if len(INSTRUCTION_AND_SENTINEL) != 6:
        raise ValueError('RVC sentinel fixture width changed')
    with (out / 'ram-256m.bin').open('wb') as sink:
        sink.truncate(RAM_BYTES)
        sink.seek(PHYSICAL_PC - RAM_BASE)
        sink.write(INSTRUCTION_AND_SENTINEL)
        for pa, page in zip((ROOT_PA, HIGH_L1_PA), pages):
            sink.seek(pa - RAM_BASE)
            sink.write(page)
    (out / 'source-first-rvc-then-synthetic-sentinel.bin').write_bytes(
        INSTRUCTION_AND_SENTINEL)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out-dir', required=True, type=Path)
    p.add_argument('--finalize', action='store_true')
    a = p.parse_args()
    a.out_dir.mkdir(parents=True, exist_ok=True)
    (a.out_dir / 'state-424.bin').write_bytes(make_state())
    (a.out_dir / 'expected.json').write_text(
        json.dumps(expected(), sort_keys=True, indent=2) + '\n')
    if a.finalize:
        finalize(a.out_dir)
    print('PRODUCT_C1_G5_RVC_SYNTHETIC_FIXTURE_PREPARED_NOT_RTL_PASS')


if __name__ == '__main__':
    main()
