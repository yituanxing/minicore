from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxHandoffContractTest(unittest.TestCase):
    def test_frozen_linux_identity_is_exact(self):
        text = (ROOT / "software/l32/linux-freeze.env").read_text()
        self.assertIn(
            "L32_LINUX_IMAGE_SHA256=5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048",
            text,
        )
        self.assertIn(
            "L32_LINUX_BUILD_CACHE_KEY=e91bff604ed9d93d2fcb2f6049fd104281e2c05ae9e2756af69e6af477c68822",
            text,
        )
        self.assertIn("L32_LINUX_PHYS_ENTRY=0x80400000", text)

    def test_builder_reuses_frozen_linux_and_explicit_payload_offset(self):
        text = (ROOT / "tools/ci/l32_linux_handoff_build.sh").read_text()
        self.assertIn("l32_linux_cache_key.sh", text)
        self.assertIn("L32_LINUX_IMAGE_SHA256", text)
        self.assertIn("FW_PAYLOAD_PATH", text)
        self.assertIn("FW_PAYLOAD_OFFSET=0x400000", text)
        self.assertIn("L32_LINUX_PHYS_ENTRY", text)
        self.assertNotIn("l32_linux_build.sh", text)

    def test_runner_requires_real_linux_physical_entry(self):
        text = (ROOT / "sim/linux_handoff_main.cpp").read_text()
        self.assertIn("kLinuxPhysicalEntry = 0x80400000ULL", text)
        self.assertIn("L32_LINUX_ENTRY_PASS", text)
        self.assertIn("OpenSBI v1.6", text)
        self.assertNotIn("Test payload running", text)

    def test_makefile_uses_linux_specific_runner(self):
        text = (ROOT / "Makefile.l32-linux-handoff").read_text()
        self.assertIn("sim/linux_handoff_main.cpp", text)
        self.assertIn("L32_LINUX_ENTRY_PASS", text)
        self.assertIn("MAX_CYCLES ?= 12000000", text)

    def test_workflow_does_not_rebuild_linux(self):
        text = (ROOT / ".github/workflows/l32-linux-handoff.yml").read_text()
        self.assertIn("L32 OpenSBI to Linux Handoff", text)
        self.assertIn("l32_linux_cache_key.sh check", text)
        self.assertIn("l32_linux_handoff_build.sh", text)
        self.assertIn("Makefile.l32-linux-handoff", text)
        self.assertNotIn("l32_linux_build.sh", text)


if __name__ == "__main__":
    unittest.main()
