"""Reviewed G workflow is bound to F's exact genuine Product RTL 8912M state."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-g-8912-to-9012.yml"
STAGES = (8932, 8952, 8972, 8992, 9012)
PIN = {
    "F_RUN": "42565303146",
    "F_HEAD": "569272059efad2140be782684608ece9a41fae80",
    "F_8912_ID": "13650788623",
    "F_8912_ZIP": "2fc90dee6eb2b4d2f758410ab9be6733018eb7dbb272caa9d17b2b376ef59dfd",
    "AETHERCORE_SD1_V4_G_8912_HOST_SHA": "12896c78b6214e3441a356f6b312dbdfbf4f2705d53972369ea936909a6e2193",
    "AETHERCORE_SD1_V4_G_8912_RTL_SHA": "1e33632affaf49d2e67618710a9c8462d5ddbd99f5ba2b7cac049c5e667c03fc",
    "AETHERCORE_SD1_V4_G_8912_CARD_SHA": "0aec18e65f59d185e979b3157124d8568ee57f6ed980ce922fd826c206f94d8e",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "G",
}


class V4GRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_g"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[a-f0-9]{40}|UNREVIEWED_FAIL_CLOSED", env["V4_G_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-8912-to-9012-five-stages",
            '.path==".github/workflows/product-sd1-v4-f-8812-to-8912.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_f"',
            'prev=8812', 'for m in 8832 8852 8872 8892 8912',
            'source_cycles=8892000000', 'boot_cycles=8912000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/f-8912.zip"',
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
            self.assertIn(f"g-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_G_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_g_executor_static", self.raw)
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


