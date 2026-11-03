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

    def test_fail_closed_when_bare_diff_fails_inside_if(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            bin_dir=root/"bin"
            bin_dir.mkdir()
            fake=bin_dir/"python3"
            fake.write_text("#!/bin/sh\nexit 17\n")
            fake.chmod(0o755)
            source=(ROOT/"tools/ci/rv64_pa32_gsim_performance_sweep.sh").read_text()
            block=source.split("verify_size() {",1)[1].split("\n}\n",1)[0]
            shell=("set -euo pipefail\n"
                   f"BASE={root}\nreference={root}/reference\nFW_BIN=missing\n"
                   "verify_size() {" + block + "\n}\n"
                   "if verify_size 15; then echo FALSE_PASS; else echo BLOCKED; fi\n")
            env={**os.environ,"PATH":str(bin_dir)+":"+os.environ["PATH"]}
            result=subprocess.run(["/bin/bash","-c",shell],env=env,
                                  capture_output=True,text=True,check=True)
            self.assertIn("BLOCKED",result.stdout)
            self.assertNotIn("FALSE_PASS",result.stdout)
            self.assertNotIn("step=FIRMWARE_20M",result.stdout)

    def test_fail_closed_when_firmware_diff_fails_inside_if(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            bin_dir=root/"bin"
            bin_dir.mkdir()
            for name,exit_code in (("python3",0),("bash",23)):
                fake=bin_dir/name
                fake.write_text(f"#!/bin/sh\nexit {exit_code}\n")
                fake.chmod(0o755)
            source=(ROOT/"tools/ci/rv64_pa32_gsim_performance_sweep.sh").read_text()
            block=source.split("verify_size() {",1)[1].split("\n}\n",1)[0]
            shell=("set -euo pipefail\n"
                   f"BASE={root}\nreference={root}/reference\nFW_BIN=missing\n"
                   "verify_size() {" + block + "\n}\n"
                   "if verify_size 15; then echo FALSE_PASS; else echo BLOCKED; fi\n")
            env={**os.environ,"PATH":str(bin_dir)+":"+os.environ["PATH"]}
            result=subprocess.run(["/bin/bash","-c",shell],env=env,
                                  capture_output=True,text=True,check=True)
            self.assertIn("step=FIRMWARE_20M",result.stdout)
            self.assertIn("BLOCKED",result.stdout)
            self.assertNotIn("FALSE_PASS",result.stdout)

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
