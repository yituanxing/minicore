"""Synthetic Product C1 P0 restore image contract; never an RTL PASS."""

import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from tools.ci.product_c1_arch_relay import p0_fixture, restore_abi


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/ci/product_c1_arch_relay/build_p0_fixture.sh'


class ProductC1P0FixtureTest(unittest.TestCase):
    def test_synthetic_abi_matches_real_asm_table(self):
        state = p0_fixture.make_state()
        self.assertEqual(len(state), 424)
        words = struct.unpack('<53Q', state)
        self.assertEqual(words[0], p0_fixture.TARGET_PC)
        self.assertEqual(words[1], 0x88)
        self.assertEqual(words[7], p0_fixture.TARGET_PC)
        self.assertEqual(words[19], restore_abi.P0_DDR_NAPOT_ADDR0)
        self.assertEqual(words[20], restore_abi.P0_DDR_NAPOT_CFG0)
        self.assertEqual(words[21 + 5], p0_fixture.SOURCE_X5)
        self.assertEqual(words[21 + 6], p0_fixture.SOURCE_X6)
        self.assertEqual(words[21 + 10], p0_fixture.MARKER_ADDRESS)
        self.assertEqual(words[21 + 11], p0_fixture.SOURCE_X11)
        self.assertEqual(words[21 + 12], p0_fixture.SOURCE_X12)

    def test_overlay_and_target_are_disjoint(self):
        self.assertEqual(p0_fixture.RAM_BASE, 0x80000000)
        self.assertEqual(p0_fixture.OVERLAY_RESERVED_BYTES, 0x100000)
        self.assertEqual(p0_fixture.TARGET_PC, 0x80100000)
        self.assertEqual(p0_fixture.MARKER_ADDRESS, 0x80101000)
        self.assertEqual(p0_fixture.STATE_OFFSET, 0x18000)
        self.assertLess(p0_fixture.STATE_OFFSET + restore_abi.BYTE_COUNT,
                        p0_fixture.OVERLAY_RESERVED_BYTES)
        self.assertGreaterEqual(p0_fixture.TARGET_PC,
                                p0_fixture.RAM_BASE + p0_fixture.OVERLAY_RESERVED_BYTES)
        self.assertLess(p0_fixture.MARKER_ADDRESS, p0_fixture.RAM_BASE + p0_fixture.RAM_BYTES)
        ld = (ROOT / 'sim/product_c1_arch_p0.ld').read_text()
        for value in ('0x80000000', '0x80018000', '0x80100000', '0x80101000'):
            self.assertIn(value, ld)

    def test_fixture_has_inspectable_register_and_memory_effects(self):
        asm = (ROOT / 'sim/product_c1_arch_p0_fixture.S').read_text()
        for mnemonic in ('sd x5, 0(x10)', 'sd x6, 8(x10)',
                         'sd x11, 16(x10)', 'addi x12, x12, 1',
                         'sd x12, 24(x10)'):
            self.assertIn(mnemonic, asm)
        self.assertEqual(p0_fixture.EXPECTED_MARKERS,
                         (p0_fixture.SOURCE_X5, p0_fixture.SOURCE_X6,
                          p0_fixture.SOURCE_X11, 42))

    def test_diagnostic_outputs_are_deterministic_and_not_a_pass(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            subprocess.run(['python3', str(ROOT / 'tools/ci/product_c1_arch_relay/p0_fixture.py'),
                            '--out-dir', str(out)], check=True, capture_output=True)
            self.assertEqual((out / 'state-424.bin').read_bytes(), p0_fixture.make_state())
            manifest = json.loads((out / 'expected.json').read_text())
            self.assertEqual(manifest['target_pc'], '0x80100000')
            self.assertEqual(manifest['state_bytes'], 424)
            self.assertFalse(manifest['rtl_handoff_pass'])
            self.assertIn('SYNTHETIC', manifest['qualification'])

    def test_sparse_guest_ram_stays_separate_from_restore_overlay(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            state = p0_fixture.make_state()
            (out / 'restore-flat.bin').write_bytes(
                b'\x00' * p0_fixture.STATE_OFFSET + state)
            guest = out / 'guest-flat.bin'
            guest_bytes = b'\x13\x00\x00\x00' + b'\x00' * (0x1020 - 4)
            guest.write_bytes(guest_bytes)
            p0_fixture.finalize(out, guest)
            with (out / 'ram-256m.bin').open('rb') as ram:
                self.assertEqual(ram.read(32), b'\x00' * 32)
                ram.seek(p0_fixture.GUEST_OFFSET)
                self.assertEqual(ram.read(len(guest_bytes)), guest_bytes)
            self.assertEqual((out / 'ram-256m.bin').stat().st_size,
                             p0_fixture.RAM_BYTES)

    def test_compiled_fixture_when_cross_toolchain_available(self):
        cross = os.environ.get('AETHERCORE_RV64_LINUX_CROSS_COMPILE', 'riscv64-linux-gnu-')
        if not all(shutil.which(cross + cmd) for cmd in ('gcc', 'objcopy', 'nm', 'readelf')):
            self.skipTest('RV64 GNU cross toolchain absent; CI builds the real ELF')
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, AETHERCORE_C1_P0_FIXTURE_OUT=d)
            run = subprocess.run(['bash', str(SCRIPT)], env=env, check=True,
                                 capture_output=True, text=True)
            self.assertIn('BUILT_NOT_RTL_RESUMED', run.stdout)
            out = Path(d)
            self.assertEqual((out / 'ram-256m.bin').stat().st_size,
                             p0_fixture.RAM_BYTES)
            self.assertLess((out / 'restore-flat.bin').stat().st_size,
                            p0_fixture.OVERLAY_RESERVED_BYTES)
            self.assertEqual((out / 'restore-flat.bin').read_bytes()[
                p0_fixture.STATE_OFFSET:p0_fixture.STATE_OFFSET + 424],
                p0_fixture.make_state())
            with (out / 'ram-256m.bin').open('rb') as ram:
                ram.seek(p0_fixture.GUEST_OFFSET)
                self.assertEqual(ram.read((out / 'guest-flat.bin').stat().st_size),
                                 (out / 'guest-flat.bin').read_bytes())


if __name__ == '__main__':
    unittest.main()
