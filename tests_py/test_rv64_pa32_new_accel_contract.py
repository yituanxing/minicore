"""Fail-closed contracts for PA32 new simulator engines and version A/B."""
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class NewAccelContracts(unittest.TestCase):
    def test_bash_syntax_and_pin(self):
        for name in ("tools/ensure_verilator_5_054.sh",
                     "tools/ci/rv64_pa32_verilator_version_ab.sh",
                     "tools/ci/rv64_pa32_gsim_feasibility.sh"):
            subprocess.run(["bash", "-n", str(ROOT / name)], check=True)
        ensure = (ROOT/"tools/ensure_verilator_5_054.sh").read_text()
        self.assertIn('version="5.054"', ensure)
        self.assertIn("bbb60a403932a9f695cb6ff001ceb73de6ad9234", ensure)
        self.assertIn('prefix="$cache_root/verilator-$version"', ensure)

    def test_version_ab_same_rtl_same_workload(self):
        script = (ROOT/"tools/ci/rv64_pa32_verilator_version_ab.sh").read_text()
        for field in ("ElaborateV2VirtualFpgaBoardPA32RV64", "5.024", "5.054",
                      "AETHERCORE_SIM_ADAPTIVE_SETTLE", "sha256sum",
                      "diff -u", "AETHERCORE_PA32_VERILATOR_VERSION_STATE_MATCH"):
            self.assertIn(field, script)
        self.assertIn("clang", script)
        self.assertNotIn("make tinyconfig", script)

    def test_gsim_is_frontend_probe_not_linux_claim(self):
        source = (ROOT/"src/main/scala/aethercore/ElaborateV2VirtualFpgaBoardPA32CHIRRTL.scala").read_text()
        probe = (ROOT/"tools/ci/rv64_pa32_gsim_feasibility.sh").read_text()
        for required in ("ChiselStage.emitCHIRRTL", "implementedPaddrBits = 32",
                         "FIRRTL", "Files.writeString"):
            self.assertIn(required, source)
        self.assertIn("master-2026-10-30-45a73d6", probe)
        self.assertIn("c4af0822567e15a2d7322653b36b310206dc51c6c6c89e1ad2ff9c148d19e919", probe)
        self.assertIn("sha256sum -c -", probe)
        self.assertIn("AETHERCORE_PA32_GSIM_FRONTEND_PASS", probe)

    def test_original_cold_linux_remains_unchanged(self):
        cold = (ROOT/".github/workflows/v2-soc-virtual-fpga-linux-pid1.yml").read_text()
        self.assertIn("MIN_STIP=1 MIN_SEIP=1", cold)
        self.assertIn("Run real PID1 through virtual FPGA pins", cold)
        for name in ("rv64-pa32-verilator-version.yml",
                     "rv64-pa32-gsim-feasibility.yml"):
            workflow = (ROOT/".github/workflows"/name).read_text()
            self.assertIn("cancel-in-progress: false", workflow)
            self.assertIn("tests_py.test_rv64_pa32_new_accel_contract", workflow)

if __name__ == "__main__":
    unittest.main()
