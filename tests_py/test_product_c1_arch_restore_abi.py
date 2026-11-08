"""Tiny pure-Python ABI tests; this does NOT qualify RTL execution."""

import struct
from pathlib import Path
import unittest

from tools.ci.product_c1_arch_relay import restore_abi
from tools.ci.product_c1_arch_relay.preflight import manifest_sha


ROOT = Path(__file__).resolve().parents[1]


class RestoreAbiTest(unittest.TestCase):
    def setUp(self):
        self.regs = {name: '0x0' for name in restore_abi.WORDS}
        self.regs.update({f'x{i}': f'0x{i:x}' for i in range(32)})
        self.regs['x0'] = '0x0'
        self.regs.update({f'pmpaddr{i}': '0x0' for i in range(16)})
        self.regs.update({f'pmpcfg{i}': '0x0' for i in range(4)})
        self.regs.update({'pc': '0x80400000', 'mepc': '0x80400000',
                          'priv': '0x1', 'mstatus': '0x88'})
        self.manifest = {'breakpoint_pc': '0x80400000',
                         'qemu_gdb_registers': self.regs}
        self.review = {'schema': 'aethercore.c1.arch-source-review.v1',
                       'minimum_s_mode_register_subset_present': True,
                       'source_pc': '0x80400000', 'rtl_handoff_pass': False}

    def pack(self):
        self.review['source_manifest_sha256'] = manifest_sha(self.manifest)
        return restore_abi.pack_p0(self.manifest, self.review)

    def test_struct_offsets_and_guest_assembly_layout_match(self):
        raw = self.pack()
        self.assertEqual(len(raw), 408)
        words = struct.unpack('<51Q', raw)
        self.assertEqual(words[0], 0x80400000)
        self.assertEqual(words[1], 0x88)
        self.assertEqual(words[7], 0x80400000)
        self.assertEqual(words[19], 0)
        self.assertEqual(words[20], 1)
        self.assertEqual(words[-1], 31)
        asm = (ROOT / 'sim/product_c1_arch_restore.S').read_text()
        self.assertIn('.equ C1_GPR,       152', asm)
        self.assertIn('.equ C1_MIP,       144', asm)
        self.assertIn('ld x6, C1_GPR+48(x5)', asm)
        self.assertIn('ld x5, C1_GPR+40(x5)', asm)
        self.assertLess(asm.rindex('fence.i'), asm.rindex('mret'))

    def test_mret_requires_exact_mstatus_and_mepc(self):
        for field, value, why in (
            ('mstatus', '0x80', 'MRET'),
            ('mstatus', '0x2088', 'FPU'),
            ('mepc', '0x80000000', 'mepc'),
        ):
            before = self.regs[field]
            self.regs[field] = value
            with self.subTest(field=field, value=value):
                with self.assertRaisesRegex(ValueError, why):
                    self.pack()
            self.regs[field] = before

    def test_rejects_irq_mmu_or_unknown_pmp_state(self):
        for field, value in (('mie', '0x20'), ('mip', '0x20'),
                             ('satp', '0x8000000000000001'), ('pmpcfg0', '0x1')):
            before = self.regs[field]
            self.regs[field] = value
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    self.pack()
            self.regs[field] = before
        del self.regs['pmpcfg0']
        with self.assertRaisesRegex(ValueError, 'pmpcfg0'):
            self.pack()

    def test_never_accepts_a_false_rtl_pass(self):
        self.review['rtl_handoff_pass'] = True
        with self.assertRaisesRegex(ValueError, 'falsely claims RTL PASS'):
            self.pack()

    def test_mutated_manifest_is_rejected_after_review(self):
        self.review['source_manifest_sha256'] = manifest_sha(self.manifest)
        self.regs['x1'] = '0x99'
        with self.assertRaisesRegex(ValueError, 'manifest has changed'):
            restore_abi.pack_p0(self.manifest, self.review)


if __name__ == '__main__':
    unittest.main()
