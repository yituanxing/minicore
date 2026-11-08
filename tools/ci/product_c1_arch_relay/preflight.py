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
CSRS = ('misa', 'mstatus', 'medeleg', 'mideleg', 'mie', 'mip', 'mtvec',
        'mscratch', 'mepc', 'mcause', 'mtval', 'stvec', 'sscratch',
        'sepc', 'scause', 'stval', 'satp', 'scounteren')
# RV64: each 64-bit pmpcfg CSR holds eight 8-bit configuration entries.
# pmpcfg1 and pmpcfg3 are not implemented/accessible on RV64.
PMP_REGISTERS = tuple(f'pmpaddr{i}' for i in range(16)) + (
    'pmpcfg0', 'pmpcfg2')
COUNTER_AND_WARL_CSRS = ('mcycle', 'minstret', 'mcountinhibit', 'menvcfg')
# ProductCoreProfiles.smallDebianTarget: RV64 IM A F D C + S/U.
# MachineCsrFile computes misa from the declared ISA, with no H extension.
# This is deliberately pinned to the actual G5 Debian hardware configuration;
# a new hardware target must update this contract deliberately.
PRODUCT_G5_DEBIAN_MISA = 0x800000000014112d


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


def manifest_sha(export):
    """Canonical evidence binding, independent of JSON whitespace/order."""
    payload = json.dumps(export, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(payload).hexdigest()


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
    source_misa = decoded.get('misa')
    misa_matches_product = source_misa == PRODUCT_G5_DEBIAN_MISA
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
    missing_pmp = sorted(set(PMP_REGISTERS) - decoded.keys())
    missing_counter_warl = sorted(set(COUNTER_AND_WARL_CSRS) - decoded.keys())
    # Product RTL does not implement mcycle/minstret architectural state and
    # implements mcountinhibit/menvcfg with different WARL semantics.  Source
    # coverage must remain visible, but never imply those values are writable.
    nonzero_counter_warl = sorted(name for name in COUNTER_AND_WARL_CSRS
                                  if decoded.get(name, 0) != 0)
    device_blockers = export.get('unexported_device_and_rtl_state')
    if not isinstance(device_blockers, list) or not device_blockers or any(
            not isinstance(item, str) or not item for item in device_blockers):
        reject('unexported device/RTL state must be explicitly recorded')
    return {
        'schema': 'aethercore.c1.arch-source-review.v1',
        'source_manifest_sha256': manifest_sha(export),
        'source_ram_sha256': memory['sha256'],
        'source_pc': f'0x{pc:x}',
        'source_misa': f'0x{source_misa:x}' if source_misa is not None else None,
        'product_g5_debian_misa': f'0x{PRODUCT_G5_DEBIAN_MISA:x}',
        'misa_exact_match_product_g5_debian': misa_matches_product,
        'misa_unexpected_bits': (f'0x{source_misa & ~PRODUCT_G5_DEBIAN_MISA:x}'
                                 if source_misa is not None else None),
        'misa_missing_bits': (f'0x{PRODUCT_G5_DEBIAN_MISA & ~source_misa:x}'
                              if source_misa is not None else None),
        'minimum_s_mode_register_subset_present': not missing,
        'missing_minimum_s_mode_registers': missing,
        'pmp_registers_present': not missing_pmp,
        'missing_pmp_registers': missing_pmp,
        'missing_counter_and_warl_csrs': missing_counter_warl,
        'nonzero_counter_and_warl_csrs': nonzero_counter_warl,
        'source_device_blockers': device_blockers,
        'rtl_handoff_pass': False,
        'rtl_injected': False,
        'blockers': [
            *([] if misa_matches_product else [
                'QEMU source misa differs from actual Product G5 Debian ISA; '
                'source and RTL cannot be called ISA-identical']),
            'RTL architectural restore port or qualified M-mode shim missing',
            'Product CSR WARL, PMP and read-only counter restore not qualified',
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
                         {name: getattr(args, name) for name in INPUTS},
                         expected_ram_bytes=RAM_BYTES)
        with args.report.open('x', encoding='utf-8') as output:
            json.dump(review, output, indent=2, sort_keys=True)
            output.write('\n')
    except (OSError, ValueError) as error:
        print('PRODUCT_C1_ARCH_SOURCE_REJECTED:', error, file=sys.stderr)
        return 2
    if not review['minimum_s_mode_register_subset_present']:
        print('PRODUCT_C1_ARCH_SOURCE_INCOMPLETE_NOT_RTL_RESUMED',
              'missing=' + str(len(review['missing_minimum_s_mode_registers'])),
              file=sys.stderr)
        return 3
    print('PRODUCT_C1_ARCH_SOURCE_SUBSET_REVIEWED_NOT_RTL_RESUMED',
          's_mode_subset=1 missing_pmp=' + str(len(review['missing_pmp_registers'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
