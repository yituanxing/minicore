#!/usr/bin/env python3
"""Read-only P1 eligibility audit for a *real* QEMU D2 architectural export.

No RTL restore/import is implemented here.  Even a complete source is NOT a
resume PASS: the D2 platform/SoC seam, CSR WARL effects, and asynchronous
devices still require independent qualification.
"""

import argparse
import json
from pathlib import Path
import struct
import sys

try:
    from . import preflight
except ImportError:
    import preflight


OVERLAY_BASE = 0x80000000
OVERLAY_BYTES = 0x100000
D2_TOP = 'AetherCoreProductDebianRV64SimTop'
SD1_TOP = 'AetherCoreProductDebianSdRV64SimTop'
FDT_MAGIC = 0xD00DFEED


def read_fdt_nodes(path):
    """Read only enough standard FDT structure to check reserved-memory.

    Refuse malformed lengths, duplicate properties or truncated structures.
    No external dtc installation or shell parsing is required.
    """
    blob = Path(path).read_bytes()
    if len(blob) < 40:
        raise ValueError('DTB: truncated FDT header')
    (magic, size, off_struct, off_strings, _, version, _, _,
     size_strings, size_struct) = struct.unpack_from('>10I', blob)
    if magic != FDT_MAGIC or version < 16 or size > len(blob):
        raise ValueError('DTB: invalid FDT header')
    if (off_struct + size_struct > size or
            off_strings + size_strings > size):
        raise ValueError('DTB: out-of-bounds FDT block')
    strings = blob[off_strings:off_strings + size_strings]
    end_struct = off_struct + size_struct
    pos = off_struct
    stack = []
    nodes = {}
    ended = False
    while pos + 4 <= end_struct:
        token = struct.unpack_from('>I', blob, pos)[0]
        pos += 4
        if token == 1:  # FDT_BEGIN_NODE
            zero = blob.find(b'\0', pos, end_struct)
            if zero < 0:
                raise ValueError('DTB: unterminated node')
            name = blob[pos:zero].decode('utf-8')
            pos = (zero + 4) & ~3
            stack.append(name)
            key = '/' + '/'.join(item for item in stack if item)
            if key in nodes:
                raise ValueError('DTB: duplicate node')
            nodes[key] = {}
        elif token == 2:  # FDT_END_NODE
            if not stack:
                raise ValueError('DTB: unbalanced nodes')
            stack.pop()
        elif token == 3:  # FDT_PROP
            if not stack or pos + 8 > end_struct:
                raise ValueError('DTB: truncated property')
            length, nameoff = struct.unpack_from('>II', blob, pos)
            pos += 8
            if nameoff >= len(strings) or pos + length > end_struct:
                raise ValueError('DTB: out-of-bounds property')
            stop = strings.find(b'\0', nameoff)
            if stop < 0:
                raise ValueError('DTB: unterminated property name')
            name = strings[nameoff:stop].decode('ascii')
            props = nodes['/' + '/'.join(item for item in stack if item)]
            if name in props:
                raise ValueError('DTB: duplicate property')
            props[name] = blob[pos:pos + length]
            pos = (pos + length + 3) & ~3
        elif token == 4:  # FDT_NOP
            continue
        elif token == 9:  # FDT_END
            ended = True
            break
        else:
            raise ValueError(f'DTB: unexpected FDT token {token}')
    if not ended or stack:
        raise ValueError('DTB: unfinished structure')
    return nodes


def reserved_overlay_check(dtb):
    nodes = read_fdt_nodes(dtb)
    root = nodes.get('/', {})
    reserved = nodes.get('/reserved-memory', {})
    # Standard reserved-memory has an empty `ranges`.  Without it, the
    # child's reg addresses cannot safely be interpreted as root physical PA.
    ranges_valid = 'ranges' in reserved and reserved['ranges'] == b''

    def cells(props, fallback, key):
        raw = props.get(key, fallback.get(key, b'\0\0\0\2' if key == '#address-cells'
                                                   else b'\0\0\0\1'))
        if len(raw) != 4:
            raise ValueError('DTB: bad cell width property')
        result = int.from_bytes(raw, 'big')
        if not 1 <= result <= 2:
            raise ValueError('DTB: unsupported address/size cells')
        return result

    address_cells = cells(reserved, root, '#address-cells')
    size_cells = cells(reserved, root, '#size-cells')
    stride = (address_cells + size_cells) * 4
    covering = []
    overlapping = []
    for path, props in nodes.items():
        if not path.startswith('/reserved-memory/') or path.count('/') != 2:
            continue
        reg = props.get('reg', b'')
        if not reg or len(reg) % stride:
            raise ValueError(f'DTB: malformed reserved-memory reg at {path}')
        for offset in range(0, len(reg), stride):
            base = int.from_bytes(reg[offset:offset + address_cells * 4], 'big')
            length = int.from_bytes(reg[offset + address_cells * 4:offset + stride], 'big')
            if base < OVERLAY_BASE + OVERLAY_BYTES and base + length > OVERLAY_BASE:
                entry = {'node': path, 'base': f'0x{base:x}', 'bytes': length,
                         'no_map': 'no-map' in props and props['no-map'] == b''}
                overlapping.append(entry)
                if (ranges_valid and base <= OVERLAY_BASE and
                        base + length >= OVERLAY_BASE + OVERLAY_BYTES and entry['no_map']):
                    covering.append(entry)
    return {'overlay_base': f'0x{OVERLAY_BASE:x}',
            'overlay_bytes': OVERLAY_BYTES,
            'reserved_parent_ranges_empty': ranges_valid,
            'reserved_no_map_covering_overlay': bool(covering),
            'overlapping_reserved_nodes': overlapping}


