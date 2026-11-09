"""I workflow authenticates H's completed genuine RTL 9112M checkpoint."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-i-9112-to-9212.yml"
STAGES = (9132, 9152, 9172, 9192, 9212)
PIN = {
    "V4_I_SOURCE_SHA": "7e0381df24049f8ecbb04be6099e195a45bc4675",
    "H_RUN": "42568596974",
    "H_HEAD": "ea93ac852e67d8423b0682b44bc7d3d29e8106ab",
    "H_9112_ID": "13651749340",
    "H_9112_ZIP": "d3080224089fc46b5ecbdb16209ac0128a29c8071b342aba58aff9888e0b86d4",
    "AETHERCORE_SD1_V4_I_9112_HOST_SHA": "fd088f26f3ff7f8ff68d6efc34b9abaa3c8e43a6221de3d3864ab7b78491dd7e",
    "AETHERCORE_SD1_V4_I_9112_RTL_SHA": "fa8c2adae010d1329b729101fdd27e9ef4bad44df166289c4c8a8cdcb000adc3",
    "AETHERCORE_SD1_V4_I_9112_CARD_SHA": "7b6db6b326957f26a6f68943769d6095a8d895d2948d9ee339ab7443ca2de2cd",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "I",
}


class V4IRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_i"]

    def test_exact_authenticated_source_and_unique_trigger(self):
        env = self.job["env"]
        for key, value in PIN.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", env["V4_I_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9112-to-9212-five-stages",
            '.path==".github/workflows/product-sd1-v4-h-9012-to-9112.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_h"',
            'prev=9012', 'for m in 9032 9052 9072 9092 9112',
            'source_cycles=9092000000', 'boot_cycles=9112000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest',
            '.expired==false',
            'sha256sum "$RUNNER_TEMP/h-9112.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_reviewed_h_identity_and_fail_closed_preflight(self):
        env = self.job["env"]
        self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["H_9112_ID"]))
        for key in (
            "H_9112_ZIP",
            "AETHERCORE_SD1_V4_I_9112_HOST_SHA",
            "AETHERCORE_SD1_V4_I_9112_RTL_SHA",
            "AETHERCORE_SD1_V4_I_9112_CARD_SHA",
        ):
            self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", env[key]))
        self.assertIn('"$H_9112_ID" =~ ^[0-9]{8,13}$', self.raw)
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
            self.assertIn(f"i-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_static_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_I_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_i_executor_static", self.raw)
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
