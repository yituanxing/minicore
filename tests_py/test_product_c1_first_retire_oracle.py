"""Source-only bounded first retirement oracle; never RTL success."""

import unittest

from tools.ci.product_c1_arch_relay import first_retire_oracle as oracle
from tools.ci.product_c1_arch_relay import preflight


class FirstRetireOracleTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            'qualification': preflight.QUALIFICATION,
            'arch_restore_ready': False,
            'rtl_cpu_injected': False,
            'breakpoint_pc': '0xffffffff808c3490',
            'memory': {'sha256': '0' * 64},
            'qemu_gdb_registers': {
                'pc': '0xffffffff808c3490', 'priv': '0x1',
                'satp': '0x80000000000856fe', 'x2': '0xffffffc80000bec0',
            },
        }
        self.walk = {
            'qualification': oracle.SOURCE_WALK_QUALIFICATION,
            'rtl_handoff_pass': False,
            'rtl_warm_resume_permitted': False,
            'source_ram_sha256': '0' * 64,
            'source_pc': '0xffffffff808c3490',
            'satp': '0x80000000000856fe',
            'instruction_width_bytes': 2,
            'instruction_bytes_le': '7971',
            'translated_physical_pc': '0x80ac3490',
        }
        self.walk['source_manifest_sha256'] = preflight.manifest_sha(self.manifest)

    def test_real_source_instruction_expected_first_retirement(self):
        result = oracle.derive(self.manifest, self.walk)
        self.assertEqual(result['decoded'], 'c.addi16sp sp, -48')
        self.assertEqual(result['source_instruction'], '0x7179')
        self.assertEqual(result['x2_expected_after'], '0xffffffc80000be90')
        self.assertEqual(result['pc_expected_after'], '0xffffffff808c3492')
        self.assertFalse(result['rtl_instruction_retired'])
        self.assertFalse(result['rtl_handoff_pass'])

    def test_reserved_c_addi16sp_and_other_insns_rejected(self):
        with self.assertRaisesRegex(ValueError, 'zero immediate'):
            oracle.c_addi16sp_imm(0x6101)
        with self.assertRaisesRegex(ValueError, 'not C.ADDI16SP'):
            oracle.c_addi16sp_imm(0x0001)

    def test_source_manifest_sha_and_ram_binding_mandatory(self):
        self.walk['source_manifest_sha256'] = 'a' * 64
        with self.assertRaisesRegex(ValueError, 'not bound'):
            oracle.derive(self.manifest, self.walk)
        self.walk['source_manifest_sha256'] = preflight.manifest_sha(self.manifest)
        self.walk['source_ram_sha256'] = '1' * 64
        with self.assertRaisesRegex(ValueError, 'RAM SHA-256'):
            oracle.derive(self.manifest, self.walk)

    def test_sv57_and_false_rtl_success_rejected(self):
        self.manifest['qemu_gdb_registers']['satp'] = '0xa0000000000856fe'
        self.walk['source_manifest_sha256'] = preflight.manifest_sha(self.manifest)
        with self.assertRaisesRegex(ValueError, 'Sv39 satp'):
            oracle.derive(self.manifest, self.walk)
        self.manifest['qemu_gdb_registers']['satp'] = '0x80000000000856fe'
        self.walk['source_manifest_sha256'] = preflight.manifest_sha(self.manifest)
        self.walk['rtl_handoff_pass'] = True
        with self.assertRaisesRegex(ValueError, 'non-RTL'):
            oracle.derive(self.manifest, self.walk)


if __name__ == '__main__':
    unittest.main()
