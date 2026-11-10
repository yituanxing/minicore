"""Reviewed M workflow contract bound to verified L9512 real RTL hashes.

The L job must complete and its 9512M artifact must be independently audited
before a reviewer may apply the M trigger label.
"""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-m-9512-to-9612.yml"
STAGES = (9532, 9552, 9572, 9592, 9612)
KNOWN_PINS = {
    "V4_M_SOURCE_SHA": "c64db688ded56c2fb8c48c69d962a557b818644b",
    "L_RUN": "42580711207",
    "L_HEAD": "f93304f12f451b678235a9606b542e035b152669",
    "AETHERCORE_SD1_V4_POSTCROSS_PROFILE": "M",
}
MISSING_PINS = (
    "L_9512_ID", "L_9512_ZIP", "AETHERCORE_SD1_V4_M_9512_HOST_SHA",
    "AETHERCORE_SD1_V4_M_9512_RTL_SHA", "AETHERCORE_SD1_V4_M_9512_CARD_SHA",
)
EXPECTED_L_9512 = {
    "L_9512_ID": "13656509323",
    "L_9512_ZIP": "0703ef1e94d0eac6b2d7bacbd76f206428ffc74f42823f7eaa8ba5c2ab09687d",
    "AETHERCORE_SD1_V4_M_9512_HOST_SHA": "1f8a9f5e5ea0fd2db70b2168d588cf25b2cf97d63741aff8e44498f2ebdc40c6",
    "AETHERCORE_SD1_V4_M_9512_RTL_SHA": "16da252d8864590cf9d892487976e3067c5130c63350631e2fa62b3b9f5cf453",
    "AETHERCORE_SD1_V4_M_9512_CARD_SHA": "98c4f66ed5510a1f03daf024d4aa119a3db93b8b8989f369a428f276b2af0c39",
}


class V4MRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_m"]

    def test_exact_known_source_and_prior_run(self):
        env = self.job["env"]
        for key, value in KNOWN_PINS.items():
            self.assertEqual(env[key], value)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", env["V4_M_SOURCE_SHA"]))
        for mandatory in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9512-to-9612-five-stages",
            '.path==".github/workflows/product-sd1-v4-l-9412-to-9512.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_l"',
            'prev=9412', 'for m in 9432 9452 9472 9492 9512',
            'source_cycles=9492000000', 'boot_cycles=9512000000',
            'model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA',
            '.workflow_run.id==$run', '.digest==$digest', '.expired==false',
            'sha256sum "$RUNNER_TEMP/l-9512.zip"',
        ):
            self.assertIn(mandatory, self.raw)

    def test_l_9512_identity_is_either_fully_unreviewed_or_fully_pinned(self):
        env = self.job["env"]
        pending = [key for key in MISSING_PINS if "REVIEW_REQUIRED" in env[key]]
        if pending:
            self.assertEqual(tuple(pending), MISSING_PINS)
            self.assertFalse(re.fullmatch(r"[0-9]{8,13}", env["L_9512_ID"]))
        else:
            self.assertTrue(re.fullmatch(r"[0-9]{8,13}", env["L_9512_ID"]))
            for key in MISSING_PINS[1:]:
                self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", env[key]))
        self.assertIn('"$L_9512_ID" =~ ^[0-9]{8,13}$', self.raw)
        self.assertIn('"$sha" =~ ^[a-f0-9]{64}$', self.raw)
        names = [s["name"] for s in self.job["steps"]]
        preflight = next(i for i, name in enumerate(names) if name.startswith("Fail closed"))
        first_guest_step = next(i for i, name in enumerate(names) if name.startswith("Advance genuine"))
        self.assertLess(preflight, first_guest_step)

    def test_exact_reviewed_or_unreviewed_l_9512_source_artifact(self):
        env = self.job["env"]
        for key, expected in EXPECTED_L_9512.items():
            self.assertEqual(env[key], expected, key)
        self.assertIn('.name=="v4_postcross_five_true_rtl_stages_l"', self.raw)
        self.assertIn("'source_cycles=9492000000'", self.raw)
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
            self.assertIn(f"m-{m}m-", item["name"])
            self.assertTrue(item["include-hidden-files"])
            self.assertEqual(item["if-no-files-found"], "error")

    def test_preflight_and_no_false_login_claim(self):
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_M_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_l_executor_static", self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_m_executor_static", self.raw)
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
