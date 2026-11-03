"""Old simulator acceleration interfaces migrated to the current PA32 virtual FPGA."""
import pathlib
import subprocess
import tempfile
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
        self.assertIn("PA32_CHECKPOINT_REPLAY_ON_FAILURE", source)
        self.assertIn("PA32_CHECKPOINT_DIAGNOSTIC_REPLAY_START", source)
        self.assertIn("PA32_CHECKPOINT_DIAGNOSTIC_REPLAY_RESULT", source)
        self.assertIn("PA32_CHECKPOINT_REPLAY_COMMIT", source)
        self.assertIn("if (replayOnFailure)", source)
        self.assertIn("if (diagnosticTrace && top.io_commit_valid)", source)
        self.assertIn("top.prepareClone()", source)
        self.assertIn("const int rc = forkChild(index, false)", source)
        self.assertIn("const int replayRc = forkChild(childCount + index, true)", source)


    def test_fork_verifier_accepts_actual_result_line_format(self):
        # V2_CHECKPOINT_PASS deliberately does not contain passed= or failed=.
        # These counts belong to V2_CHECKPOINT_RESULT.
        sample = """
V2_CHECKPOINT_READY cycles=425018087 commits=134150862 interrupts=8475 stip=4237 seip=0 trigger=Run /init as init process children=2
V2_CHECKPOINT_CHILD_PASS index=0 delta-cycles=705388 delta-commits=214436 interrupts-delta=19 stip-delta=9 seip-delta=1
V2_CHECKPOINT_CHILD_PASS index=1 delta-cycles=705388 delta-commits=214436 interrupts-delta=19 stip-delta=9 seip-delta=1
V2_CHECKPOINT_RESULT children=2 passed=2 failed=0 checkpoint-cycles=425018087
V2_CHECKPOINT_PASS children=2 checkpoint-cycles=425018087
"""
        checker = ROOT / "tools/ci/rv64_pa32_checkpoint_verify.py"
        with tempfile.TemporaryDirectory() as d:
            logfile = pathlib.Path(d) / "checkpoint.log"
            logfile.write_text(sample)
            ok = subprocess.run(["python3", str(checker), str(logfile)], capture_output=True, text=True)
            self.assertEqual(ok.returncode, 0, ok.stderr)
            self.assertIn("PA32_CHECKPOINT_DETERMINISTIC_REPLAY_PASS", ok.stdout)
            logfile.write_text(sample.replace("index=1 delta-cycles=705388", "index=1 delta-cycles=705389"))
            bad = subprocess.run(["python3", str(checker), str(logfile)], capture_output=True, text=True)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("diverged", bad.stderr)

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
