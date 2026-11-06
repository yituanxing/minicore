"""Cheap structural guards for the manually gated SD1 kernel-only experiment."""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools/ci/product_sd1_gpio_cs_diag_build.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-gpio-cs-diag-kernel.yml"


class DiagnosticSD1BuildIsolation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = BUILD.read_text()
        cls.workflow = WORKFLOW.read_text()

    def test_seed_verified_before_any_diagnostic_image(self):
        body = self.build
        self.assertIn("AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_BUILD:-0", body)
        self.assertLess(body.index("product_sd1_official_seed.py verify"),
                        body.index("mkdir -p \"$out\""))
        self.assertLess(body.index("[[ ! -e \"$out\" ]]"),
                        body.index("cp -a --reflink=auto"))
        self.assertIn("source_dir/.aethercore-linux-source-sha256", body)
        self.assertIn("ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638", body)
        self.assertIn("cmp -s \"$official_config\" \"$obj/.config\"", body)

    def test_distinct_artifact_qualification_and_no_official_image_writes(self):
        body = self.build
        self.assertIn("product-sd1-gpio-cs-diagnostic", body)
        self.assertIn("READY_FOR_SHORT_RTL_ONLY", body)
        self.assertIn("GPIO_CS_DIAGNOSTIC_KERNEL_FIRMWARE_NOT_DEBIAN_PASS", body)
        self.assertIn("RV64_PAYLOAD_LINUX_BUILD_DIR=\"$linux\"", body)
        self.assertIn("RV64_PAYLOAD_BUILD_DIR=\"$out/boot\"", body)
        self.assertNotIn('rm -rf "${ROOT}/build/rv64-debian-', body)

    def test_manual_only_workflow_and_separate_cache(self):
        workflow = self.workflow
        self.assertIn("types: [labeled]", workflow)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertIn("github.event.pull_request.number == 379", workflow)
        self.assertIn("product-sd1-gpio-cs-diag-kernel", workflow)
        self.assertIn("product_sd1_official_seed.py verify", workflow)
        self.assertIn("sd1-gpio-cs-diagnostic-image-v1", workflow)
        self.assertNotIn("product_checkpoint_sd1_short.sh advance-only", workflow)
        self.assertNotIn("AETHERCORE_SD1_LONGRUN_CONFIRM", workflow)

    def test_object_probe_precedes_full_kernel_and_cannot_save_fake_image(self):
        body = self.build
        workflow = self.workflow
        self.assertIn("product-sd1-gpio-cs-obj-probe", workflow)
        self.assertIn("AETHERCORE_SD1_GPIO_CS_OBJECT_PROBE", body)
        self.assertLess(body.index("drivers/mmc/host/mmc_spi.o"),
                        body.index('Image 2>&1 | tee "$out/linux-build.log"'))
        self.assertIn("object-probe/identity.txt", workflow)
        self.assertIn("kernel_patch_applied=1", body)


if __name__ == "__main__":
    unittest.main()
