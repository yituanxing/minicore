"""Strict synthetic G5 Sv39 fixture checks; no assertion of RTL execution."""

import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from tools.ci.product_c1_arch_relay import p1_sv39_fixture as p1


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/ci/product_c1_arch_relay/build_p1_sv39_fixture.sh'


class ProductC1P1Sv39FixtureTest(unittest.TestCase):
    def test_satp_mret_and_s_mode_gprs(self):
        state = struct.unpack('<53Q', p1.make_state())
        self.assertEqual(len(state), 53)
        self.assertEqual(state[0], 0xFFFFFFFF80100000)
        self.assertEqual(state[1], 0x88)
        self.assertEqual(state[7], p1.GUEST_PC)
        self.assertEqual(state[15], (8 << 60) | (p1.ROOT_PA >> 12))
        self.assertEqual(state[19], 0x21FFFFFF)
        self.assertEqual(state[20], 0x1F)
        self.assertEqual(state[21 + 5], 0x5555555555555555)
        self.assertEqual(state[21 + 6], 0x6666666666666666)
        self.assertEqual(state[21 + 10], 0x80101000)
        self.assertEqual(state[21 + 11], 0xCAFEBABEDEADBEEF)
        self.assertEqual(state[21 + 12], 41)

    def test_high_kernel_fetch_and_low_marker_s_mode_translations(self):
        pages = p1.table_pages()
        self.assertEqual(p1.vpn(p1.GUEST_PC, 2), 510)
        self.assertEqual(p1.vpn(p1.MARKER_VA, 2), 2)
        self.assertEqual(p1.walk(p1.GUEST_PC, pages), 0x80100000)
        self.assertEqual(p1.walk(p1.GUEST_PC + 20, pages), 0x80100014)
        self.assertEqual(p1.walk(p1.MARKER_VA, pages), 0x80101000)
        self.assertEqual(p1.walk(p1.MARKER_VA + 24, pages), 0x80101018)
        root, high, low = pages
        self.assertEqual(struct.unpack_from('<Q', root, 510 * 8)[0],
                         ((p1.HIGH_L1_PA >> 12) << 10) | 1)
        self.assertEqual(struct.unpack_from('<Q', root, 2 * 8)[0],
                         ((p1.LOW_L1_PA >> 12) << 10) | 1)
        self.assertEqual(struct.unpack_from('<Q', high)[0], p1.LEAF_FLAGS |
                         ((p1.RAM_BASE >> 12) << 10))
        self.assertEqual(struct.unpack_from('<Q', low)[0], p1.LEAF_FLAGS |
                         ((p1.RAM_BASE >> 12) << 10))
        # A user page must not be accidentally granted; S-only flags U=0.
        self.assertEqual(p1.LEAF_FLAGS & (1 << 4), 0)

    def test_state_manifest_is_honest_and_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            subprocess.run(['python3', str(ROOT / 'tools/ci/product_c1_arch_relay/p1_sv39_fixture.py'),
                            '--out-dir', str(out)], check=True, capture_output=True)
            self.assertEqual((out / 'state-424.bin').read_bytes(), p1.make_state())
            m = json.loads((out / 'expected.json').read_text())
            self.assertFalse(m['rtl_handoff_pass'])
            self.assertIn('SYNTHETIC', m['qualification'])
            self.assertEqual(m['guest_virtual_pc'], hex(p1.GUEST_PC))
            self.assertEqual(m['guest_physical_pc'], hex(p1.PHYSICAL_PC))
            self.assertEqual(m['expected_nonfaulting_commits'], 5)

    def test_sparse_ram_contains_actual_page_table_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            state = p1.make_state()
            (out / 'restore-flat.bin').write_bytes(b'\0' * p1.STATE_OFFSET + state)
            guest = out / 'guest-flat.bin'
            guest.write_bytes(b'\x13\x00\x00\x00' + b'\0' * 0x101c)
            p1.finalize(out, guest)
            pages = p1.table_pages()
            with (out / 'ram-256m.bin').open('rb') as ram:
                for pa, page in zip((p1.ROOT_PA, p1.HIGH_L1_PA, p1.LOW_L1_PA), pages):
                    ram.seek(pa - p1.RAM_BASE)
                    self.assertEqual(ram.read(4096), page)
                ram.seek(p1.PHYSICAL_PC - p1.RAM_BASE)
                self.assertEqual(ram.read(0x1020), guest.read_bytes())
            self.assertEqual((out / 'ram-256m.bin').stat().st_size, p1.RAM_BYTES)

    def test_real_elf_cross_toolchain_if_installed(self):
        cross = os.getenv('AETHERCORE_RV64_LINUX_CROSS_COMPILE', 'riscv64-linux-gnu-')
        if not all(shutil.which(cross + name) for name in ('gcc', 'objcopy', 'nm', 'readelf')):
            self.skipTest('RV64 GNU cross toolchain absent; real fixture link runs in G5 CI')
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, AETHERCORE_C1_P1_SV39_OUT=d)
            run = subprocess.run(['bash', str(SCRIPT)], env=env,
                                 check=True, capture_output=True, text=True)
            self.assertIn('BUILT_NOT_RTL_PASS', run.stdout)
            out = Path(d)
            image = (out / 'restore-flat.bin').read_bytes()
            self.assertEqual(image[p1.STATE_OFFSET:p1.STATE_OFFSET + 424], p1.make_state())
            self.assertLess(len(image), 1024 * 1024)


if __name__ == '__main__':
    unittest.main()
