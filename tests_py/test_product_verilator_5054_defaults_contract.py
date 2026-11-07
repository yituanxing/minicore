"""Keep qualified Product defaults on Verilator 5.054 with an explicit rollback."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ProductVerilator5054DefaultsContract(unittest.TestCase):
    def test_sd1_manual_prefers_5054_but_fails_closed_without_ab(self):
        script = (ROOT / "tools/ci/product_sim_sd1_manual.sh").read_text()
        self.assertIn('AETHERCORE_VERILATOR_VERSION="${AETHERCORE_VERILATOR_VERSION:-5.054}"', script)
        self.assertIn('5.024:reference) sd1_fast_profile=0', script)
        self.assertIn('5.054:reference|5.054:adaptive) sd1_fast_profile=1', script)
        self.assertIn('verify_fast_profile_qualification', script)
        self.assertIn('AETHERCORE_SD1_AB_EVIDENCE', script)
        self.assertIn('PRODUCT_G5_SD1_SHORT_AB_PROFILE_VERIFIED', script)

    def test_manual_g5_workflow_prefers_5054_but_keeps_5024_option(self):
        workflow = (ROOT / ".github/workflows/product-g5-debian-rv64gc.yml").read_text()
        self.assertIn("options: ['5.054', '5.024']", workflow)
        self.assertIn("default: '5.054'", workflow)
        self.assertIn("|| inputs.verilator_version || '5.054'", workflow)
        self.assertIn("AETHERCORE_VERILATOR_VERSION=5.024", workflow)

    def test_frozen_sd1_relay_and_legacy_installer_are_not_retargeted(self):
        relay = (ROOT / ".github/workflows/product-sd1-official-artifact-relay.yml").read_text()
        installer = (ROOT / "tools/ensure_verilator_5_024.sh").read_text()
        self.assertIn("AETHERCORE_VERILATOR_VERSION: '5.054'", relay)
        self.assertIn("SIM_SETTLE_MODE: reference", relay)
        self.assertIn('version="${AETHERCORE_VERILATOR_VERSION:-5.024}"', installer)
        self.assertIn("TARGET_SHA: a15197f96e5869e69269202a0a9caffdbe1f5255", relay)


if __name__ == "__main__":
    unittest.main()
