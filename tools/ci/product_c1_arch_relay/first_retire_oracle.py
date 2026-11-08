#!/usr/bin/env python3
"""Reference-only first RVC retirement from a SHA-bound Sv39 QEMU export.

No RTL is executed or restored here.  Intentionally supports only the first
observed Linux instruction form, C.ADDI16SP, so an unexpected instruction
must fail closed instead of silently producing a misleading golden result.
"""

import argparse
import json
from pathlib import Path
import sys

try:
    from . import preflight
except ImportError:
    import preflight


MASK64 = (1 << 64) - 1
SOURCE_WALK_QUALIFICATION = 'READ_ONLY_QEMU_SV39_INSTRUCTION_DIAGNOSTIC_NOT_RTL_PASS'


def c_addi16sp_imm(instruction):
    """Decode C.ADDI16SP NZIMM[9|4|6|8:7|5], sign extended from bit 9."""
    if type(instruction) is not int or not 0 <= instruction < (1 << 16):
        raise ValueError('expected a 16-bit RVC instruction')
    if (instruction & 3) != 1 or ((instruction >> 13) & 7) != 3 or (
            (instruction >> 7) & 31) != 2:
        raise ValueError('first instruction is not C.ADDI16SP')
    immediate = (((instruction >> 12) & 1) << 9 |
                 ((instruction >> 6) & 1) << 4 |
                 ((instruction >> 5) & 1) << 6 |
                 ((instruction >> 3) & 3) << 7 |
                 ((instruction >> 2) & 1) << 5)
    if immediate == 0:
        raise ValueError('reserved C.ADDI16SP zero immediate')
    return immediate - 1024 if immediate & 512 else immediate


def derive(manifest, walk):
    if manifest.get('qualification') != preflight.QUALIFICATION or (
            manifest.get('rtl_cpu_injected') is not False or
            manifest.get('arch_restore_ready') is not False):
        raise ValueError('source is not a QEMU-only diagnostic checkpoint')
    if walk.get('qualification') != SOURCE_WALK_QUALIFICATION or (
            walk.get('rtl_handoff_pass') is not False or
            walk.get('rtl_warm_resume_permitted') is not False):
        raise ValueError('walk is not a non-RTL Sv39 source proof')
    if walk.get('source_manifest_sha256') != preflight.manifest_sha(manifest):
        raise ValueError('Sv39 walk is not bound to this exact QEMU manifest')
    memory = manifest.get('memory')
    if not isinstance(memory, dict) or walk.get('source_ram_sha256') != memory.get('sha256'):
        raise ValueError('Sv39 walk and source RAM SHA-256 do not match')
    regs = manifest.get('qemu_gdb_registers')
    if not isinstance(regs, dict):
        raise ValueError('missing architectural registers')
    pc = preflight.parse_hex(regs.get('pc'), 'source PC')
    if (pc != preflight.parse_hex(walk.get('source_pc'), 'walk PC') or
            pc != preflight.parse_hex(manifest.get('breakpoint_pc'), 'breakpoint PC')):
        raise ValueError('source and walk disagree on instruction PC')
    if preflight.parse_hex(regs.get('priv'), 'source privilege') != 1:
        raise ValueError('source must be S-mode')
    satp = preflight.parse_hex(regs.get('satp'), 'satp')
    if (satp >> 60 != 8 or
            satp != preflight.parse_hex(walk.get('satp'), 'walk satp')):
        raise ValueError('source and walk disagree on actual Sv39 satp')
    if walk.get('instruction_width_bytes') != 2:
        raise ValueError('first instruction must be exactly 16-bit RVC')
    raw = walk.get('instruction_bytes_le')
    if not isinstance(raw, str) or len(raw) != 4:
        raise ValueError('malformed first instruction byte evidence')
    try:
        instruction = int.from_bytes(bytes.fromhex(raw), 'little')
    except ValueError as exc:
        raise ValueError('malformed instruction hex') from exc
    immediate = c_addi16sp_imm(instruction)
    sp_before = preflight.parse_hex(regs.get('x2'), 'source x2/sp')
    return {
        'schema': 'aethercore.c1.first-retire-reference.v1',
        'qualification': 'QEMU_SOURCE_FIRST_RETIRE_ORACLE_NOT_PRODUCT_RTL_PASS',
        'source_manifest_sha256': preflight.manifest_sha(manifest),
        'source_ram_sha256': memory['sha256'],
        'source_pc': f'0x{pc:x}',
        'source_instruction_le': raw,
        'source_instruction': f'0x{instruction:04x}',
        'source_physical_pc': walk.get('translated_physical_pc'),
        'decoded': 'c.addi16sp sp, ' + str(immediate),
        'x2_before': f'0x{sp_before:x}',
        'x2_expected_after': f'0x{(sp_before + immediate) & MASK64:x}',
        'pc_expected_after': f'0x{(pc + 2) & MASK64:x}',
        'expected_retired_instruction_count': 1,
        'rtl_instruction_retired': False,
        'rtl_handoff_pass': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--source-walk', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    try:
        report = derive(json.loads(args.manifest.read_text()),
                        json.loads(args.source_walk.read_text()))
        with args.report.open('x', encoding='utf-8') as target:
            json.dump(report, target, sort_keys=True, indent=2)
            target.write('\n')
    except (OSError, ValueError) as exc:
        print('QEMU_FIRST_RETIRE_REFERENCE_REJECTED:', exc, file=sys.stderr)
        return 2
    print('QEMU_FIRST_RETIRE_REFERENCE_ONLY pc=' + report['source_pc'] +
          ' expected_pc=' + report['pc_expected_after'] +
          ' rtl_handoff_pass=0')
    return 0


if __name__ == '__main__':
    sys.exit(main())
