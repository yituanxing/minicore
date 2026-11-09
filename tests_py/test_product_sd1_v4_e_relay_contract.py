"""Reviewed E workflow is bound to D's exact genuine Product RTL 8712M state."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-e-8712-to-8812.yml"
STAGES = (8732, 8752, 8772, 8792, 8812)
PIN = {
    "D_RUN": "42553860635",
    "D_HEAD": "d81358030732c51b549e744f6b1e8c02902354d4",
    "D_8712_ID": "13646962321",
    "D_8712_ZIP": "f58c2cb7ad76e253a1695a54a700728210259082788549e0a232e070e8697eaf",
    "AETHERCORE_SD1_V4_E_8712_HOST_SHA": "22048174ece94ac2231c44dc5841f687b70f55bd052426284fa81d7b257874f3",
    "AETHERCORE_SD1_V4_E_8712_RTL_SHA": "a6fc3ead29f70fa9b634aae46c4bd4dbae589e48717900bf289f0e78fcff971f",
    "AETHERCORE_SD1_V4_E_8712_CARD_SHA": "cd6799343d1a041440eea388127c08c07f32dfa8150e4e1e5c5425a11b976b6f",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "E",
}


class V4ERelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_e"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[a-f0-9]{40}|UNREVIEWED_FAIL_CLOSED", env["V4_E_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-8712-to-8812-five-stages",
            '.path==".github/workflows/product-sd1-v4-d-8612-to-8712.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_d"',
            'prev=8612', 'for m in 8632 8652 8672 8692 8712',
            'source_cycles=8692000000', 'boot_cycles=8712000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/d-8712.zip"',
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
            self.assertIn(f"e-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_E_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_e_executor_static", self.raw)
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
