import pathlib,subprocess,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class EngineABContracts(unittest.TestCase):
    def test_source_and_gate(self):
        for f in ("tools/ci/rv64_pa32_gsim_engine_ab.sh",
                  "tools/ci/rv64_pa32_gsim_source_patch.sh"):
            subprocess.run(["bash","-n",str(ROOT/f)],check=True)
        driver=(ROOT/"sim/pa32_sim_engine_ab_main.cpp").read_text()
        shell=(ROOT/"tools/ci/rv64_pa32_gsim_engine_ab.sh").read_text()
        for t in ("AETHERCORE_PA32_ENGINE_RESULT","AETHERCORE_USE_GSIM",
                  "state.mtime","state.commits","state.exceptions","io_imemInst"):
            self.assertIn(t,driver)
        for t in ("ElaborateV2VirtualFpgaBoardPA32RV64",
                  "AETHERCORE_PA32_ENGINE_AB_STATE_MATCH",
                  "AETHERCORE_PA32_ENGINE_AB_STATE_MISMATCH",
                  "AETHERCORE_PA32_ENGINE_AB_RESULT",
                  "zero-response-not-linux","--compiler clang"):
            self.assertIn(t,shell)
if __name__=="__main__":
    unittest.main()
