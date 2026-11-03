"""Performance experiments must preserve the strict 20M differential oracle."""
import pathlib
import subprocess
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]

class PerformanceContracts(unittest.TestCase):
    def test_shell_syntax(self):
        for script in ("rv64_pa32_gsim_performance_sweep.sh","rv64_pa32_gsim_pgo_ab.sh"):
            subprocess.run(["bash","-n",str(ROOT/"tools/ci"/script)],check=True)

    def test_sweep_is_same_host_with_strict_differentials(self):
        source=(ROOT/"tools/ci/rv64_pa32_gsim_performance_sweep.sh").read_text()
        for token in ("build_size 35", "for size in 15 24 48",
                      "GSIM_SUPERNODE_MAX_SIZE=", "rv64_pa32_gsim_bare_diff.py",
                      "rv64_pa32_gsim_bare_diff.sh", "rv64_pa32_gsim_firmware_diff.sh",
                      "CYCLES=20000000",
                      "AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS",
                      "rv64_pa32_gsim_pgo_ab.sh"):
            self.assertIn(token,source)
        self.assertIn('VERILATOR_OUT="$reference"',source)
        self.assertIn("summary.tsv",source)

    def test_llvm_pgo_still_requires_real_oracle(self):
        source=(ROOT/"tools/ci/rv64_pa32_gsim_pgo_ab.sh").read_text()
        for token in ("-fprofile-instr-generate","llvm-profdata-19 merge",
                      "-fprofile-instr-use", "rv64_pa32_gsim_bare_diff.py",
                      "rv64_pa32_gsim_firmware_diff.sh",
                      "AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS"):
            self.assertIn(token,source)
        build=(ROOT/"tools/ci/rv64_pa32_gsim_source_patch.sh").read_text()
        self.assertIn('GSIM_SUPERNODE_MAX_SIZE=',build)
        self.assertIn('--supernode-max-size="$GSIM_SUPERNODE_MAX_SIZE"',build)

    def test_ci_uses_qualified_cache_recipe(self):
        source=(ROOT/".github/workflows/rv64-pa32-gsim-performance.yml").read_text()
        for token in ("rv64-linux-v2-", "rv64-linux-gcc-v1-",
                      "pa32-verilator-5054-bbb60a4-v1-",
                      "tests_py.test_rv64_pa32_gsim_performance_contract",
                      "rv64_pa32_gsim_performance_sweep.sh",
                      "if: always()"):
            self.assertIn(token,source)

if __name__=="__main__":
    unittest.main()
