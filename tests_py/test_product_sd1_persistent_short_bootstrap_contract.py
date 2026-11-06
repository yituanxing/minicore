"""Source-only contract: short Product SD1 CI must reuse runner tools."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-persistent-short.yml"
BOOTSTRAP_NAME = (
    "Reuse Java 17 and install only missing RTL host dependencies after trusted seed gate"
)


class ProductSd1PersistentShortBootstrapContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.bootstrap = cls.workflow.split(f"- name: {BOOTSTRAP_NAME}", 1)[1].split(
            "      - name: Validate official full Debian SD media firmware Linux and DTB hashes", 1
        )[0]

    def test_exact_official_media_cache_fails_closed_before_host_packages(self):
        self.assertLess(
            self.workflow.index("steps.seed-cache.outputs.cache-hit"),
            self.workflow.index(f"- name: {BOOTSTRAP_NAME}"),
        )
        self.assertIn("python3 tools/ci/product_sd1_official_seed.py verify", self.workflow)
        self.assertIn("product-sd1-official-seed-v1", self.workflow)

    def test_preinstalled_java17_checked_without_jdk_redownload(self):
        for marker in (
            "JAVA_HOME_17_X64", "command -v java", "command -v javac",
            "java -version 2>&1 | grep -Eq", "javac -version 2>&1 | grep -Eq",
            'echo "JAVA_HOME=$JAVA_HOME" >> "$GITHUB_ENV"',
        ):
            self.assertIn(marker, self.bootstrap)
        self.assertNotIn("openjdk-17-jdk-headless", self.bootstrap)

    def test_only_missing_native_verilator_build_dependencies_are_installed(self):
        for marker in (
            "missing_packages=()", "need_command cmake cmake",
            "need_command ninja ninja-build", "need_command autoconf autoconf",
            "need_command dtc device-tree-compiler", "need_package libfl-dev",
            "need_package libgmp-dev", "need_package zlib1g-dev",
            "need_package libelf-dev", "need_package libssl-dev",
            "if (( ${#missing_packages[@]} )); then",
            'sudo apt-get install -y --no-install-recommends "${missing_packages[@]}"',
            "SD1_SHORT_RUNNER_TOOLS_ALREADY_PRESENT",
        ):
            self.assertIn(marker, self.bootstrap)
        self.assertNotIn("sudo apt-get install -y openjdk", self.bootstrap)

    def test_runner_packages_are_bounded_and_verilator_is_still_pinned(self):
        self.assertIn("timeout --signal=TERM --kill-after=30s 10m sudo apt-get update", self.bootstrap)
        self.assertIn("timeout --signal=TERM --kill-after=30s 15m", self.bootstrap)
        self.assertIn("AETHERCORE_VERILATOR_VERSION=5.054 bash tools/ensure_verilator_5_024.sh", self.bootstrap)
        self.assertIn("timeout-minutes: 35", self.workflow)
        self.assertNotIn("product_sd1_background_runner.sh", self.workflow)


if __name__ == "__main__":
    unittest.main()
