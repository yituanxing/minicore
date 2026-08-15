from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32OpenSBIExecContractTest(unittest.TestCase):
    def test_sim_top_reuses_frozen_devices_with_composed_sv32_pmp_profile(self):
        text = (ROOT / "src/main/scala/aethercore/sim/AetherCoreOpenSbiSimTop.scala").read_text()
        self.assertIn("CoreProfiles.rv32imasuSv32PmpSoftware", text)
        self.assertNotIn("CoreProfiles.rv32imasuSv32Software,", text)
        self.assertIn("withNs16550Uart = true", text)
        self.assertIn("withMachineInterruptPlatform = false", text)
        self.assertIn("stopOnTrap = false", text)
        self.assertIn("stopOnWfi = false", text)

    def test_probe_requires_real_opensbi_and_smode_payload_output(self):
        text = (ROOT / "sim/opensbi_boot_main.cpp").read_text()
        self.assertIn('OpenSBI v1.6', text)
        self.assertIn('Test payload running', text)
        self.assertIn('L32_OPENSBI_BANNER', text)
        self.assertIn('L32_OPENSBI_TEST_PAYLOAD_PASS', text)
        self.assertIn('L32_FIRST_EXCEPTION', text)
        self.assertIn('L32_OPENSBI_TIMEOUT', text)

    def test_makefile_uses_frozen_payload_without_rebuilding_software(self):
        text = (ROOT / "Makefile.l32-opensbi-probe").read_text()
        self.assertIn("fw_payload.bin", text)
        self.assertIn("aethercore.ElaborateOpenSbi", text)
        self.assertIn("sim/opensbi_boot_main.cpp", text)
        self.assertIn("MAX_CYCLES ?= 10000000", text)
        self.assertNotIn("git clone", text)
        self.assertNotIn("linux", text.lower())


if __name__ == "__main__":
    unittest.main()
