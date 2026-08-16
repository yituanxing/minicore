from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cFreeRtosProbeContract(unittest.TestCase):
    def test_existing_freertos_defaults_remain_rv32im(self):
        makefile = (ROOT / "Makefile.freertos").read_text()
        self.assertIn("FREERTOS_MARCH ?= rv32im_zicsr", makefile)
        self.assertIn("TOP ?= AetherCoreRV32IMTrapSimTop", makefile)
        self.assertIn("ELABORATE_MAIN ?= aethercore.ElaborateRV32IMTrap", makefile)
        self.assertIn("-march=$(FREERTOS_MARCH)", makefile)

    def test_imc_probe_changes_only_isa_and_trap_top_profile(self):
        probe = (ROOT / "Makefile.freertos-rv32imc").read_text()
        self.assertIn("FREERTOS_MARCH := rv32imc_zicsr", probe)
        self.assertNotIn("rv32imac", probe)
        self.assertIn("TOP := AetherCoreRV32IMCTrapSimTop", probe)
        self.assertIn("ELABORATE_MAIN := aethercore.ElaborateRV32IMCTrap", probe)
        self.assertIn("include Makefile.freertos-difftest", probe)

    def test_local_reset_bootstrap_remains_explicitly_non_rvc(self):
        startup = (ROOT / "software/freertos/aethercore/startup.S").read_text()
        self.assertIn(".option norvc", startup)
        probe = (ROOT / "Makefile.freertos-rv32imc").read_text()
        self.assertIn("startup_compressed", probe)
        self.assertIn('test "$$startup" -eq 0', probe)
        self.assertIn("kernel_c_compressed", probe)
        self.assertIn("app_c_compressed", probe)

    def test_imc_trap_top_preserves_machine_interrupt_platform(self):
        top = (ROOT / "src/main/scala/aethercore/sim/AetherCoreRV32IMCTrapSimTop.scala").read_text()
        elaborate = (ROOT / "src/main/scala/aethercore/ElaborateRV32IMCTrap.scala").read_text()
        self.assertIn("CoreProfiles.rv32imcSoftware", top)
        self.assertIn("stopOnTrap = false", top)
        self.assertIn("withMachineInterruptPlatform = true", top)
        self.assertIn("new AetherCoreRV32IMCTrapSimTop", elaborate)


if __name__ == "__main__":
    unittest.main()
