"""Independent synthetic first Linux RVC source-opcode G5 fixture tests."""

import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from tools.ci.product_c1_arch_relay import first_retire_oracle
from tools.ci.product_c1_arch_relay import p1_rvc_fixture as rvc


ROOT = Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / 'tools/ci/product_c1_arch_relay/build_p1_rvc_fixture.sh'


class ProductC1P1RvcFixtureTest(unittest.TestCase):
    def test_exact_first_linux_source_oracle_values(self):
        self.assertEqual(rvc.VIRTUAL_PC, 0xFFFFFFFF808C3490)
        self.assertEqual(rvc.PHYSICAL_PC, 0x80AC3490)
        self.assertEqual(rvc.INITIAL_SP, 0xFFFFFFC80000BEC0)
        self.assertEqual(rvc.EXPECTED_SP, 0xFFFFFFC80000BE90)
        self.assertEqual(rvc.EXPECTED_NEXT_PC, 0xFFFFFFFF808C3492)
        self.assertEqual(rvc.INSTRUCTION_AND_SENTINEL, bytes.fromhex('7971f3260030'))
        self.assertEqual(first_retire_oracle.c_addi16sp_imm(rvc.FIRST_OPCODE), -48)

    def test_sv39_level1_mapping_matches_kernel_pc_physical_offset(self):
        self.assertEqual(rvc.VIRTUAL_PC & ((1 << 21) - 1), 0xC3490)
        self.assertEqual(rvc.PHYSICAL_PC & ((1 << 21) - 1), 0xC3490)
        root, l1 = rvc.table_pages()
        self.assertEqual(struct.unpack_from('<Q', root, 510 * 8)[0],
                         ((rvc.HIGH_L1_PA >> 12) << 10) | 1)
        self.assertEqual(struct.unpack_from('<Q', l1, 4 * 8)[0],
                         ((rvc.LEAF_PHYSICAL_BASE >> 12) << 10) | 0xCF)
        self.assertEqual(struct.unpack_from('<Q', l1, 0)[0], 0)

    def test_synthetic_mret_state_and_initial_sp(self):
        words = struct.unpack('<53Q', rvc.make_state())
        self.assertEqual(words[0], rvc.VIRTUAL_PC)
        self.assertEqual(words[1], 0x88)
        self.assertEqual(words[7], rvc.VIRTUAL_PC)
        self.assertEqual(words[15], rvc.SATP)
        self.assertEqual(words[19], 0x21FFFFFF)
        self.assertEqual(words[20], 0x1F)
        self.assertEqual(words[21 + 2], rvc.INITIAL_SP)
        self.assertEqual(sum(v != 0 for v in words[21:]), 1)

    def test_fixture_ram_and_manifest_never_pretend_linux_handoff(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            subprocess.run(['python3', str(ROOT / 'tools/ci/product_c1_arch_relay/p1_rvc_fixture.py'),
                            '--out-dir', str(out)], capture_output=True, check=True)
            manifest = json.loads((out / 'expected.json').read_text())
            self.assertFalse(manifest['rtl_handoff_pass'])
            self.assertIn('SYNTHETIC', manifest['qualification'])
            self.assertEqual(manifest['first_instruction_le'], '7971')
            self.assertEqual(manifest['expected_next_pc'], hex(rvc.EXPECTED_NEXT_PC))
            (out / 'restore-flat.bin').write_bytes(
                bytes(rvc.STATE_OFFSET) + rvc.make_state())
            rvc.finalize(out)
            with (out / 'ram-256m.bin').open('rb') as ram:
                ram.seek(rvc.PHYSICAL_PC - rvc.RAM_BASE)
                self.assertEqual(ram.read(6), rvc.INSTRUCTION_AND_SENTINEL)
                for pa, page in zip((rvc.ROOT_PA, rvc.HIGH_L1_PA), rvc.table_pages()):
                    ram.seek(pa - rvc.RAM_BASE)
                    self.assertEqual(ram.read(4096), page)
            self.assertEqual((out / 'ram-256m.bin').stat().st_size, rvc.RAM_BYTES)

    def test_cross_compiled_restore_when_toolchain_available(self):
        cross = os.getenv('AETHERCORE_RV64_LINUX_CROSS_COMPILE', 'riscv64-linux-gnu-')
        if not all(shutil.which(cross + n) for n in ('gcc', 'objcopy', 'nm', 'readelf')):
            self.skipTest('RV64 GNU cross toolchain absent; CI verifies actual ELF')
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, AETHERCORE_C1_P1_RVC_OUT=d)
            run = subprocess.run(['bash', str(BUILD_SCRIPT)], env=env,
                                 check=True, text=True, capture_output=True)
            self.assertIn('RVC_FIXTURE_BUILT_NOT_RTL_PASS', run.stdout)
            restore = (Path(d) / 'restore-flat.bin').read_bytes()
            self.assertEqual(restore[rvc.STATE_OFFSET:rvc.STATE_OFFSET + 424],
                             rvc.make_state())


if __name__ == '__main__':
    unittest.main()