def audit(export, ram, inputs, target_top, expected_ram_bytes=preflight.RAM_BYTES):
    source = preflight.inspect(export, ram, inputs, expected_ram_bytes=expected_ram_bytes)
    regs = {name: preflight.parse_hex(raw, name)
            for name, raw in export['qemu_gdb_registers'].items()}
    blockers = []
    if not source['minimum_s_mode_register_subset_present']:
        blockers.append('GDB lacks minimum S-mode architectural source registers')
    if not source['pmp_registers_present']:
        blockers.append('GDB lacks complete PMP address/configuration registers')
    if source['missing_counter_and_warl_csrs']:
        blockers.append('GDB lacks counters or WARL CSR source values')
    if source['nonzero_counter_and_warl_csrs']:
        blockers.append('Source counter/WARL state cannot be recreated by P0 guest CSR writes')
    if target_top != D2_TOP:
        blockers.append('QEMU D2 RAM-only source cannot restore the SD1 SPI-SD platform')
    # The source manifest hashes firmware and DTB, but it currently does not
    # attest the QEMU launch argv/board profile; treat a matching CLI target
    # name as a hypothesis, not immutable device equivalence evidence.
    blockers.append('QEMU source board profile not attested by export manifest')
    reserved = reserved_overlay_check(inputs['dtb'])
    if not reserved['reserved_no_map_covering_overlay']:
        blockers.append('DTB has no no-map reservation covering first 1 MiB restore overlay')
    blockers.append('Host overlay installation and removal without guest RAM mutation unproved')

    satp = regs.get('satp')
    satp_mode = (satp >> 60) if satp is not None else None
    satp_root = ((satp & ((1 << 44) - 1)) << 12) if satp is not None else None
    sv39 = satp_mode == 8
    page_table_in_ram = bool(sv39 and satp_root is not None and
                             preflight.RAM_BASE <= satp_root <
                             preflight.RAM_BASE + expected_ram_bytes)
    if not sv39:
        blockers.append('Actual source Sv39 satp missing or not active')
    elif not page_table_in_ram:
        blockers.append('Sv39 root PPN does not fall inside authenticated RAM aperture')
    else:
        blockers.append('Sv39 page-table/PMP walk and TLB/cache warm-reset parity unproved')
    mstatus = regs.get('mstatus')
    if mstatus is None:
        blockers.append('mstatus is missing')
    else:
        if mstatus & (3 << 13):
            blockers.append('mstatus.FS requires F/D state restoration not in P0')
        if mstatus & ((3 << 11) | (1 << 17)) or not mstatus & (1 << 7) or not mstatus & (1 << 3):
            blockers.append('MRET would alter source mstatus privilege/interrupt bits')
    if regs.get('mepc') != regs.get('pc'):
        blockers.append('MRET overwrites source mepc with target PC')
    if regs.get('mie') or regs.get('mip'):
        blockers.append('Nonzero interrupt enable/pending state requires exact timer/PLIC epoch')
    blockers.extend([
        'PLIC/UART/CLINT/SPI-or-RAM device-state equivalence unproved',
        'LR/SC reservation, concurrent DMA and in-flight MMIO not frozen',
        'No qualified Product RTL warm restore and post-import retirement diff',
    ])
    return {
        'schema': 'aethercore.c1.p1-source-eligibility.v1',
        'source_manifest_sha256': source['source_manifest_sha256'],
        'source_ram_sha256': source['source_ram_sha256'],
        'source_pc': source['source_pc'],
        'source_privilege': regs.get('priv'),
        'target_top': target_top,
        'platform': 'QEMU_D2_RAM_ONLY_SOURCE_UNATTESTED_NOT_SD1',
        'dtb_overlay': reserved,
        'satp': None if satp is None else f'0x{satp:x}',
        'satp_mode': satp_mode,
        'satp_root_pa': None if satp_root is None else f'0x{satp_root:x}',
        'sv39_root_in_signed_ram': page_table_in_ram,
        'pmp_registers_available': source['pmp_registers_present'],
        'fpu_source_required': mstatus is None or bool(mstatus & (3 << 13)),
        'interrupt_enables': None if 'mie' not in regs else f'0x{regs["mie"]:x}',
        'interrupt_pending': None if 'mip' not in regs else f'0x{regs["mip"]:x}',
        'blockers': blockers,
        'restore_plan': 'NO_WRITE_DIAGNOSTIC_ONLY',
        'rtl_warm_resume_permitted': False,
        'rtl_handoff_pass': False,
        'result': 'C1_P1_SOURCE_PLAN_REVIEWED_NOT_RTL_RESUMED',
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--ram', type=Path, required=True)
    p.add_argument('--target-top', choices=(D2_TOP, SD1_TOP), required=True)
    p.add_argument('--report', type=Path, required=True)
    for name in preflight.INPUTS:
        p.add_argument('--' + name.replace('_', '-'), required=True, type=Path)
    a = p.parse_args()
    try:
        result = audit(json.loads(a.manifest.read_text()), a.ram,
                       {key: getattr(a, key) for key in preflight.INPUTS}, a.target_top)
        with a.report.open('x', encoding='utf-8') as out:
            json.dump(result, out, indent=2, sort_keys=True)
            out.write('\n')
    except (OSError, ValueError) as exc:
        print('PRODUCT_C1_P1_SOURCE_REJECTED:', exc, file=sys.stderr)
        return 2
    print('PRODUCT_C1_P1_SOURCE_PLAN_ONLY_NOT_RTL_RESUMED blockers=' + str(len(result['blockers'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
