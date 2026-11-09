"""Reviewed J workflow authenticates I's complete genuine RTL 9212M checkpoint."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-j-9212-to-9312.yml"
STAGES = (9232, 9252, 9272, 9292, 9312)
PIN = {
    "V4_J_SOURCE_SHA": "4542a019add9aaf7d642e2d7cf38cd5c3b3db8f9",
    "I_RUN": "42569616651",
    "I_HEAD": "030b57c8fddad2a94d0a42dc40c80147a1c1e371",
    "I_9212_ID": "13652054610",
    "I_9212_ZIP": "527d7f676ece2d866202c5f8354d313ca5f808d01213348f4db10dbc9538ed95",
    "AETHERCORE_SD1_V4_J_9212_HOST_SHA": "737fbf9e4279d25589b9a4903ab62ccaa591bf4222e62d157e0149af9fe5e796",
    "AETHERCORE_SD1_V4_J_9212_RTL_SHA": "d4247254d6d2ec7f95e3cd691d709cb4de75d344ebfb438ed8fc1c986e1acc84",
    "AETHERCORE_SD1_V4_J_9212_CARD_SHA": "55bbb21ec9a80de5c86210d652ecd65cc02c92c922aefc29fed2e5bb6f6c22cd",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "J",
}


class V4JRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_j"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", env["V4_J_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9212-to-9312-five-stages",
            '.path==".github/workflows/product-sd1-v4-i-9112-to-9212.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_i"',
            'prev=9112', 'for m in 9132 9152 9172 9192 9212',
            'source_cycles=9192000000', 'boot_cycles=9212000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/i-9212.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_reviewed_i_identity_and_fail_closed_preflight(self):
        env = self.job["env"]
        self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["I_9212_ID"]))
        for key in (
            "I_9212_ZIP",
            "AETHERCORE_SD1_V4_J_9212_HOST_SHA",
            "AETHERCORE_SD1_V4_J_9212_RTL_SHA",
            "AETHERCORE_SD1_V4_J_9212_CARD_SHA",
        ):
            self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", env[key]))
        self.assertIn('"$I_9212_ID" =~ ^[0-9]{8,13}$', self.raw)
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
            self.assertIn(f"j-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_J_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_j_executor_static", self.raw)
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
