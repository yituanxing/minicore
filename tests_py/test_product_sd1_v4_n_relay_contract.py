"""Fail-closed N relay contract, now pinned to audited M 9612M input."""

import re
import subprocess
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-n-9612-to-9712.yml"
STAGES = (9632, 9652, 9672, 9692, 9712)
SOURCE_SHA = "994434ab2172d5218916ba539854056ba27274ca"
KNOWN_M = {
    "M_RUN": "42582890916",
    "M_HEAD": "4828ee46fc0b76fa9b644e06d428957c262dab0d",
    "M_9612_ID": "13658566846",
    "M_9612_ZIP": "42db333dc3d4eae6c4597e625ee8e30623b6a8f12d1627abfc3f5b55ac91228f",
    "AETHERCORE_SD1_V4_N_9612_HOST_SHA": "5e18819d2ac0157ddcf262e615bbd57b8c6199db8bb4af570dc59107477c6247",
    "AETHERCORE_SD1_V4_N_9612_RTL_SHA": "b841a30aee012474a834383e006aa8bef23b717219b889d641e08e679132c310",
    "AETHERCORE_SD1_V4_N_9612_CARD_SHA": "560ad248059934689fd5cab1e20187faa80b87538240d6f7eb4a21b14c6c2e5b",
}
PENDING = {
    "M_RUN": "REVIEW_REQUIRED_M_RUN",
    "M_HEAD": "REVIEW_REQUIRED_M_HEAD_SHA",
    "M_9612_ID": "REVIEW_REQUIRED_M_9612_ARTIFACT_ID",
    "M_9612_ZIP": "REVIEW_REQUIRED_M_9612_ZIP_SHA",
    "AETHERCORE_SD1_V4_N_9612_HOST_SHA": "REVIEW_REQUIRED_M_9612_HOST_SHA",
    "AETHERCORE_SD1_V4_N_9612_RTL_SHA": "REVIEW_REQUIRED_M_9612_RTL_SHA",
    "AETHERCORE_SD1_V4_N_9612_CARD_SHA": "REVIEW_REQUIRED_M_9612_CARD_SHA",
}


class V4NRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_five_true_rtl_stages_n"]

    def test_exact_source_and_reviewed_event_gate(self):
        env = self.job["env"]
        self.assertEqual(env["V4_N_SOURCE_SHA"], SOURCE_SHA)
        self.assertEqual(env["AETHERCORE_SD1_V4_POSTCROSS_PROFILE"], "N")
        self.assertEqual(env["AETHERCORE_SD1_RELAY_CONFIRM"],
                         "SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B")
        for text in (
            "github.event.pull_request.number == 379",
            "github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'",
            "product-sd1-v4-reviewed-9612-to-9712-five-stages",
            '.path==".github/workflows/product-sd1-v4-m-9512-to-9612.yml"',
            '.name=="v4_postcross_five_true_rtl_stages_m"',
            "prev=9512", "for m in 9532 9552 9572 9592 9612",
            "source_cycles=9592000000", "boot_cycles=9612000000",
            ".workflow_run.id==$run", ".digest==$digest", ".expired==false",
            'sha256sum "$RUNNER_TEMP/m-9612.zip"',
            "model_sha256=$AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA",
        ):
            self.assertIn(text, self.raw)

    def test_independent_terminal_m_9612_source_is_exactly_pinned(self):
        env = self.job["env"]
        for key, expected in KNOWN_M.items():
            self.assertEqual(str(env[key]), expected, key)
        self.assertIn('product-sd1-migrated-v4-m-9612m-$M_RUN', self.raw)
        self.assertIn('source_cycles=9592000000', self.raw)
        self.assertIn('boot_cycles=9612000000', self.raw)
        self.assertIn('debian_login_claim=0', self.raw)

    def test_all_seven_unreviewed_m_pins_or_all_seven_real(self):
        env = self.job["env"]
        pending_keys = [k for k in PENDING if "REVIEW_REQUIRED" in str(env[k])]
        if pending_keys:
            self.assertEqual(set(pending_keys), set(PENDING))
            for k, v in PENDING.items():
                self.assertEqual(env[k], v)
        else:
            self.assertRegex(str(env["M_RUN"]), r"^\d{10,13}$")
            self.assertRegex(env["M_HEAD"], r"^[a-f0-9]{40}$")
            self.assertRegex(str(env["M_9612_ID"]), r"^\d{8,13}$")
            for k in ("M_9612_ZIP", "AETHERCORE_SD1_V4_N_9612_HOST_SHA",
                      "AETHERCORE_SD1_V4_N_9612_RTL_SHA", "AETHERCORE_SD1_V4_N_9612_CARD_SHA"):
                self.assertRegex(env[k], r"^[a-f0-9]{64}$")
        self.assertIn('"$M_RUN" =~ ^[0-9]{10,13}$', self.raw)
        self.assertIn('"$M_9612_ID" =~ ^[0-9]{8,13}$', self.raw)
        self.assertIn('"$sha" =~ ^[a-f0-9]{64}$', self.raw)
        names = [s["name"] for s in self.job["steps"]]
        self.assertLess(next(i for i, n in enumerate(names) if n.startswith("Fail closed")),
                        next(i for i, n in enumerate(names) if n.startswith("Advance genuine")))

    def test_five_true_rtl_stages_and_each_immediate_full_checkpoint_upload(self):
        names = [s["name"] for s in self.job["steps"]]
        advance = [s for s in names if s.startswith("Advance genuine v4 Product RTL")]
        uploads = [s for s in names if s.startswith("Upload complete v4")]
        self.assertEqual(len(advance), 5)
        self.assertEqual(len(uploads), 5)
        for m, a, u in zip(STAGES, advance, uploads):
            self.assertIn(f"{m-20}M to {m}M", a)
            self.assertIn(f"{m}M", u)
            self.assertEqual(names[names.index(a) + 1], u)
            step = self.job["steps"][names.index(a)]
            self.assertIn("--execute-reviewed-postcross-20m", step["run"])
            self.assertTrue(step["run"].endswith(f" {m}"))
            upload = self.job["steps"][names.index(u)]["with"]
            self.assertIn(f"n-{m}m-", upload["name"])
            self.assertTrue(upload["include-hidden-files"])
            self.assertEqual(upload["if-no-files-found"], "error")

    def test_no_mutation_of_original_signed_media_or_false_debian_pass(self):
        self.assertIn("product_sd1_official_seed.py verify", self.raw)
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_N_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_n_executor_static", self.raw)
        self.assertIn("'debian_login_claim=0'", self.raw)
        self.assertIn("_NOT_DEBIAN_PASS", self.raw)
        self.assertIn("product-sd1-v4-n-source/9612m", self.raw)
        self.assertIn("product-sd1-migrated-v4-10b-postcross-n", self.raw)
        self.assertLess(self.raw.index("uses: actions/checkout@v4"),
                        self.raw.index("curl -fsSL --retry 2"))
        self.assertNotIn("verilator --cc", self.raw)
        for action in self.job["steps"]:
            if "run" in action and "\n" in action["run"]:
                subprocess.run(["bash", "-n"], input=action["run"],
                               text=True, check=True)


if __name__ == "__main__":
    unittest.main()
