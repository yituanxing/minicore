#!/usr/bin/env python3
"""Independent G5 Product synthetic Sv39 S-mode fixture (NOT Linux/QEMU).

It deliberately bypasses P0's SATP=0 packer by building an explicit synthetic
424-byte state table. Two 2-MiB supervisor mappings cover one guest text page
and a low-VA marker page using physical DDR source bytes, not host-side address
rewrites. The real Product CPU must execute the hardware PTW after MRET.
"""

import argparse
import json
from pathlib import Path
import struct

try:
    from . import restore_abi, p0_fixture
except ImportError:
    import restore_abi
    import p0_fixture


RAM_BASE = p0_fixture.RAM_BASE
RAM_BYTES = p0_fixture.RAM_BYTES
STATE_OFFSET = p0_fixture.STATE_OFFSET
PHYSICAL_PC = p0_fixture.TARGET_PC
GUEST_PC = 0xFFFFFFFF80100000
MARKER_VA = p0_fixture.MARKER_ADDRESS
MARKER_PA = MARKER_VA
ROOT_PA = 0x80200000
HIGH_L1_PA = ROOT_PA + 0x1000
LOW_L1_PA = ROOT_PA + 0x2000
SATP = (8 << 60) | (ROOT_PA >> 12)
PTE_V = 1
LEAF_FLAGS = 0xCF  # V|R|W|X|A|D, S-only U=0
PT_PAGE_BYTES = 4096


def vpn(va, level):
    return (va >> (12 + level * 9)) & 0x1FF


def make_state():
    fields = {name: 0 for name in restore_abi.WORDS}
    fields.update({
        'pc': GUEST_PC,
        'mepc': GUEST_PC,
        'mstatus': 0x88,
        'satp': SATP,
        'pmpaddr0': restore_abi.P0_DDR_NAPOT_ADDR0,
        'pmpcfg0': restore_abi.P0_DDR_NAPOT_CFG0,
    })
    regs = [0] * 32
    regs[5] = p0_fixture.SOURCE_X5
    regs[6] = p0_fixture.SOURCE_X6
    regs[10] = MARKER_VA
    regs[11] = p0_fixture.SOURCE_X11
    regs[12] = p0_fixture.SOURCE_X12
    state = struct.pack('<' + 'Q' * restore_abi.WORD_COUNT,
                        *[fields[n] for n in restore_abi.WORDS], *regs)
    assert len(state) == restore_abi.BYTE_COUNT
    return state


def table_pages():
    root = bytearray(PT_PAGE_BYTES)
    high = bytearray(PT_PAGE_BYTES)
    low = bytearray(PT_PAGE_BYTES)

    def put(buf, index, pte):
        struct.pack_into('<Q', buf, index * 8, pte)

    assert vpn(GUEST_PC, 2) == 510 and vpn(GUEST_PC, 1) == 0
    assert vpn(MARKER_VA, 2) == 2 and vpn(MARKER_VA, 1) == 0
    assert (RAM_BASE & ((1 << 21) - 1)) == 0
    put(root, vpn(GUEST_PC, 2), ((HIGH_L1_PA >> 12) << 10) | PTE_V)
    put(root, vpn(MARKER_VA, 2), ((LOW_L1_PA >> 12) << 10) | PTE_V)
    # Aligned level-1 Sv39 leaf PPN: the entire 2-MiB page is mapped and
    # independently translated by Product RTL. No identity-VA shortcut.
    leaf = ((RAM_BASE >> 12) << 10) | LEAF_FLAGS
    put(high, vpn(GUEST_PC, 1), leaf)
    put(low, vpn(MARKER_VA, 1), leaf)
    return root, high, low


def walk(virtual, pages):
    """Strict reference Sv39 walk used solely for local fixture self-tests."""
    root, high, low = pages
    if ((virtual >> 39) not in (0, (1 << 25) - 1)):
        raise ValueError('noncanonical Sv39 VA')
    root_entry = struct.unpack_from('<Q', root, vpn(virtual, 2) * 8)[0]
    if (root_entry & 1) == 0 or (root_entry & 0xE):
        raise ValueError('Sv39 root PTE invalid')
    next_pa = (root_entry >> 10) << 12
    if next_pa not in (HIGH_L1_PA, LOW_L1_PA):
        raise ValueError('Sv39 root points outside fixture tables')
    l1 = high if next_pa == HIGH_L1_PA else low
    leaf = struct.unpack_from('<Q', l1, vpn(virtual, 1) * 8)[0]
    if (leaf & LEAF_FLAGS) != LEAF_FLAGS or ((leaf >> 10) & 0x1FF):
        raise ValueError('Sv39 invalid/non-aligned 2-MiB leaf')
    return ((leaf >> 10) << 12) | (virtual & ((1 << 21) - 1))


def expected():
    return {
        'schema': 'aethercore.c1.p1-sv39.synthetic-fixture.v1',
        'qualification': 'SYNTHETIC_SV39_G5_ONLY_NOT_QEMU_OR_LINUX_OR_SD1',
        'rtl_handoff_pass': False,
        'g5_real_product_top': 'AetherCoreProductDebianRV64SimTop',
        'ram_base': hex(RAM_BASE), 'ram_bytes': RAM_BYTES,
        'guest_virtual_pc': hex(GUEST_PC),
        'guest_physical_pc': hex(PHYSICAL_PC),
        'marker_virtual': hex(MARKER_VA),
        'marker_physical': hex(MARKER_PA),
        'satp': hex(SATP), 'pt_root_pa': hex(ROOT_PA),
        'high_l1_pa': hex(HIGH_L1_PA), 'low_l1_pa': hex(LOW_L1_PA),
        'expected_nonfaulting_commits': 5,
        'expected_privilege_trap_commit': 6,
        'expected_marker_words': [hex(v) for v in p0_fixture.EXPECTED_MARKERS],
    }


def finalize(out, guest):
    guest_bytes = guest.read_bytes()
    if not guest_bytes or len(guest_bytes) > MARKER_PA + 32 - PHYSICAL_PC:
        raise ValueError('synthetic Sv39 guest outside fixed DDR window')
    image = (out / 'restore-flat.bin').read_bytes()
    state = make_state()
    if len(image) > p0_fixture.OVERLAY_RESERVED_BYTES:
        raise ValueError('restore shim outside reserved first MiB')
    if image[STATE_OFFSET:STATE_OFFSET + len(state)] != state:
        raise ValueError('linked restore does not contain synthetic Sv39 state')
    pages = table_pages()
    if walk(GUEST_PC, pages) != PHYSICAL_PC:
        raise ValueError('instruction translation mismatch')
    if walk(MARKER_VA, pages) != MARKER_PA:
        raise ValueError('data translation mismatch')
    with (out / 'ram-256m.bin').open('wb') as sink:
        sink.truncate(RAM_BYTES)
        sink.seek(PHYSICAL_PC - RAM_BASE)
        sink.write(guest_bytes)
        for pa, page in zip((ROOT_PA, HIGH_L1_PA, LOW_L1_PA), pages):
            sink.seek(pa - RAM_BASE)
            sink.write(page)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out-dir', type=Path, required=True)
    p.add_argument('--guest-bin', type=Path)
    args = p.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / 'state-424.bin').write_bytes(make_state())
    (args.out_dir / 'expected.json').write_text(
        json.dumps(expected(), sort_keys=True, indent=2) + '\n')
    if args.guest_bin:
        finalize(args.out_dir, args.guest_bin)
    print('PRODUCT_C1_P1_SV39_SYNTHETIC_INPUT_PREPARED_NOT_RTL_PASS')


if __name__ == '__main__':
    main()
