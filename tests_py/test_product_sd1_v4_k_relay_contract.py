"""PREPARED-ONLY K workflow contract; no J9312 hashes may be guessed.

The J job must complete and its 9312M artifact must be independently audited
before a reviewer replaces placeholders or applies the K trigger label.
"""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-k-9312-to-9412.yml"
STAGES = (9332, 9352, 9372, 9392, 9412)
KNOWN_PINS = {
    "V4_K_SOURCE_SHA": "62c12230d09d69d99f6a57a75326c37b931941ee",
    "J_RUN": "42571103240",
    "J_HEAD": "b95bc0a514fcaba79baa64e35d59e88e7b877455",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "K",
}
MISSING_PINS = (
    "J_9312_ID", "J_9312_ZIP", "AETHERCORE_SD1_V4_K_9312_HOST_SHA",
    "AETHERCORE_SD1_V4_K_9312_RTL_SHA", "AETHERCORE_SD1_V4_K_9312_CARD_SHA",
)


class V4KRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_k"]

    def test_exact_known_source_and_prior_run(self):
        env = self.job["env"]
        for key, value in KNOWN_PINS.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", env["V4_K_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9312-to-9412-five-stages",
            '.path==".github/workflows/product-sd1-v4-j-9212-to-9312.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_j"',
            'prev=9212', 'for m in 9232 9252 9272 9292 9312',
            'source_cycles=9292000000', 'boot_cycles=9312000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest', '.expired==false',
            'sha256sum "$RUNNER_TEMP/j-9312.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_j_9312_identity_is_either_fully_unreviewed_or_fully_pinned(self):
        env = self.job["env"]
        pending = [key for key in MISSING_PINS if "REVIEW_REQUIRED" in env[key]]
        if pending:
            self.assertEqual(tuple(pending), MISSING_PINS)
            self.assertFalse(re.fullmatch(r"[0-9]{8,13}", env["J_9312_ID"]))
        else:
            self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["J_9312_ID"]))
            for key in MISSING_PINS[1:]:
                self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", env[key]))
        self.assertIn('"$J_9312_ID" =~ ^[0-9]{8,13}$', self.raw)
        self.assertIn('"$sha" =~ ^[a-f0-9]{64}$', self.raw)
        names = [s["name"] for s in self.job["steps"]]
        preflight = next(i for i, name in enumerate(names) if name.startswith("Fail closed"))
        first_guest_step = next(i for i, name in enumerate(names) if name.startswith("Advance genuine"))
        self.assertLess(preflight, first_guest_step)

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
            self.assertIn(f"k-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_K_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_k_executor_static", self.raw)
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
