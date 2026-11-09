"""Reviewed F workflow is bound to E's exact genuine Product RTL 8812M state."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-f-8812-to-8912.yml"
STAGES = (8832, 8852, 8872, 8892, 8912)
PIN = {
    "E_RUN": "42563504452",
    "E_HEAD": "5166bfcc144d7419e1c4eeb354237a457b13680c",
    "E_8812_ID": "13650597310",
    "E_8812_ZIP": "502d11ac422cb61961c0b5ba31d44a4c8b086a93d17ab06d33aa6d763be33d22",
    "AETHERCORE_SD1_V4_F_8812_HOST_SHA": "2c608a656322378f6f83c9e27aeef6cdbac75ab4ecec545aaecffb4d1ff31454",
    "AETHERCORE_SD1_V4_F_8812_RTL_SHA": "ecbbf4fde9a06eb93e73868afcad4fcb2c0b6434c2a02cfe03eab6bdf7becb2b",
    "AETHERCORE_SD1_V4_F_8812_CARD_SHA": "b68ed61cb28dea4cb8655fddd354559ef69b21d3dae3bbf6bdc28fa65b0094cd",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "F",
}


class V4FRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_f"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[a-f0-9]{40}|UNREVIEWED_FAIL_CLOSED", env["V4_F_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-8812-to-8912-five-stages",
            '.path==".github/workflows/product-sd1-v4-e-8712-to-8812.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_e"',
            'prev=8712', 'for m in 8732 8752 8772 8792 8812',
            'source_cycles=8792000000', 'boot_cycles=8812000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/e-8812.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_five_sequential_real_rtl_stages_each_uploaded(self):
        names = [s["name"] for s in self.job["steps"]]
        advance = [s for s in names if s.startswith("Advance genuine v4 Product RTL")]
        uploads = [s for s in names if s.startswith("Upload complete v4")]
        self.assertEqual(len(advance), 5)
        self.assertEqual(len(uploads), 5)
        for m, a, u in zip(STAGES, advance, uploads):
            self.assertIn(f"{m-20}M to {m}M", a)
            self.assertIn(f"{m}M", u)
            self.assertEqual(names[names.index(a) + 1], u)
            run = self.job["steps"][names.index(a)]["run"]
            self.assertIn("--execute-reviewed-postcross-20m", run)
            self.assertTrue(run.endswith(f" {m}"))
            item = self.job["steps"][names.index(u)]["with"]
            self.assertIn(f"f-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_F_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_f_executor_static", self.raw)
        self.assertIn("debian_login_claim=0", self.raw)
        self.assertIn("_NOT_DEBIAN_PASS", self.raw)
        self.assertIn("product_sd1_official_seed.py verify", self.raw)
        self.assertLess(self.raw.index("uses: actions/checkout@v4"),
                        self.raw.index("curl -fsSL --retry 2"))
        self.assertNotIn("verilator --cc", self.raw)
        for action in self.job["steps"]:
            if "run" in action and "\n" in action["run"]:
                subprocess.run(["bash", "-n"], input=action["run"],
                               text=True, check=True)


if __name__ == "__main__":
    unittest.main()

