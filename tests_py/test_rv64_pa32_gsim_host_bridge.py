"""Pin GSIM source-transform behavior and immutable host-RAM boundary."""
import importlib.util
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCH = ROOT / "tools/ci/rv64_pa32_gsim_host_bridge_patch.py"
spec = importlib.util.spec_from_file_location("gsim_host_bridge", PATCH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class HostBridgeContract(unittest.TestCase):
    def test_exact_supernode_callback_and_drift_guard(self):
        fixture = module.HEADER + "\n" + module.GENERATE + "\n" + module.EVAL
        patched = module.patch_source(fixture)
        for token in ("pa32HostBridgeRequestSuper(super)",
                      "pa32HostBridge(pa32HostContext)",
                      'name == "io$$imemAddr"',
                      'name == "io$$ptwAddr"'):
            self.assertIn(token, patched)
        with self.assertRaises(ValueError):
            module.patch_source(patched)
        with self.assertRaises(ValueError):
            module.patch_source(module.GENERATE + module.EVAL)

    def test_one_hardware_tick_and_shared_host_memory(self):
        runner = (ROOT / "sim/pa32_gsim_prefix_main.cpp").read_text()
        build = (ROOT / "tools/ci/rv64_pa32_gsim_source_patch.sh").read_text()
        for item in ("top->pa32HostBridge = &hostBridge",
                     "ctx.ports->respond(resp)",
                     "readResponses(req, *ctx.memory)",
                     "AETHERCORE_PA32_GSIM_BRIDGE_METRICS",
                     "matchingBareInstructions != 3"):
            self.assertIn(item, runner)
        self.assertIn("top->step();", runner)
        self.assertIn("GSIM_HOST_BRIDGE_EXPERIMENT", build)
        self.assertIn('--supernode-max-size="$GSIM_SUPERNODE_MAX_SIZE"', build)
        self.assertIn("gsim-configuration.txt", build)
        self.assertIn("rv64_pa32_gsim_host_bridge_patch.py", build)
        subprocess.run(["bash","-n",str(ROOT / "tools/ci/rv64_pa32_gsim_source_patch.sh")], check=True)

if __name__ == "__main__":
    unittest.main()
