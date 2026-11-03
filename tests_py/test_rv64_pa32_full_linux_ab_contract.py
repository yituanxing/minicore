import pathlib, subprocess, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class ExactFullLinuxAB(unittest.TestCase):
    def test_fail_closed_signoff(self):
        script=ROOT/"tools/ci/rv64_pa32_verilator_full_linux_ab.sh"
        subprocess.run(["bash","-n",str(script)],check=True)
        text=script.read_text()
        for key in ("OpenSBI v1.6","RV64 USER UART IRQ OK","L32_RUNTIME_MILESTONE_PASS",
                    "L32_FIRST_SUPERVISOR_TIMER_INTERRUPT","L32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT",
                    "AETHERCORE_ISA_COVERAGE=1","AETHERCORE_PA32_FULL_LINUX_STATE_MATCH",
                    "AETHERCORE_PA32_FULL_LINUX_AB_RESULT","950000000","sha256sum"):
            self.assertIn(key,text)
        self.assertIn("5.024 5.054",text)
    def test_same_rtl_and_firmware(self):
        workflow=(ROOT/".github/workflows/rv64-pa32-verilator-full-linux-ab.yml").read_text()
        for key in ("RV64_INIT_PROFILE=isa-fast","rv64_minimal_initramfs_build.sh",
                    "rv64_minimal_init_payload_build.sh","rv64_pa32_verilator_version_ab.sh",
                    "rv64_pa32_verilator_full_linux_ab.sh","cancel-in-progress: false"):
            self.assertIn(key,workflow)
        version=(ROOT/"tools/ci/rv64_pa32_verilator_version_ab.sh").read_text()
        self.assertIn("ElaborateV2VirtualFpgaBoardPA32RV64",version)
        self.assertIn("AETHERCORE_SIM_ADAPTIVE_SETTLE",version)
if __name__=="__main__":
    unittest.main()
