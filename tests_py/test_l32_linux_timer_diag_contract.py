from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxTimerDiagContractTest(unittest.TestCase):
    def test_opensbi_simtop_exports_observation_only_sstc_state(self):
        text = (ROOT / "src/main/scala/aethercore/sim/AetherCoreOpenSbiSimTop.scala").read_text()
        for required in (
            "linuxTimerDebug",
            "core.csrFile.io.currentPrivilege",
            "core.csrFile.sstc.get.io.compare",
            "core.csrFile.io.supervisorTimerPending.get",
            "core.csrFile.io.supervisorTimerInterrupt.get",
        ):
            self.assertIn(required, text)
        self.assertIn("observation-only", text)

    def test_runner_records_comparator_pending_and_qualification_transitions(self):
        text = (ROOT / "sim/opensbi_boot_main.cpp").read_text()
        for required in (
            "linuxTimerDebug_stimecmp",
            "linuxTimerDebug_privilege",
            "linuxTimerDebug_supervisorTimerPending",
            "linuxTimerDebug_supervisorTimerInterrupt",
            "L32_STIMECMP_UPDATE",
            "L32_STIP_PENDING",
            "L32_STIP_QUALIFIED",
            "stip-pending=",
            "stip-qualified=",
        ):
            self.assertIn(required, text)


if __name__ == "__main__":
    unittest.main()
