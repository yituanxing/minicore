from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
DTB_BUILDER_PATH = ROOT / "tools/ci/make_l32_dtb.py"
_spec = importlib.util.spec_from_file_location("make_l32_dtb", DTB_BUILDER_PATH)
_dtb = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_dtb)


class L32LinuxBootContractTest(unittest.TestCase):
    def test_payload_build_reuses_exact_frozen_linux_image(self):
        text = (ROOT / "tools/ci/l32_linux_payload_build.sh").read_text()
        self.assertIn("l32_linux_cache_key.sh", text)
        self.assertIn("5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048", text)
        self.assertIn('FW_PAYLOAD_PATH="${LINUX_IMAGE}"', text)
        self.assertIn('FW_PAYLOAD_OFFSET="${FW_PAYLOAD_OFFSET}"', text)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="${FW_PAYLOAD_FDT_ADDR}"', text)
        self.assertIn('FW_PAYLOAD_OFFSET="0x00400000"', text)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="0x87f00000"', text)
        self.assertIn("next_addr=0x80400000", text)
        self.assertIn("next_mode=S-mode", text)
        self.assertIn("L32_LINUX_PAYLOAD_BUILD_RESULT: status=PASS", text)

    def test_linux_fdt_adds_early_serial_bootargs_without_changing_default(self):
        plain = _dtb.build_l32_dtb()
        linux = _dtb.build_l32_dtb(
            "earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200"
        )
        self.assertNotIn(b"bootargs\0", plain)
        self.assertIn(b"bootargs\0", linux)
        self.assertIn(b"earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200\0", linux)
        self.assertIn(b"rv32ima_zicsr_zifencei_sstc\0", linux)
        self.assertIn(b"riscv,sv32\0", linux)

    def test_runtime_probe_keeps_opensbi_regression_and_accepts_linux_marker(self):
        runner = (ROOT / "sim/opensbi_boot_main.cpp").read_text()
        self.assertIn('kDefaultMilestone = "Test payload running"', runner)
        self.assertIn("L32_OPENSBI_TEST_PAYLOAD_PASS", runner)
        self.assertIn("L32_RUNTIME_MILESTONE_PASS", runner)
        self.assertIn("UART_MILESTONE", runner)
        self.assertIn("MIN_INTERRUPTS", runner)
        self.assertIn("L32_FIRST_INTERRUPT", runner)
        self.assertIn("min-interrupts=", runner)

        makefile = (ROOT / "Makefile.l32-linux-boot").read_text()
        self.assertIn("Linux version 6.6.143", makefile)
        self.assertIn("MAX_CYCLES ?= 50000000", makefile)
        self.assertIn("MIN_INTERRUPTS ?= 0", makefile)
        self.assertIn('"$(MIN_INTERRUPTS)"', makefile)
        self.assertIn("L32_FIRST_INTERRUPT", makefile)
        self.assertIn("L32_RUNTIME_MILESTONE_PASS", makefile)
        self.assertIn("build/l32-linux-boot", makefile)

    def test_workflow_is_bounded_to_first_real_linux_console_milestone(self):
        text = (ROOT / ".github/workflows/l32-linux-boot.yml").read_text()
        self.assertIn("Build OpenSBI with frozen Linux payload", text)
        self.assertIn("Run real Linux early-boot probe", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh check", text)
        self.assertIn("tools/ci/l32_linux_build.sh", text)
        self.assertIn("Makefile.l32-linux-boot", text)
        self.assertIn("MAX_CYCLES=50000000", text)
        self.assertNotIn("BusyBox", text)
        self.assertNotIn("/bin/sh", text)


if __name__ == "__main__":
    unittest.main()
