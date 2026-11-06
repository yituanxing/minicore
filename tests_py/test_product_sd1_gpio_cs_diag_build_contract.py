"""Constrain the opt-in SD1 Linux GPIO-CS diagnostic build to an isolated source copy."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/rv64_debian_linux_build.sh"
PATCH = ROOT / "tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
PIN = "ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638"


class ProductSd1GpioCsDiagBuildContract(unittest.TestCase):
    def test_patch_pin_and_explicit_sd1_only_opt_in(self):
        s = SCRIPT.read_text()
        self.assertIn('AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_PATCH:-0', s)
        self.assertIn('[[ "${ROOTFS_STAGE}" == sd1 ]]', s)
        self.assertIn(PIN, s)
        self.assertIn("diagnostic Linux patch SHA changed without review", s)
        self.assertIn("host->status.cs_off = 1;", PATCH.read_text())
        self.assertIn("host->status.cs_off = 0;", PATCH.read_text())

    def test_pristine_source_is_verified_then_copied_before_patch(self):
        s = SCRIPT.read_text()
        verify = s.index('.aethercore-linux-source-sha256')
        clone = s.index('cp -a --reflink=auto "${SOURCE_DIR}/." "${diagnostic_source}/"')
        apply_check = s.index('git -C "${diagnostic_source}" apply --check')
        apply_now = s.index('git -C "${diagnostic_source}" apply "${diagnostic_patch}"')
        use_diag = s.index('SOURCE_DIR="${diagnostic_source}"')
        self.assertLess(verify, clone)
        self.assertLess(clone, apply_check)
        self.assertLess(apply_check, apply_now)
        self.assertLess(apply_now, use_diag)

    def test_result_is_explicitly_diagnostic_and_sha_bound(self):
        s = SCRIPT.read_text()
        self.assertIn("qualification=SD1_GPIO_CS_DIAGNOSTIC_ONLY_NOT_FROZEN_PRODUCT", s)
        self.assertIn("upstream_linux_source_sha256=", s)
        self.assertIn("kernel_patch_applied=1", s)
        self.assertIn("diagnostic_patch_sha256=", s)
        self.assertIn('linux-6.6.143-mmc-spi-gpio-cs-off.patch" >> "${EVIDENCE_DIR}/sha256.txt"', s)


if __name__ == "__main__":
    unittest.main()
