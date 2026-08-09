from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxEarlyBootContractTest(unittest.TestCase):
    def test_fdt_supports_optional_linux_bootargs(self):
        text = (ROOT / "tools/ci/make_l32_dtb.py").read_text()
        self.assertIn('parser.add_argument("--bootargs")', text)
        self.assertIn('b.prop("bootargs", string(bootargs))', text)

    def test_builder_reuses_frozen_image_and_adds_only_observability(self):
        text = (ROOT / "tools/ci/l32_linux_early_boot_build.sh").read_text()
        self.assertIn("L32_LINUX_IMAGE_SHA256", text)
        self.assertIn("l32_linux_cache_key.sh", text)
        self.assertIn("FW_PAYLOAD_OFFSET=0x400000", text)
        self.assertIn("earlycon=uart8250,mmio,0x10000000", text)
        self.assertIn("console=ttyS0,115200", text)
        self.assertNotIn("l32_linux_build.sh", text)

    def test_runner_tracks_physical_entry_sv32_and_first_linux_exception(self):
        text = (ROOT / "sim/linux_early_boot_main.cpp").read_text()
        self.assertIn("kLinuxPhysicalEntry = 0x80400000ULL", text)
        self.assertIn("kLinuxVirtualBase = 0xc0000000ULL", text)
        self.assertIn("L32_LINUX_PHYSICAL_ENTRY", text)
        self.assertIn("L32_LINUX_HIGH_VA", text)
        self.assertIn("L32_LINUX_FIRST_EXCEPTION", text)
        self.assertIn("L32_LINUX_EARLY_CONSOLE_PASS", text)
        self.assertIn("Linux version 6.6.143", text)

    def test_workflow_uses_exact_frozen_payload_without_kernel_rebuild(self):
        text = (ROOT / ".github/workflows/l32-linux-early-boot.yml").read_text()
        self.assertIn("L32 Linux 6.6.143 Early Boot", text)
        self.assertIn("l32_linux_early_boot_build.sh", text)
        self.assertIn("Makefile.l32-linux-early-boot", text)
        self.assertNotIn("l32_linux_build.sh", text)


if __name__ == "__main__":
    unittest.main()
