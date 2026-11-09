"""Reviewed H workflow is bound to G's genuine 9012M checkpoint."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-h-9012-to-9112.yml"
STAGES = (9032, 9052, 9072, 9092, 9112)
PIN = {
    "V4_H_SOURCE_SHA": "0621392b56f4b951e73d148235d833c0a23174ca",
    "G_RUN": "42567080918",
    "G_HEAD": "e3a6135875dc1f4d23f71d695ed953546b44ac4d",
    "G_9012_ID": "13651848929",
    "G_9012_ZIP": "fefea9612be5e4710b2fb7b9a5f58965789410a5afe0f4e64c4da2df701b653f",
    "AETHERCORE_SD1_V4_H_9012_HOST_SHA": "467e2f1453fed5379b3920deb599d11175a0269a71ae9f720fcc57b75d4c3778",
    "AETHERCORE_SD1_V4_H_9012_RTL_SHA": "9b3403279e461e885720debc85f59b1836eca61b729394cf529977b5454f6a44",
    "AETHERCORE_SD1_V4_H_9012_CARD_SHA": "85a8b5693f40d8e279d6aaa55fae03263ff4abfdcfb8fece8679d0ae4b76babf",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "H",
}


class V4HRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_h"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[a-f0-9]{40}", env["V4_H_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9012-to-9112-five-stages",
            '.path==".github/workflows/product-sd1-v4-g-8912-to-9012.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_g"',
            'prev=8912', 'for m in 8932 8952 8972 8992 9012',
            'source_cycles=8992000000', 'boot_cycles=9012000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/g-9012.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_reviewed_g_identity_and_fail_closed_preflight(self):
        env = self.job["env"]
        self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["G_9012_ID"]))
        for key in (
            "G_9012_ZIP",
            "AETHERCORE_SD1_V4_H_9012_HOST_SHA",
            "AETHERCORE_SD1_V4_H_9012_RTL_SHA",
            "AETHERCORE_SD1_V4_H_9012_CARD_SHA",
        ):
            self.assertTrue(re.fullmatch(r"[a-f0-9]{64}", env[key]))
        self.assertIn('"$G_9012_ID" =~ ^[0-9]{8,13}$', self.raw)
        self.assertIn('"$sha" =~ ^[a-f0-9]{64}$', self.raw)
        names = [s["name"] for s in self.job["steps"]]
        preflight = next(i for i, name in enumerate(names) if name.startswith("Fail closed"))
        first_guest_step = next(i for i, name in enumerate(names) if name.startswith("Advance genuine"))
        self.assertLess(preflight, first_guest_step)

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
            self.assertIn(f"h-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_H_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_h_executor_static", self.raw)
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
