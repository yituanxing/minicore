#!/usr/bin/env python3
"""Pack an isolated RV64 S-mode P0 state for product_c1_arch_restore.S.

This is an *experimental* guest-side restore ABI, not a QEMU savevm decoder,
Verilator checkpoint, or working RTL injector.  Intentionally rejects normal
Linux/SD1 snapshots: the P0 path cannot restore devices, PMP, paging or IRQs.
"""

import argparse
import json
from pathlib import Path
import struct
import sys

try:
    from .preflight import parse_hex, manifest_sha
except ImportError:
    from preflight import parse_hex, manifest_sha


# Byte offsets equal 8 * word index; see sim/product_c1_arch_restore.S.
WORDS = (
    'pc', 'mstatus', 'medeleg', 'mideleg', 'mie', 'mtvec',
    'mscratch', 'mepc', 'mcause', 'mtval', 'stvec', 'sscratch',
    'sepc', 'scause', 'stval', 'satp', 'scounteren', 'mcounteren',
    'mip', 'pmpaddr0', 'pmpcfg0',
)
WORD_COUNT = len(WORDS) + 32
BYTE_COUNT = WORD_COUNT * 8

# One exact unlocked NAPOT RWX entry for [0x80000000, 0x90000000).
# Product's PMP16 rejects S/U-mode accesses unmatched by any active entry.
P0_DDR_NAPOT_ADDR0 = 0x21FFFFFF
P0_DDR_NAPOT_CFG0 = 0x1F


def pack_p0(export, review):
    if not isinstance(review, dict) or review.get('schema') != 'aethercore.c1.arch-source-review.v1':
        raise ValueError('a SHA-verified C1 source review is required')
    if review.get('rtl_handoff_pass') is not False or not review.get(
            'minimum_s_mode_register_subset_present'):
        raise ValueError('source review incomplete or falsely claims RTL PASS')
    if review.get('source_manifest_sha256') != manifest_sha(export):
        raise ValueError('source manifest has changed since SHA-verified review')
    regs = export.get('qemu_gdb_registers')
    if not isinstance(regs, dict) or review.get('source_pc') != export.get('breakpoint_pc'):
        raise ValueError('source registers/PC disagree with review')
    if parse_hex(regs.get('priv'), 'priv') != 1:
        raise ValueError('P0 only accepts an S-mode source')
    values = [parse_hex(regs.get(word), word) for word in WORDS]
    gpr = [parse_hex(regs.get(f'x{i}'), f'x{i}') for i in range(32)]
    if gpr[0] != 0:
        raise ValueError('x0 must be zero')
    state = dict(zip(WORDS, values))
    mstatus = state['mstatus']
    # An instruction-based MRET clears MPP and MPRV, sets MPIE and replaces
    # MIE with prior MPIE. Require a source already at the resulting values;
    # during the shim MPP is forced to Supervisor, then MRET clears it.
    if (mstatus & (3 << 11)) or not (mstatus & (1 << 7)) or not (
            mstatus & (1 << 3)) or (mstatus & (1 << 17)):
        raise ValueError('mstatus cannot survive P0 MRET bit-exactly')
    if mstatus & (3 << 13):
        raise ValueError('P0 has no FPU replay; mstatus.FS must be Off')
    if state['mepc'] != state['pc']:
        raise ValueError('P0 MRET overwrites mepc; source mepc must equal target PC')
    if state['mie'] or state['mip'] or state['satp']:
        raise ValueError('P0 forbids interrupt enable/pending and page-table state')
    # A zeroed Product PMP16 is *not* permissive: unmatched S-mode instruction
    # fetches fail.  P0 accepts only a single all-DDR RWX NAPOT entry, and
    # requires every other architectural PMP slot to be explicitly zero.
    # In RV64 each pmpcfg CSR configures eight PMP entries; only the even
    # registers 0/2 are architecturally accessible.  pmpcfg1 and pmpcfg3
    # are illegal, so demanding their presence would reject real QEMU.
    for name in (*[f'pmpaddr{i}' for i in range(16)],
                 'pmpcfg0', 'pmpcfg2'):
        permitted = (P0_DDR_NAPOT_ADDR0 if name == 'pmpaddr0' else
                     P0_DDR_NAPOT_CFG0 if name == 'pmpcfg0' else 0)
        if parse_hex(regs.get(name), name) != permitted:
            raise ValueError('P0 requires exact DDR PMP NAPOT state: ' + name)
    return struct.pack('<' + 'Q' * WORD_COUNT, *(values + gpr))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', required=True, type=Path)
    p.add_argument('--review', required=True, type=Path)
    p.add_argument('--out', required=True, type=Path)
    a = p.parse_args()
    try:
        state = pack_p0(json.loads(a.manifest.read_text()),
                        json.loads(a.review.read_text()))
        with a.out.open('xb') as output:
            output.write(state)
    except (ValueError, OSError) as err:
        print('PRODUCT_C1_P0_RESTORE_ABI_REJECTED:', err, file=sys.stderr)
        return 2
    print('PRODUCT_C1_P0_RESTORE_TABLE_PREPARED_NOT_RTL_RESUMED bytes=' + str(len(state)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
