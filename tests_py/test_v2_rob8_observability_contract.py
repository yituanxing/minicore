import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2Rob8HostPerformance.scala"
ELABORATE = ROOT / "src/main/scala/aethercore/ElaborateV2OpenSbiRV64.scala"
SHIM = ROOT / "sim/v2_rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h"
HOOK = ROOT / "sim/v2_rv64_opensbi_shim/v2_rob8_perf_host_hook.h"
AB = ROOT / "tools/ci/v2_p8_arch_ab.sh"


class V2Rob8ObservabilityContractTest(unittest.TestCase):
    def test_host_visible_wrapper_exports_every_added_bucket(self) -> None:
        text = WRAPPER.read_text(encoding="utf-8")
        for occupancy in range(5, 9):
            self.assertIn(f"core.io.occupancy === {occupancy}.U", text)
            self.assertIn(f"val ioPerfRob{occupancy}", text)
            self.assertIn(f"ioPerfRob{occupancy} := rob{occupancy}", text)

    def test_measured_elaboration_uses_rob8_wrapper(self) -> None:
        text = ELABORATE.read_text(encoding="utf-8")
        self.assertIn("AetherCoreV2Rob8MeasuredOpenSbiRV64SimTopHostVisible", text)

    def test_host_hook_appends_every_added_bucket(self) -> None:
        shim = SHIM.read_text(encoding="utf-8")
        hook = HOOK.read_text(encoding="utf-8")
        self.assertIn('#include "v2_rob8_perf_host_hook.h"', shim)
        self.assertIn('#include "v2_perf_host_hook.h"', hook)
        for occupancy in range(5, 9):
            self.assertIn(f'rob{occupancy}=', hook)
            self.assertIn(f'top.ioPerfRob{occupancy}', hook)

    def test_architecture_ab_fails_closed_on_histogram_gaps(self) -> None:
        text = AB.read_text(encoding="utf-8")
        self.assertIn("baseline_rob_sum = sum(b[f'rob{i}'] for i in range(5))", text)
        self.assertIn("target_rob_sum = sum(t[f'rob{i}'] for i in range(9))", text)
        self.assertIn("baseline ROB histogram incomplete", text)
        self.assertIn("target ROB histogram incomplete", text)
        self.assertIn("AETHERCORE_ARCH_AB_HISTOGRAM_PASS", text)


if __name__ == "__main__":
    unittest.main()
