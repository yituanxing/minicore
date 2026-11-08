#!/usr/bin/env python3
"""Strict, read-only Sv39 instruction fetch from a SHA-bound QEMU RAM image.

This is a *source-only* page-table diagnostic. It does not restore any RTL
state, synchronize devices or certify instruction retirement. It intentionally
rejects unsupported RISC-V PTE extensions rather than silently interpreting
them as base Sv39 mappings.
"""

import argparse
import json
from pathlib import Path
import sys

try:
    from . import preflight
except ImportError:
    import preflight


SV39_MODE = 8
SV39_LEVELS = (2, 1, 0)
PAGE_BITS = 12
PAGE_SIZE = 1 << PAGE_BITS
PTE_SIZE = 8
FULL64 = (1 << 64) - 1


class WalkError(ValueError):
    pass


def _canonical_sv39(va):
    if type(va) is not int or not 0 <= va <= FULL64:
        raise WalkError('virtual PC is not an unsigned RV64 address')
    sign = (va >> 38) & 1
    if (va >> 39) != ((1 << 25) - 1 if sign else 0):
        raise WalkError('virtual PC is not Sv39 canonical')


def _read_ram(ram, ram_base, ram_bytes, pa, size):
    if not (ram_base <= pa and pa - ram_base <= ram_bytes and
            size <= ram_bytes - (pa - ram_base)):
        raise WalkError(f'physical address 0x{pa:x} escapes sealed DDR aperture')
    ram.seek(pa - ram_base)
    payload = ram.read(size)
    if len(payload) != size:
        raise WalkError(f'short sealed physical-memory read at 0x{pa:x}')
    return payload


def walk_exec(ram, ram_base, ram_bytes, satp, va):
    """Return supervisor instruction-fetch PA and PTE path (no side effects)."""
    _canonical_sv39(va)
    if type(satp) is not int or not 0 <= satp <= FULL64:
        raise WalkError('satp is not RV64')
    mode = satp >> 60
    if mode != SV39_MODE:
        raise WalkError(f'actual satp.MODE={mode}, expected Sv39 mode 8')
    page_table = (satp & ((1 << 44) - 1)) << PAGE_BITS
    steps = []
    for level in SV39_LEVELS:
        vpn = (va >> (PAGE_BITS + 9 * level)) & 0x1FF
        pte_pa = page_table + vpn * PTE_SIZE
        pte = int.from_bytes(_read_ram(ram, ram_base, ram_bytes,
                                       pte_pa, PTE_SIZE), 'little')
        v = bool(pte & 1)
        r = bool(pte & 2)
        w = bool(pte & 4)
        x = bool(pte & 8)
        u = bool(pte & 16)
        a = bool(pte & 64)
        d = bool(pte & 128)
        ppn = (pte >> 10) & ((1 << 44) - 1)
        step = {'level': level, 'pte_pa': f'0x{pte_pa:x}',
                'pte': f'0x{pte:x}', 'vpn': vpn, 'leaf': r or x}
        steps.append(step)
        if not v or (w and not r):
            raise WalkError(f'invalid Sv39 PTE at level {level} (V/R/W)')
        # No Svnapot/Svpbmt or future reserved-bit interpretation in this
        # bounded P1 source checker; the Product profile is base Sv39.
        if pte >> 54:
            raise WalkError(f'unsupported Sv39 high PTE bits at level {level}')
        if r or x:
            if not x or u or not a:
                raise WalkError(f'supervisor instruction page not executable/accessed at level {level}')
            lower_ppn_bits = 9 * level
            if lower_ppn_bits and (ppn & ((1 << lower_ppn_bits) - 1)):
                raise WalkError(f'misaligned Sv39 superpage at level {level}')
            phys_ppn = (ppn & ~((1 << lower_ppn_bits) - 1)) | (
                (va >> PAGE_BITS) & ((1 << lower_ppn_bits) - 1))
            physical = (phys_ppn << PAGE_BITS) | (va & (PAGE_SIZE - 1))
            # Only fetch instructions from the source's authenticated RAM.
            _read_ram(ram, ram_base, ram_bytes, physical, 2)
            return {'physical_pc': physical, 'leaf_level': level,
                    'pte_path': steps, 'leaf_pte': f'0x{pte:x}',
                    'dirty': d}
        if level == 0:
            raise WalkError('Sv39 level-0 PTE is a non-leaf pointer')
        # Non-leaf PTEs may legally set G (global mapping) and RSW[9:8];
        # only U/A/D are reserved in an otherwise valid pointer PTE.
        if pte & (16 | 64 | 128):
            raise WalkError(f'non-leaf Sv39 PTE has leaf-only flags at level {level}')
        page_table = ppn << PAGE_BITS
    raise WalkError('Sv39 page-table walk did not resolve a leaf')


