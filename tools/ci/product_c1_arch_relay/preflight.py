#!/usr/bin/env python3
"""Validate a QEMU architectural export without claiming RTL handoff.

Product RTL has no qualified architectural warm-import port.  This script
checks evidence, records missing architectural values and ALWAYS reports
rtl_handoff_pass=false.  It cannot be used to certify SD1 or C1 boot.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

RAM_BYTES = 256 * 1024 * 1024
RAM_BASE = 0x80000000
QUALIFICATION = 'QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS'
INPUTS = ('firmware', 'dtb', 'linux_image', 'system_map')
CSRS = ('mstatus', 'medeleg', 'mideleg', 'mie', 'mip', 'mtvec',
        'mscratch', 'mepc', 'mcause', 'mtval', 'stvec', 'sscratch',
        'sepc', 'scause', 'stval', 'satp', 'scounteren')


def reject(message):
    raise ValueError(message)


def parse_hex(text, label):
    if not isinstance(text, str) or not re.fullmatch(r'0x(?:0|[1-9a-f][0-9a-f]*)', text):
        reject(label + ': noncanonical RV64 hex')
    value = int(text, 16)
    if value >= (1 << 64):
        reject(label + ': overflow')
    return value


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def check_file(descriptor, path, name):
    if not isinstance(descriptor, dict) or not re.fullmatch(
            r'[0-9a-f]{64}', str(descriptor.get('sha256', ''))):
        reject(name + ': bad SHA-256 descriptor')
    if file_sha(path) != descriptor['sha256']:
        reject(name + ': source bytes differ from SHA-256')


def inspect(export, ram, source_paths, expected_ram_bytes=RAM_BYTES):
    """Review source only; small RAM override is for unit tests, never CLI."""
    if not isinstance(export, dict) or export.get('qualification') != QUALIFICATION:
        reject('unexpected QEMU source qualification')
    if export.get('arch_restore_ready') is not False or export.get('rtl_cpu_injected') is not False:
        reject('diagnostic QEMU source claims a nonexistent RTL import')
    if type(export.get('qemu_cpu_count')) is not int or export['qemu_cpu_count'] != 1:
        reject('only one-hart capture is in scope')
    memory = export.get('memory')
    if not isinstance(memory, dict) or parse_hex(memory.get('start'), 'RAM base') != RAM_BASE:
        reject('RAM physical base is not Product 0x80000000')
    if type(memory.get('bytes')) is not int or memory['bytes'] != expected_ram_bytes:
        reject('RAM aperture size differs from fixed Product size')
    if Path(ram).stat().st_size != expected_ram_bytes:
        reject('RAM binary size differs from manifest')
    check_file(memory, ram, 'RAM')
    inputs = export.get('inputs')
    if not isinstance(inputs, dict) or set(inputs) != set(INPUTS) or set(source_paths) != set(INPUTS):
        reject('must pin all four source firmware/DTB/Linux/System.map files')
    for name in INPUTS:
        check_file(inputs[name], source_paths[name], name)
    registers = export.get('qemu_gdb_registers')
    missing_source = export.get('missing_gdb_registers')
    if not isinstance(registers, dict) or not isinstance(missing_source, list) or any(
            not isinstance(x, str) for x in missing_source):
        reject('malformed QEMU register evidence')
    if len(set(missing_source)) != len(missing_source) or set(registers) & set(missing_source):
        reject('contradictory QEMU register availability')
    decoded = {name: parse_hex(raw, name) for name, raw in registers.items()}
    pc = parse_hex(export.get('breakpoint_pc'), 'breakpoint PC')
    if decoded.get('pc') != pc or pc & 1:
        reject('PC differs from aligned breakpoint')
    if 'x0' in decoded and decoded['x0'] != 0:
        reject('RISC-V x0 must be zero')
    if 'priv' in decoded and decoded['priv'] != 1:
        reject('first C1 capture must be in supervisor mode')
    needed = {'pc', 'priv', *CSRS, *(f'x{i}' for i in range(32))}
    mstatus = decoded.get('mstatus')
    if mstatus is None or ((mstatus >> 13) & 3):
        needed |= {'fcsr', *(f'f{i}' for i in range(32))}
    missing = sorted(needed - decoded.keys())
    device_blockers = export.get('unexported_device_and_rtl_state')
    if not isinstance(device_blockers, list) or not device_blockers or any(
            not isinstance(item, str) or not item for item in device_blockers):
        reject('unexported device/RTL state must be explicitly recorded')
    return {
        'schema': 'aethercore.c1.arch-source-review.v1',
        'source_ram_sha256': memory['sha256'],
        'source_pc': f'0x{pc:x}',
        'arch_registers_complete': not missing,
        'missing_arch_registers': missing,
        'source_device_blockers': device_blockers,
        'rtl_handoff_pass': False,
        'rtl_injected': False,
        'blockers': [
            'RTL architectural restore port or qualified M-mode shim missing',
            'QEMU/RTL platform, timer, PLIC, UART and LRSC equivalence unproved',
            'No post-import RTL retirement-diff evidence',
        ],
        'result': 'C1_SOURCE_REVIEWED_NOT_RTL_RESUMED',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--ram', required=True, type=Path)
    for name in INPUTS:
        parser.add_argument('--' + name.replace('_', '-'), required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    try:
        review = inspect(json.loads(args.manifest.read_text()), args.ram,
                         {name: getattr(args, name) for name in INPUTS})
        with args.report.open('x', encoding='utf-8') as output:
            json.dump(review, output, indent=2, sort_keys=True)
            output.write('\n')
    except (OSError, ValueError) as error:
        print('PRODUCT_C1_ARCH_SOURCE_REJECTED:', error, file=sys.stderr)
        return 2
    print('PRODUCT_C1_ARCH_SOURCE_REVIEWED_NOT_RTL_RESUMED',
          'arch_complete=' + str(int(review['arch_registers_complete'])),
          'missing=' + str(len(review['missing_arch_registers'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
