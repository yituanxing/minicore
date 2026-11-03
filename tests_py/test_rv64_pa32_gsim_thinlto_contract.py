"""Single final ThinLTO experiment: exact firmware, strict oracle, bounded repeats."""
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/rv64_pa32_gsim_thinlto_ab.sh"

class ThinLtoContract(unittest.TestCase):
    def test_shell_and_bounded_abba(self):
        subprocess.run(["bash","-n",str(SCRIPT)],check=True)
        s=SCRIPT.read_text()
        self.assertIn("baseline-1 thinlto-1 thinlto-2 baseline-2",s)
        self.assertIn("CYCLES=20000000",s)
        self.assertIn('AETHERCORE_GSIM_PHASE_PROFILE=0',s)
        self.assertIn('-flto=thin -fuse-ld="$LTO_LINKER"',s)

    def test_every_trial_has_original_firmware_strict_oracle(self):
        s=SCRIPT.read_text()
        self.assertIn('cmp -s "$BASELINE_DIR/firmware.sha256" "$OUT/firmware.sha256"',s)
        self.assertIn("rv64_pa32_gsim_bare_diff.py",s)
        self.assertIn('"$OUT/$trial.log" "$REFERENCE" --firmware',s)
        self.assertIn("AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS",s)
        self.assertIn('|| exit 7',s)
        self.assertIn("trials=4 cycles=20000000",s)
        self.assertIn("NO_PROMOTION",s)

    def test_job_is_same_host_and_uploads_evidence(self):
        wf=(ROOT / ".github/workflows/rv64-pa32-gsim-host-bridge.yml").read_text()
        self.assertIn("Final bounded ThinLTO A-B-B-A",wf)
        self.assertIn("lld-19",wf)
        self.assertIn("rv64_pa32_gsim_thinlto_ab.sh",wf)
        self.assertIn("build/rv64-pa32-gsim-thinlto/measurements.tsv",wf)
        self.assertIn("tests_py.test_rv64_pa32_gsim_thinlto_contract",wf)
        self.assertIn("default: false",wf)

if __name__=="__main__":
    unittest.main()
