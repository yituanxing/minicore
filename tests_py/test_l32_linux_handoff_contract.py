from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxHandoffContractTest(unittest.TestCase):
    def test_historical_l32c_checkpoint_remains_exact_legacy_evidence(self):
        text = (ROOT / "software/l32/linux-freeze.env").read_text()
        self.assertIn(
            "L32_LINUX_LEGACY_IMAGE_SHA256=5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048",
            text,
        )
        self.assertIn(
            "L32_LINUX_LEGACY_BUILD_CACHE_KEY=e91bff604ed9d93d2fcb2f6049fd104281e2c05ae9e2756af69e6af477c68822",
            text,
        )
        self.assertIn("L32_LINUX_LEGACY_RUN_ID=31290514249", text)
        self.assertIn("L32_LINUX_LEGACY_ARTIFACT_ID=9031239454", text)
        self.assertIn("L32_LINUX_RECIPE_VERSION=canonical-v1", text)
        self.assertIn("L32_LINUX_PHYS_ENTRY=0x80400000", text)

    def test_builder_reuses_validated_canonical_linux_and_explicit_payload_offset(self):
        text = (ROOT / "tools/ci/l32_linux_handoff_build.sh").read_text()
        self.assertIn("l32_linux_cache_key.sh", text)
        self.assertIn("L32_LINUX_IMAGE_SHA256", text)
        self.assertIn("FW_PAYLOAD_PATH", text)
        self.assertIn("FW_PAYLOAD_OFFSET=0x400000", text)
        self.assertIn("L32_LINUX_PHYS_ENTRY", text)
        self.assertIn("cache key identifies the current build/check recipe", text)
        self.assertNotIn('observed_cache_key}" == "${L32_LINUX_LEGACY_BUILD_CACHE_KEY}', text)
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

    def test_workflow_uses_single_owner_linux_producer_and_fail_closed_consumer(self):
        text = (ROOT / ".github/workflows/l32-linux-handoff.yml").read_text()
        producer = (ROOT / ".github/workflows/l32-linux-build.yml").read_text()

        self.assertIn("L32 OpenSBI to Linux Handoff", text)
        self.assertIn("uses: ./.github/workflows/l32-linux-build.yml", text)
        self.assertIn("needs: linux-software", text)
        self.assertIn("Restore qualified canonical L32 Linux output", text)
        self.assertIn("l32-linux-qualified-v2", text)
        self.assertIn("fail-on-cache-miss: true", text)
        self.assertIn("Restore or verify canonical Linux software image", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh check build/l32-linux", text)
        self.assertNotIn("if ! tools/ci/l32_linux_cache_key.sh check build/l32-linux", text)
        self.assertNotIn("tools/ci/l32_linux_build.sh\n", text)
        self.assertNotIn("tools/ci/l32_linux_cache_key.sh mark build/l32-linux", text)
        self.assertIn("L32_LINUX_IMAGE_SHA256", text)
        self.assertIn("L32_LINUX_VMLINUX_SHA256", text)
        self.assertIn("L32_LINUX_CONFIG_SHA256", text)
        self.assertIn("l32_linux_handoff_build.sh", text)
        self.assertIn("Makefile.l32-linux-handoff", text)

        self.assertIn("workflow_call:", producer)
        self.assertIn("aethercore-l32-linux-qualified-v2-", producer)
        self.assertIn("cancel-in-progress: false", producer)
        self.assertIn("reusable single owner", producer)

    def test_handoff_preserves_validated_layers_before_runtime_failure(self):
        text = (ROOT / ".github/workflows/l32-linux-handoff.yml").read_text()
        self.assertIn("actions/cache/restore@v4", text)
        self.assertIn("actions/cache/save@v4", text)
        self.assertIn("Save validated RV32 Linux GCC immediately", text)
        self.assertIn("Save validated OpenSBI source immediately", text)
        self.assertIn("Save fixed Verilator immediately", text)
        self.assertIn("Save Scala dependencies after handoff elaboration", text)
        self.assertIn("Save exact-head handoff simulation build", text)
        self.assertIn("real OpenSBI to Linux physical-entry probe still executes every qualification run", text)


if __name__ == "__main__":
    unittest.main()
