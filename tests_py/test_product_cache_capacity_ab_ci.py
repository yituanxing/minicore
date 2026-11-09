"""Guard the bounded private Product cache A/B CI against SD1/unsafe reuse."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-cache-capacity-short-ab.yml"
RUNNER = ROOT / "tools/ci/product_cache_capacity_short_ab.sh"


class CacheCapacityAbCiContract(unittest.TestCase):
    def test_workflow_is_explicit_private_and_bounded(self):
        src = WORKFLOW.read_text()
        self.assertIn("workflow_dispatch:", src)
        self.assertIn("types: [labeled]", src)
        self.assertEqual(src.count("\n  push:\n"), 1)
        self.assertIn("branches: [work/xc7-cache-ab-isolated-20261109]", src)
        self.assertIn("paths: [.github/workflows/product-cache-capacity-short-ab.yml]", src)
        self.assertIn("github.ref == 'refs/heads/work/xc7-cache-ab-isolated-20261109'", src)
        self.assertIn("head.repo.full_name == github.repository", src)
        self.assertIn("persist-credentials: false", src)
        self.assertIn("contents: read", src)
        self.assertIn("RV64_DEBIAN_STAGE: d2", src)
        self.assertIn("AETHERCORE_CACHE_AB_CYCLES: '5000000'", src)
        self.assertIn("AETHERCORE_CACHE_AB_PROFILES: '64 256'", src)
        self.assertIn("AETHERCORE_ALLOW_PRODUCT_CACHE_AB: '1'", src)
        self.assertIn("SIM_SETTLE_MODE: reference", src)
        self.assertIn("bash tools/ensure_verilator_5_054.sh", src)
        self.assertIn("bash tools/ci/product_cache_capacity_short_ab.sh build", src)
        self.assertIn("bash tools/ci/product_cache_capacity_short_ab.sh run", src)
        self.assertIn("qualification=SHORT_PREFIX_ONLY_NO_DEBIAN_LOGIN_NO_FMAX", src)

    def test_runner_requires_exact_firmware_models_and_runtime_counters(self):
        src = RUNNER.read_text()
        self.assertIn("'64 256'|'64 256 512'", src)
        self.assertIn("AETHERCORE_ALLOW_PRODUCT_CACHE_AB", src)
        self.assertIn("git -c core.filemode=false status --porcelain --untracked-files=normal", src)
        self.assertIn("RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS", src)
        self.assertIn("model_cache verify", src)
        self.assertIn("PRODUCT_CACHE_AB_SNAPSHOT phase=timeout", src)
        self.assertIn("PRODUCT_CACHE_AB_SNAPSHOT phase=milestone", src)
        self.assertIn("SHORT_PREFIX_ONLY_NO_DEBIAN_LOGIN_NO_FMAX", src)
        self.assertIn("AETHERCORE_PRODUCT_CACHE_AB", (ROOT / "tools/ci/product_sim_cache.sh").read_text())


if __name__ == "__main__":
    unittest.main()
