"""PREPARED-ONLY L workflow contract; no K9412 hashes may be guessed.

The K job must complete and its 9412M artifact must be independently audited
before a reviewer replaces placeholders or applies the L trigger label.
"""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-l-9412-to-9512.yml"
STAGES = (9432, 9452, 9472, 9492, 9512)
KNOWN_PINS = {
    "V4_L_SOURCE_SHA": "39b4046d38f88a2411fdcfdfab3304110991d42f",
    "K_RUN": "42575734285",
    "K_HEAD": "4dd150ff99d23cf03756db5794d822026bd683f9",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "L",
}
MISSING_PINS = (
    "K_9412_ID", "K_9412_ZIP", "AETHERCORE_SD1_V4_L_9412_HOST_SHA",
    "AETHERCORE_SD1_V4_L_9412_RTL_SHA", "AETHERCORE_SD1_V4_L_9412_CARD_SHA",
)
REVIEWED_K_9412 = {
    "K_9412_ID": "13655481465",
    "K_9412_ZIP": "c16bf62041b0896a3c1ce7525529b9f1d4856f64c72ee561831a6b39b8e2c92f",
    "AETHERCORE_SD1_V4_L_9412_HOST_SHA": "6b76b415e924063489108b987016f0f7b42ff23860d6433e1d640286257b985e",
    "AETHERCORE_SD1_V4_L_9412_RTL_SHA": "2f4438bc387ee4a0b6c03257bbe7fb44ad8d7479f2fb49b72ecaff60514bb6ea",
    "AETHERCORE_SD1_V4_L_9412_CARD_SHA": "50ee06ecb314377c16222136e2d10a845bebcfa607543d120705879840956440",
}


class V4LRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_l"]

    def test_exact_known_source_and_prior_run(self):
        env = self.job["env"]
        for key, value in KNOWN_PINS.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", env["V4_L_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9412-to-9512-five-stages",
            '.path==".github/workflows/product-sd1-v4-k-9312-to-9412.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_k"',
            'prev=9312', 'for m in 9332 9352 9372 9392 9412',
            'source_cycles=9392000000', 'boot_cycles=9412000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest', '.expired==false',
            'sha256sum "$RUNNER_TEMP/k-9412.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_k_9412_identity_is_either_fully_unreviewed_or_fully_pinned(self):
        env = self.job["env"]
        pending = [key for key in MISSING_PINS if "REVIEW_REQUIRED" in env[key]]
        if pending:
            self.assertEqual(tuple(pending), MISSING_PINS)
            self.assertFalse(re.fullmatch(r"[0-9]{8,13}", env["K_9412_ID"]))
        else:
            self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["K_9412_ID"]))
            for key in MISSING_PINS[1:]:
                self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", env[key]))
        self.assertIn('"$K_9412_ID" =~ ^[0-9]{8,13}$', self.raw)
        self.assertIn('"$sha" =~ ^[a-f0-9]{64}$', self.raw)
        names = [s["name"] for s in self.job["steps"]]
        preflight = next(i for i, name in enumerate(names) if name.startswith("Fail closed"))
        first_guest_step = next(i for i, name in enumerate(names) if name.startswith("Advance genuine"))
        self.assertLess(preflight, first_guest_step)

    def test_exact_independently_audited_k_9412_source_artifact(self):
        env = self.job["env"]
        for key, expected in REVIEWED_K_9412.items():
            self.assertEqual(env[key], expected, key)
        self.assertIn('.name=="v4_postcross_five_true_rtl_stages_k"', self.raw)
        self.assertIn("'source_cycles=9392000000'", self.raw)
        self.assertIn(".digest==$digest", self.raw)
        self.assertIn("debian_login_claim=0", self.raw)

    def test_five_sequential_rtl_stages_each_uploaded(self):
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
            self.assertIn(f"l-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_L_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_k_executor_static", self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_l_executor_static", self.raw)
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
