from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
RUNNER = ROOT / "sim" / "opensbi_boot_main.cpp"
C_TOP = ROOT / "src" / "main" / "scala" / "aethercore" / "sim" / "AetherCoreOpenSbiCSimTop.scala"
C_ELAB = ROOT / "src" / "main" / "scala" / "aethercore" / "ElaborateOpenSbiC.scala"
WORKFLOW = ROOT / ".github" / "workflows" / "rv32c-linux-kernel.yml"


class Rv32CLinuxRuntimeContractTest(unittest.TestCase):
    def test_c_top_is_structurally_identical_to_frozen_shell_except_profile(self) -> None:
        text = C_TOP.read_text()
        self.assertIn("extends AetherCoreSimTop(", text)
        self.assertIn("CoreProfiles.rv32imacsuSv32PmpSoftware", text)
        self.assertIn("stopOnTrap = false", text)
        self.assertIn("withMachineInterruptPlatform = false", text)
        self.assertIn("withSupervisorInterruptPlatform = true", text)
        self.assertIn("stopOnWfi = false", text)
        self.assertIn("withNs16550Uart = true", text)
        self.assertIn("supervisorPlicSourceCount = 52", text)
        self.assertIn("supervisorUartSourceId = 10", text)

    def test_c_elaboration_is_a_peer_entry_point(self) -> None:
        text = C_ELAB.read_text()
        self.assertIn("object ElaborateOpenSbiC extends App", text)
        self.assertIn("new AetherCoreOpenSbiCSimTop", text)

    def test_makefile_preserves_historical_defaults_and_exposes_opt_in(self) -> None:
        text = MAKEFILE.read_text()
        self.assertIn("TOP ?= AetherCoreOpenSbiSimTop", text)
        self.assertIn("ELABORATE_MAIN ?= aethercore.ElaborateOpenSbi", text)
        self.assertIn("REQUIRE_LAYERED_COMPRESSED ?= 0", text)
        self.assertIn("aethercore.runMain $(ELABORATE_MAIN)", text)
        self.assertIn('"$(REQUIRE_LAYERED_COMPRESSED)"', text)
        self.assertIn("L32_LAYERED_COMPRESSED_PASS", text)

    def test_shared_runner_has_explicit_c_top_and_layered_oracle(self) -> None:
        text = RUNNER.read_text()
        self.assertIn("#ifdef AETHERCORE_L32_C_TOP", text)
        self.assertIn("using OpenSbiTop = VAetherCoreOpenSbiCSimTop;", text)
        self.assertIn("using OpenSbiTop = VAetherCoreOpenSbiSimTop;", text)
        self.assertIn("kLinuxPayloadBase = 0x80400000ULL", text)
        self.assertIn("top.io_commit_instBytes", text)
        self.assertIn("opensbiCompressedCommits", text)
        self.assertIn("linuxCompressedCommits", text)
        self.assertIn("L32_FIRST_OPENSBI_COMPRESSED", text)
        self.assertIn("L32_FIRST_LINUX_COMPRESSED", text)
        self.assertIn("L32_LAYERED_COMPRESSED_PASS", text)
        self.assertIn("L32_LAYERED_COMPRESSED_MISSING", text)

    def test_workflow_requires_dynamic_c_in_both_software_layers(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("TOP=AetherCoreOpenSbiCSimTop", text)
        self.assertIn("ELABORATE_MAIN=aethercore.ElaborateOpenSbiC", text)
        self.assertIn("-DAETHERCORE_L32_C_TOP", text)
        self.assertIn("REQUIRE_LAYERED_COMPRESSED=1", text)
        self.assertIn("Linux version 6.6.143", text)


if __name__ == "__main__":
    unittest.main()
