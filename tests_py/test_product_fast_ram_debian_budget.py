import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.ci.product_fast_ram_debian_budget import BudgetError, budget, read_pinned_rootfs_result


SCRIPT = Path(__file__).resolve().parents[1] / "tools/ci/product_fast_ram_debian_budget.py"


class FastRamBudgetTest(unittest.TestCase):
    def _manifest(self, parent: Path, content: str) -> Path:
        path = parent / "result.txt"
        path.write_text(content, encoding="utf-8")
        return path

    def _valid(self, size: int, stage: str = "sd1") -> str:
        return ("RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS\n"
                f"stage={stage}\n"
                f"rootfs_bytes={size}\n"
                f"rootfs_tree_sha256={'a' * 64}\n")

    def test_full_sd1_does_not_fit_256_mib(self):
        result = budget(186038556, 256)
        self.assertEqual(result["available_rootfs_bytes"], 96 * 1024 * 1024)
        self.assertEqual(result["recommended_power_of_two_ram_mib"], 512)
        self.assertEqual(result["verdict"], "RAM_BUDGET_INSUFFICIENT_NOT_RTL_PASS")
        self.assertFalse(result["native_spi_sd1_qualified"])

    def test_exact_96_mib_boundary(self):
        self.assertEqual(budget(96 * 1024 * 1024, 256)["verdict"],
                         "RAM_BUDGET_FITS_ONLY_NOT_RTL_PASS")
        self.assertEqual(budget(96 * 1024 * 1024 + 1, 256)["verdict"],
                         "RAM_BUDGET_INSUFFICIENT_NOT_RTL_PASS")

    def test_new_512_mib_contract_is_explicit(self):
        result = budget(186038556, 512)
        self.assertTrue(result["requires_new_host_ram_and_dtb_identity"])
        self.assertEqual(result["verdict"], "RAM_BUDGET_FITS_ON_PAPER_NEW_MODEL_REQUIRED_NOT_RTL_PASS")
        self.assertFalse(result["real_product_rtl_run_verified"])

    def test_reject_unpinned_or_ambiguous_result(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            cases = [
                self._valid(1234).replace("status=PASS", "status=FAIL"),
                self._valid(1234).replace("stage=sd1", "stage=d1"),
                self._valid(1234).replace("stage=sd1", "stage=d3"),
                self._valid(1234) + "rootfs_bytes=1234\n",
                self._valid(1234).replace("rootfs_tree_sha256=" + "a" * 64, "rootfs_tree_sha256=broken"),
                self._valid(1234).replace("rootfs_bytes=1234", "rootfs_bytes=-1"),
            ]
            for content in cases:
                with self.subTest(content=content):
                    with self.assertRaises(BudgetError):
                        read_pinned_rootfs_result(self._manifest(d, content))

    def test_cli_insufficient_is_nonzero_and_not_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self._manifest(Path(directory), self._valid(186038556))
            run = subprocess.run(
                [sys.executable, str(SCRIPT), "--rootfs-result", str(path)],
                check=False, capture_output=True, text=True)
            self.assertEqual(run.returncode, 3)
            self.assertIn("RAM_BUDGET_INSUFFICIENT_NOT_RTL_PASS", run.stdout)
            self.assertNotIn("DEBIAN_LOGIN_PASS", run.stdout)


if __name__ == "__main__":
    unittest.main()
