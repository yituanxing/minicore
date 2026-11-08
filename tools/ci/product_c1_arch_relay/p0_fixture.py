#!/usr/bin/env python3
"""Generate *synthetic* C1 P0 state, then assemble an isolated DDR fixture.

This is deliberately not a QEMU capture, Linux checkpoint, or RTL PASS.
The state exercises the real restore_abi.pack_p0() constraints against a
deterministic fixture; no source SHA / device equivalence is claimed.
"""

import argparse
import json
from pathlib import Path
import struct

try:
    from . import restore_abi
    from .preflight import manifest_sha
except ImportError:
    import restore_abi
    from preflight import manifest_sha


RAM_BASE = 0x80000000
RAM_BYTES = 256 * 1024 * 1024
OVERLAY_RESERVED_BYTES = 1024 * 1024
STATE_OFFSET = 0x18000
TARGET_PC = 0x80100000
MARKER_ADDRESS = 0x80101000
GUEST_OFFSET = TARGET_PC - RAM_BASE
SOURCE_X5 = 0x5555555555555555
SOURCE_X6 = 0x6666666666666666
SOURCE_X11 = 0xCAFEBABEDEADBEEF
SOURCE_X12 = 41
EXPECTED_MARKERS = (SOURCE_X5, SOURCE_X6, SOURCE_X11, SOURCE_X12 + 1)


def make_state():
    regs = {name: '0x0' for name in restore_abi.WORDS}
    regs.update({f'x{i}': '0x0' for i in range(32)})
    regs.update({f'pmpaddr{i}': '0x0' for i in range(16)})
    regs.update({f'pmpcfg{i}': '0x0' for i in range(4)})
    regs.update({
        'pc': hex(TARGET_PC), 'mepc': hex(TARGET_PC),
        'mstatus': '0x88', 'priv': '0x1',
        'pmpaddr0': hex(restore_abi.P0_DDR_NAPOT_ADDR0),
        'pmpcfg0': hex(restore_abi.P0_DDR_NAPOT_CFG0),
        'x5': hex(SOURCE_X5), 'x6': hex(SOURCE_X6),
        'x10': hex(MARKER_ADDRESS), 'x11': hex(SOURCE_X11),
        'x12': hex(SOURCE_X12),
    })
    export = {'breakpoint_pc': hex(TARGET_PC), 'qemu_gdb_registers': regs}
    # Only a fixture-internal pack self-check: no real QEMU review was done.
    synthetic_review = {
        'schema': 'aethercore.c1.arch-source-review.v1',
        'minimum_s_mode_register_subset_present': True,
        'rtl_handoff_pass': False,
        'source_pc': hex(TARGET_PC),
        'source_manifest_sha256': manifest_sha(export),
    }
    return restore_abi.pack_p0(export, synthetic_review)


def expected():
    return {
        'schema': 'aethercore.c1.p0.synthetic-fixture.v1',
        'qualification': 'SYNTHETIC_P0_ONLY_NOT_QEMU_AND_NOT_RTL_PASS',
        'rtl_handoff_pass': False,
        'ram_base': f'0x{RAM_BASE:x}',
        'ram_bytes': RAM_BYTES,
        'overlay_reserved_bytes': OVERLAY_RESERVED_BYTES,
        'state_offset': STATE_OFFSET,
        'state_bytes': restore_abi.BYTE_COUNT,
        'source_pc': f'0x{TARGET_PC:x}',
        'source_priv': 'S',
        'target_pc': f'0x{TARGET_PC:x}',
        'marker_address': f'0x{MARKER_ADDRESS:x}',
        'expected_marker_words': [f'0x{v:x}' for v in EXPECTED_MARKERS],
        'expected_commits_after_resume': 5,
    }


def finalize(out, guest):
    guest_bytes = guest.read_bytes()
    if not guest_bytes or len(guest_bytes) > MARKER_ADDRESS + 32 - TARGET_PC:
        raise ValueError('guest flat image outside the deterministic P0 range')
    if len((out / 'restore-flat.bin').read_bytes()) > OVERLAY_RESERVED_BYTES:
        raise ValueError('restore flat image would overwrite guest RAM after reserved MiB')
    state = make_state()
    flat = (out / 'restore-flat.bin').read_bytes()
    if flat[STATE_OFFSET:STATE_OFFSET + len(state)] != state:
        raise ValueError('linked restore flat image does not contain packed P0 state')
    ram = out / 'ram-256m.bin'
    with ram.open('wb') as sink:
        sink.truncate(RAM_BYTES)  # sparse, deterministic zero-filled RAM
        sink.seek(GUEST_OFFSET)
        sink.write(guest_bytes)
    assert ram.stat().st_size == RAM_BYTES
    assert TARGET_PC >= RAM_BASE + OVERLAY_RESERVED_BYTES
    assert STATE_OFFSET + len(state) <= OVERLAY_RESERVED_BYTES


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out-dir', type=Path, required=True)
    p.add_argument('--guest-bin', type=Path)
    args = p.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / 'state-424.bin').write_bytes(make_state())
    (args.out_dir / 'expected.json').write_text(
        json.dumps(expected(), sort_keys=True, indent=2) + '\n')
    if args.guest_bin is not None:
        finalize(args.out_dir, args.guest_bin)
        print('PRODUCT_C1_SYNTHETIC_P0_RAM_PREPARED_NOT_RTL_PASS bytes=' + str(RAM_BYTES))
    else:
        print('PRODUCT_C1_SYNTHETIC_P0_STATE_PREPARED_NOT_RTL_PASS bytes=' +
              str(restore_abi.BYTE_COUNT))


if __name__ == '__main__':
    main()
