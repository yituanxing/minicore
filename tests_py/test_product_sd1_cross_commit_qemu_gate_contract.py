"""Source-only fail-closed tests for reusing a Debian QEMU oracle after a PR SHA change."""

from pathlib import Path
import json
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
SD1 = ROOT / ".github/workflows/product-sd1-official-artifact-relay.yml"
C1 = ROOT / ".github/workflows/product-sd1-c1-checkpoint-relay.yml"


def comparator(source: str) -> str:
    matches = re.findall(r"jq -e --arg old \"\$[^\"]+\" '([^']+)'", source, re.S)
    if not matches:
        raise AssertionError("missing fail-closed GitHub ancestor comparator")
    return matches[0]


@unittest.skipUnless(shutil.which("jq"), "requires jq, which CI Ubuntu runners provide")
class ProductSd1QemuCrossCommitContract(unittest.TestCase):
    def check(self, expression: str, files: list[str], success: bool,
              *, status: str = "ahead", renamed: bool = False) -> None:
        prior = "b" * 40
        payload = {
            "merge_base_commit": {"sha": prior},
            "behind_by": 0,
            "status": status,
            "total_commits": 1,
            "files": [
                {"status": "renamed" if renamed else "modified", "filename": path}
                for path in files
            ],
        }
        run = subprocess.run(
            ["jq", "-e", "--arg", "old", prior, expression],
            input=json.dumps(payload), text=True, capture_output=True, check=False,
        )
        self.assertEqual(run.returncode == 0, success, run.stderr or run.stdout)

    def test_exact_workflow_lookup_and_only_proven_success(self):
        for workflow in (SD1, C1):
            text = workflow.read_text()
            if workflow == SD1:
                self.assertIn("actions/workflows/product-sd1-native-qemu-oracle.yml/runs", text)
            else:
                self.assertIn("actions/workflows/$required/runs", text)
                self.assertIn("product-sd1-native-qemu-oracle.yml product-sd1-c1-qemu-oracle.yml", text)
            self.assertIn('select(.conclusion == "success"', text)
            self.assertIn("compare/$", text)
            self.assertIn(".merge_base_commit.sha == $old", text)
            self.assertIn(".behind_by == 0", text)
            self.assertIn("length < 300", text)
            self.assertIn('.status != "renamed"', text)
        self.assertIn("actions/workflows/product-sd1-official-artifact-relay.yml/runs", SD1.read_text())
        self.assertIn("actions/workflows/product-sd1-c1-checkpoint-relay.yml/runs", C1.read_text())

    def test_unrelated_fpga_commit_reuses_qemu_but_sensitive_source_never_does(self):
        for workflow in (SD1, C1):
            expression = comparator(workflow.read_text())
            self.check(expression, [
                "docs/architecture/PRODUCT_FPGA_XC7_FIRST_MAPPED_RESULTS.md",
                "tests_py/test_product_fpga_xc7_unplaced.py",
                "tools/ci/product_fpga_xc7_unplaced.sh",
                "tools/ci/product_fpga_xc7_hierarchy.py",
            ], True)
            for sensitive in (
                "src/main/scala/AetherCoreProduct.scala",
                "sim/product_rv64_checkpoint_sd1_main.cpp",
                "tools/ci/product_sd1_qemu_watch.py",
                "tools/ci/product_sd1_qemu_init_oracle.sh",
                "tools/ci/rv64_debian_sd1_sd_image_build.sh",
                "tools/ci/product_sd1_c1_kernel_build.sh",
                "software/rv64/product_sd1_official_seed.lock",
            ):
                self.check(expression, [sensitive], False)
            self.check(expression, ["docs/architecture/renamed.md"], False, renamed=True)
            self.check(expression, ["tools/ci/product_fpga_xc7_unplaced.sh"], False, status="behind")

    def test_c1_only_uart_oracle_fix_preserves_independent_native_qemu_proof(self):
        # A C1-only QEMU host UART watcher does not alter native Debian QEMU
        # kernel/media inputs. C1's own READY->GO oracle must be re-run.
        files = [
            "tools/ci/product_sd1_c1_qemu_watch.py",
            "tests_py/test_product_sd1_c1_qemu_oracle_contract.py",
        ]
        self.check(comparator(SD1.read_text()), files, True)
        self.check(comparator(C1.read_text()), files, False)


if __name__ == "__main__":
    unittest.main()
