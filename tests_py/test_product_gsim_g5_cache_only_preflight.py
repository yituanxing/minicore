"""Static security/identity contract for the bounded G5 cache-only probe."""

import pathlib
import unittest

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]
PROBE = ROOT / ".github/workflows/product-gsim-g5-cache-only-preflight.yml"
SOURCE = ROOT / ".github/workflows/product-gsim-g5-short-ab.yml"


def workflow(path):
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


class G5CacheOnlyPreflightContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = workflow(PROBE)
        cls.source = workflow(SOURCE)
        cls.steps = cls.probe["jobs"]["exact-cache-lookup"]["steps"]
        cls.real_steps = cls.source["jobs"]["real-product-g5-gsim-short-ab"]["steps"]

    def test_bounded_manual_or_one_label_only(self):
        self.assertEqual(self.probe["jobs"]["exact-cache-lookup"]["timeout-minutes"], "8")
        self.assertEqual(set(self.probe["on"]), {"workflow_dispatch", "pull_request"})
        self.assertEqual(self.probe["on"]["pull_request"]["types"], ["labeled"])
        self.assertIn("product-g5-cache-only-preflight", self.probe["jobs"]["exact-cache-lookup"]["if"])
        self.assertEqual(self.probe["permissions"], {"contents": "read"})

    def test_no_download_build_or_runtime_action(self):
        for step in self.steps:
            if "uses" in step:
                self.assertIn(step["uses"], {"actions/checkout@v4", "actions/cache/restore@v4"})
                if step["uses"] == "actions/cache/restore@v4":
                    self.assertEqual(step["with"]["lookup-only"], "true")
                    self.assertNotIn("restore-keys", step["with"])
            if "run" in step:
                self.assertNotIn("sudo ", step["run"])
                self.assertNotIn("make ", step["run"])
                self.assertNotIn("bash tools/", step["run"])

    def test_exact_cache_keys_and_paths_match_expensive_workflow(self):
        normal={s["with"]["key"]:s["with"]["path"] for s in self.real_steps
                if s.get("uses") in {"actions/cache/restore@v4", "actions/cache@v4"}}
        for step in self.steps:
            if step.get("uses") != "actions/cache/restore@v4":
                continue
            self.assertIn(step["with"]["key"], normal)
            self.assertEqual(step["with"]["path"], normal[step["with"]["key"]])
        self.assertEqual(len([s for s in self.steps if s.get("uses") == "actions/cache/restore@v4"]),5)

    def test_fail_closed_and_never_claims_product_pass(self):
        report=self.steps[-1]
        self.assertEqual(report["if"], "always()")
        self.assertIn("exit 3", report["run"])
        self.assertIn("G5_CACHE_ONLY_EXACT_LOOKUP_NOT_RTL_PASS", report["run"])
        self.assertNotIn("PRODUCT_GSIM_G5_AB_PASS", PROBE.read_text())


if __name__ == "__main__":
    unittest.main()
