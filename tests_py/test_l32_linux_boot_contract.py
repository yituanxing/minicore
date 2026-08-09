from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxBootContractTest(unittest.TestCase):
    def test_runtime_uses_exact_frozen_linux_outputs(self):
        text = (ROOT / "software/l32/linux-runtime.env").read_text()
        self.assertIn(
            "L32_LINUX_IMAGE_SHA256=5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048",
            text,
        )
        self.assertIn(
            "L32_LINUX_VMLINUX_SHA256=2b0307994ca640b2fead335f690ea6d4ce6e1a5fb67857a31f13999308aa50d7",
            text,
        )
        self.assertIn(
            "L32_LINUX_CONFIG_SHA256=c344bc909736ad9c8932d077467c473022be69bc4bbeea658c5b743e8b923e27",
            text,
        )
        self.assertIn("L32_LINUX_IMAGE_BYTES=30147828", text)
        self.assertIn("L32_LINUX_PHYS_ENTRY=0x80400000", text)
        self.assertIn("L32_LINUX_VIRT_ENTRY=0xc0000000", text)
        self.assertIn("L32_LINUX_VERSION_MARKER=Linux version 6.6.143", text)

    def test_opensbi_builder_can_isolate_an_external_payload_without_changing_default(self):
        text = (ROOT / "tools/ci/l32_opensbi_build.sh").read_text()
        self.assertIn("L32_OPENSBI_BUILD_DIR", text)
        self.assertIn("L32_FW_PAYLOAD_PATH", text)
        self.assertIn('MAKE_PAYLOAD_ARGS+=("FW_PAYLOAD_PATH=${FW_PAYLOAD_PATH}")', text)
        self.assertIn("builtin-test-payload", text)
        self.assertIn('BUILD_DIR="${L32_OPENSBI_BUILD_DIR:-${ROOT_DIR}/build/l32-opensbi}"', text)

    def test_firmware_prepare_is_fail_closed_on_kernel_hashes_and_payload_vma(self):
        text = (ROOT / "tools/ci/l32_linux_fw_payload.sh").read_text()
        for required in (
            "l32_linux_cache_key.sh",
            "L32_LINUX_VMLINUX_SHA256",
            "L32_LINUX_IMAGE_SHA256",
            "L32_LINUX_CONFIG_SHA256",
            "L32_LINUX_IMAGE_BYTES",
            "L32_OPENSBI_BUILD_DIR",
            "L32_FW_PAYLOAD_PATH",
            'awk \'$2 == ".payload"',
            "L32_LINUX_PHYS_ENTRY",
            "L32_LINUX_FW_PAYLOAD_RESULT: status=PASS",
        ):
            self.assertIn(required, text)

    def test_probe_requires_opensbi_linux_physical_entry_and_version_banner(self):
        text = (ROOT / "sim/linux_boot_main.cpp").read_text()
        self.assertIn("0x80400000ULL", text)
        self.assertIn("0xc0000000ULL", text)
        self.assertIn('uart.find("OpenSBI v1.6")', text)
        self.assertIn('uart.find("Linux version 6.6.143")', text)
        self.assertIn("L32_LINUX_PHYS_ENTRY", text)
        self.assertIn("L32_LINUX_VERSION_PASS", text)
        self.assertIn("L32_LINUX_EXCEPTION_LIVELOCK", text)
        self.assertNotIn("Test payload running", text)

    def test_makefile_runs_linux_probe_with_bounded_budget(self):
        text = (ROOT / "Makefile.l32-linux-probe").read_text()
        self.assertIn("AetherCoreOpenSbiSimTop", text)
        self.assertIn("sim/linux_boot_main.cpp", text)
        self.assertIn("build/l32-linux-opensbi", text)
        self.assertIn("MAX_CYCLES ?= 100000000", text)
        self.assertIn("L32_LINUX_VERSION_PASS", text)

    def test_workflow_keeps_build_and_execution_in_one_self_hosted_job(self):
        text = (ROOT / ".github/workflows/l32-linux-boot.yml").read_text()
        self.assertIn("L32 Linux First Boot", text)
        self.assertIn("self-hosted, Linux, X64, minicore", text)
        self.assertIn("tools/ci/l32_linux_fw_payload.sh", text)
        self.assertIn("tools/ensure_verilator_5_024.sh", text)
        self.assertIn("Makefile.l32-linux-probe", text)
        self.assertIn("MAX_CYCLES=100000000", text)
        self.assertIn("build/l32-linux-probe/logs", text)


if __name__ == "__main__":
    unittest.main()
