"""Static and DDR-only checks; never a substitute for real RTL evidence."""

import json
from pathlib import Path
import tempfile
import unittest

from tools.ci.product_c1_arch_relay import g5_direct_seed_fixture as fixture
from tools.ci.product_c1_arch_relay import p1_rvc_fixture as rvc


ROOT = Path(__file__).resolve().parents[1]


class ProductC1G5DirectSeedFixtureTest(unittest.TestCase):
    def test_direct_source_opcode_and_page_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            fixture.prepare(output)
            manifest = json.loads((output / 'expected.json').read_text())
            self.assertFalse(manifest['rtl_handoff_pass'])
            self.assertFalse(manifest['mret_executed'])
            self.assertFalse(manifest['guest_ddr_overlaid'])
            self.assertFalse(manifest['source_is_full_qemu_snapshot'])
            self.assertIn('SYNTHETIC', manifest['qualification'])
            self.assertEqual((output / 'ram-256m.bin').stat().st_size,
                             rvc.RAM_BYTES)
            with (output / 'ram-256m.bin').open('rb') as guest:
                guest.seek(rvc.PHYSICAL_PC - rvc.RAM_BASE)
                self.assertEqual(guest.read(6), bytes.fromhex('7971f3260030'))
                for physical, page in zip((rvc.ROOT_PA, rvc.HIGH_L1_PA),
                                          rvc.table_pages()):
                    guest.seek(physical - rvc.RAM_BASE)
                    self.assertEqual(guest.read(4096), page)

    def test_warm_port_is_optional_and_production_top_unmodified(self):
        base = ROOT / 'src/main/scala/aethercore'
        top = (base / 'sim/AetherCoreProductUnifiedMemoryCompatSimTop.scala').read_text()
        core = (base / 'core/product/ProductSmallPagedCore.scala').read_text()
        self.assertIn('experimentalWarmSeed: Boolean = false', top)
        self.assertIn('class AetherCoreProductDebianWarmSeedRV64SimTop', top)
        self.assertIn('enableWarmSeed: Boolean = false', core)
        self.assertIn('assert(warmHold && cleanBoundary', core)


if __name__ == '__main__':
    unittest.main()
