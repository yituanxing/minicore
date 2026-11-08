"""Fail-closed P1 source eligibility: never a Product RTL resume PASS."""

import importlib.util
import struct
import sys
import tempfile
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/ci/product_c1_arch_relay'))
import p1_contract as p1
import preflight


def dtb_reserved(no_map=True, start=0x80000000, length=0x100000):
    names = ['#address-cells', '#size-cells', 'reg', 'no-map', 'ranges']
    strings = b''
    offsets = {}
    for name in names:
        offsets[name] = len(strings)
        strings += name.encode() + b'\0'

    def tok(value):
        return struct.pack('>I', value)

    def pad(data):
        return data + bytes((-len(data)) % 4)

    def begin(name):
        return tok(1) + pad(name.encode() + b'\0')

    def prop(name, data):
        return tok(3) + struct.pack('>II', len(data), offsets[name]) + pad(data)

    tree = (begin('') +
            prop('#address-cells', tok(2)) + prop('#size-cells', tok(2)) +
            begin('reserved-memory') +
            prop('#address-cells', tok(2)) + prop('#size-cells', tok(2)) +
            prop('ranges', b'') +
            begin('buffer@80000000') +
            prop('reg', struct.pack('>IIII', start >> 32, start & 0xffffffff,
                                     length >> 32, length & 0xffffffff)) +
            (prop('no-map', b'') if no_map else b'') +
            tok(2) + tok(2) + tok(2) + tok(9))
    reserve_map = bytes(16)
    off_struct = 40 + len(reserve_map)
    off_strings = off_struct + len(tree)
    size = off_strings + len(strings)
    header = struct.pack('>10I', p1.FDT_MAGIC, size, off_struct, off_strings,
                         40, 17, 16, 0, len(strings), len(tree))
    return header + reserve_map + tree + strings


class P1ContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ram = self.root / 'ram'
        self.ram.write_bytes(bytes(range(64)))
        self.sources = {}
        self.inputs = {}
        for name in preflight.INPUTS:
            path = self.root / name
            path.write_bytes(dtb_reserved() if name == 'dtb' else name.encode())
            self.sources[name] = path
            self.inputs[name] = {'sha256': preflight.file_sha(path)}
        regs = {'pc': '0xffffffff808c3490', 'priv': '0x1',
                'mstatus': '0xa00001880', 'satp': '0xa0000000000856fe'}
        regs.update({f'x{i}': f'0x{i:x}' for i in range(32)})
        regs['x0'] = '0x0'
        regs.update({name: '0x0' for name in preflight.CSRS if name not in regs})
        regs.update({name: '0x0' for name in preflight.PMP_REGISTERS})
        regs.update({name: '0x0' for name in preflight.COUNTER_AND_WARL_CSRS})
        regs.update({f'f{i}': '0x0' for i in range(32)})
        regs['fcsr'] = '0x0'
        self.export = {
            'qualification': preflight.QUALIFICATION,
            'arch_restore_ready': False, 'rtl_cpu_injected': False,
            'qemu_cpu_count': 1,
            'memory': {'start': '0x80000000', 'bytes': 64,
                       'sha256': preflight.file_sha(self.ram)},
            'inputs': self.inputs,
            'breakpoint_pc': regs['pc'],
            'qemu_gdb_registers': regs,
            'missing_gdb_registers': [],
            'unexported_device_and_rtl_state': ['PLIC', 'UART', 'CLINT'],
        }

    def audit(self, top=p1.D2_TOP):
        return p1.audit(self.export, self.ram, self.sources, top,
                        expected_ram_bytes=64)

    def test_parse_actual_shape_dtb_no_map(self):
        reservation = p1.reserved_overlay_check(self.sources['dtb'])
        self.assertTrue(reservation['reserved_no_map_covering_overlay'])
        self.assertEqual(reservation['overlapping_reserved_nodes'][0]['bytes'], 0x100000)

    def test_real_qemu_satp_sv57_is_rejected_for_sv39(self):
        out = self.audit()
        self.assertEqual(out['satp_mode'], 10)
        self.assertFalse(out['sv39_root_in_signed_ram'])
        self.assertIn('Actual source Sv39 satp missing or not active', out['blockers'])
        self.assertEqual(out['restore_plan'], 'NO_WRITE_DIAGNOSTIC_ONLY')
        self.assertFalse(out['rtl_warm_resume_permitted'])
        self.assertFalse(out['rtl_handoff_pass'])

    def test_sv39_nonzero_page_table_ppn_only_source_review(self):
        self.export['qemu_gdb_registers']['satp'] = '0x8000000000080000'
        out = self.audit()
        self.assertTrue(out['sv39_root_in_signed_ram'])
        self.assertIn('Sv39 page-table/PMP walk and TLB/cache warm-reset parity unproved',
                      out['blockers'])
        self.assertFalse(out['rtl_warm_resume_permitted'])

    def test_no_map_missing_or_partial_overlap_blocks(self):
        self.sources['dtb'].write_bytes(dtb_reserved(no_map=False))
        self.inputs['dtb']['sha256'] = preflight.file_sha(self.sources['dtb'])
        self.assertFalse(self.audit()['dtb_overlay']['reserved_no_map_covering_overlay'])
        self.sources['dtb'].write_bytes(dtb_reserved(start=0x80000000, length=0x40000))
        self.inputs['dtb']['sha256'] = preflight.file_sha(self.sources['dtb'])
        self.assertFalse(self.audit()['dtb_overlay']['reserved_no_map_covering_overlay'])

    def test_sd1_platform_mismatch_and_unavailable_csrs(self):
        del self.export['qemu_gdb_registers']['mip']
        self.export['missing_gdb_registers'] = ['mip']
        out = self.audit(p1.SD1_TOP)
        self.assertIn('QEMU D2 RAM-only source cannot restore the SD1 SPI-SD platform',
                      out['blockers'])
        self.assertIn('GDB lacks minimum S-mode architectural source registers',
                      out['blockers'])
        self.assertFalse(out['rtl_handoff_pass'])

    def test_corrupt_fdt_or_source_sha_rejected(self):
        self.sources['dtb'].write_bytes(b'not-a-device-tree')
        self.inputs['dtb']['sha256'] = preflight.file_sha(self.sources['dtb'])
        with self.assertRaisesRegex(ValueError, 'FDT header'):
            self.audit()
        self.sources['dtb'].write_bytes(dtb_reserved())
        with self.assertRaisesRegex(ValueError, 'source bytes differ'):
            self.audit()


if __name__ == '__main__':
    unittest.main()