def inspect(manifest, ram_path, expected_ram_bytes=preflight.RAM_BYTES):
    """Verify memory bytes then fetch a 16/32-bit instruction at recorded PC."""
    if manifest.get('qualification') != preflight.QUALIFICATION or (
            manifest.get('arch_restore_ready') is not False or
            manifest.get('rtl_cpu_injected') is not False):
        raise WalkError('source is not an unmodified diagnostic QEMU checkpoint')
    memory = manifest.get('memory', {})
    if not isinstance(memory, dict) or (
            preflight.parse_hex(memory.get('start'), 'RAM base') != preflight.RAM_BASE or
            type(memory.get('bytes')) is not int or memory.get('bytes') != expected_ram_bytes):
        raise WalkError('source RAM geometry incompatible with Product D2')
    if Path(ram_path).stat().st_size != expected_ram_bytes:
        raise WalkError('source RAM size differs from manifest')
    preflight.check_file(memory, ram_path, 'RAM')
    regs = manifest.get('qemu_gdb_registers', {})
    if not isinstance(regs, dict):
        raise WalkError('missing QEMU architectural register dictionary')
    va = preflight.parse_hex(regs.get('pc'), 'source PC')
    if va != preflight.parse_hex(manifest.get('breakpoint_pc'), 'breakpoint PC'):
        raise WalkError('source PC does not match breakpoint')
    if preflight.parse_hex(regs.get('priv'), 'source privilege') != 1:
        raise WalkError('source privilege must be S-mode')
    satp = preflight.parse_hex(regs.get('satp'), 'source satp')
    with Path(ram_path).open('rb') as ram:
        first = walk_exec(ram, preflight.RAM_BASE, expected_ram_bytes, satp, va)
        physical = first['physical_pc']
        first_half = _read_ram(ram, preflight.RAM_BASE,
                               expected_ram_bytes, physical, 2)
        size = 2 if (first_half[0] & 3) != 3 else 4
        if size == 2:
            raw = first_half
            second = None
        elif (va & (PAGE_SIZE - 1)) == PAGE_SIZE - 2:
            # A 32-bit RISC-V instruction can straddle distinct physical pages.
            second = walk_exec(ram, preflight.RAM_BASE,
                               expected_ram_bytes, satp, va + 2)
            raw = first_half + _read_ram(ram, preflight.RAM_BASE,
                                         expected_ram_bytes, second['physical_pc'], 2)
        else:
            raw = _read_ram(ram, preflight.RAM_BASE,
                            expected_ram_bytes, physical, 4)
            second = None
    return {
        'schema': 'aethercore.c1.sv39-source-walk.v1',
        'qualification': 'READ_ONLY_QEMU_SV39_INSTRUCTION_DIAGNOSTIC_NOT_RTL_PASS',
        'source_manifest_sha256': preflight.manifest_sha(manifest),
        'source_ram_sha256': memory['sha256'],
        'source_pc': f'0x{va:x}',
        'satp': f'0x{satp:x}',
        'satp_mode': satp >> 60,
        'translated_physical_pc': f'0x{physical:x}',
        'instruction_bytes_le': raw.hex(),
        'instruction_width_bytes': size,
        'first_page_walk': first,
        'second_page_walk': second,
        'rtl_warm_resume_permitted': False,
        'rtl_handoff_pass': False,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', required=True, type=Path)
    p.add_argument('--ram', required=True, type=Path)
    p.add_argument('--report', required=True, type=Path)
    args = p.parse_args()
    try:
        result = inspect(json.loads(args.manifest.read_text()), args.ram)
        with args.report.open('x', encoding='utf-8') as output:
            json.dump(result, output, sort_keys=True, indent=2)
            output.write('\n')
    except (ValueError, OSError) as exc:
        print('QEMU_SV39_SOURCE_WALK_REJECTED:', exc, file=sys.stderr)
        return 2
    print('QEMU_SV39_SOURCE_WALK_DIAGNOSTIC_ONLY physical_pc=' +
          result['translated_physical_pc'] + ' rtl_handoff_pass=0')
    return 0


if __name__ == '__main__':
    sys.exit(main())
