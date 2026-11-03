"""Old simulator acceleration interfaces migrated to the current PA32 virtual FPGA."""
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class Pa32AccelerationContracts(unittest.TestCase):
    def test_host_speed_matrix_same_workload_and_reference(self):
        path = ROOT / "tools/ci/rv64_pa32_host_acceleration.sh"
        subprocess.run(["bash", "-n", str(path)], check=True)
        content = path.read_text()
        for required in ("ElaborateV2VirtualFpgaBoardPA32RV64", "gcc-reference",
                         "gcc-adaptive", "clang-adaptive",
                         "AETHERCORE_SIM_ADAPTIVE_SETTLE",
                         "AETHERCORE_PA32_SPEED_STATE_MATCH",
                         "firmware.sha256", "diff -u"):
            self.assertIn(required, content)
        self.assertNotIn("gcc-os", content)

    def test_fork_reuses_historic_COW_and_interrupt_observer(self):
        source = (ROOT / "sim/pa32_checkpoint_forkserver_main.cpp").read_text()
        make = (ROOT / "Makefile.rv64-pa32-checkpoint").read_text()
        for required in ("VAetherCoreV2OpenSbiRV64SimTop.h", "top.prepareClone()",
                         "top.atClone()", "::fork()", "stats.seip > startSeip"):
            self.assertIn(required, source)
        self.assertIn("aethercore.ElaborateV2VirtualFpgaBoardPA32RV64", make)
        self.assertIn("REQUIRE_POST_CHECKPOINT_SEIP ?= 1", make)
        self.assertIn("Run /init as init process", make)
        self.assertIn("sim/pa32_checkpoint_forkserver_main.cpp", make)

    def test_full_cold_signoff_remains_separate(self):
        main = (ROOT / ".github/workflows/v2-soc-virtual-fpga-linux-pid1.yml").read_text()
        self.assertIn("MIN_STIP=1 MIN_SEIP=1", main)
        self.assertIn("Run real PID1 through virtual FPGA pins", main)
        for path in ("rv64-pa32-host-acceleration.yml", "rv64-pa32-checkpoint.yml"):
            workflow = (ROOT / ".github/workflows" / path).read_text()
            self.assertIn("cancel-in-progress: false", workflow)
            self.assertIn("tests_py.test_rv64_pa32_acceleration_contract", workflow)


if __name__ == "__main__":
    unittest.main()
