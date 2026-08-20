from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / "software/rv64/linux_early.env"
BUILD = ROOT / "tools/ci/rv64_linux_early_build.sh"
WORKFLOW = ROOT / ".github/workflows/rv64-linux-early.yml"
DOC = ROOT / "docs/RV64_LINUX_EARLY_BOOT.md"


class RV64LinuxEarlyContract(unittest.TestCase):
    def test_inputs_are_exact_and_share_frozen_opensbi(self) -> None:
        env = ENV.read_text()
        self.assertIn("RV64_LINUX_VERSION=6.6.143", env)
        self.assertIn(
            "RV64_LINUX_SHA256=dace1f8dc9c0dbf5df14f47e3229cd62c298e83049681731ef229f2ba7592932",
            env,
        )
        self.assertIn("RV64_LINUX_DEFCONFIG=defconfig", env)
        self.assertIn("RV64_LINUX_PHYS_ENTRY=0x80200000", env)
        self.assertIn("Linux version 6.6.143", env)

    def test_build_is_config_only_not_source_patching(self) -> None:
        build = BUILD.read_text()
        self.assertIn('scripts/config" --file', build)
        self.assertIn("-e NONPORTABLE", build)
        self.assertIn("-d PORTABLE", build)
        self.assertIn("CONFIG_NONPORTABLE=y", build)
        self.assertIn("config_is_y PORTABLE", build)
        self.assertNotIn("grep -qx '# CONFIG_PORTABLE is not set'", build)
        self.assertIn("-d EFI", build)
        self.assertIn("-d RISCV_ISA_C", build)
        self.assertIn("-d FPU", build)
        self.assertIn("-d RISCV_ISA_V", build)
        self.assertIn("-d VGA_CONSOLE", build)
        self.assertIn("CONFIG_VGA_CONSOLE is not set", build)
        self.assertIn("CONFIG_64BIT=y", build)
        self.assertIn("CONFIG_SERIAL_8250_CONSOLE=y", build)
        self.assertNotIn("patch -p", build)
        self.assertNotIn("git apply", build)
        self.assertNotIn("sed -i", build)

    def test_handoff_uses_native_rv64_linux_layout(self) -> None:
        build = BUILD.read_text()
        self.assertIn('FW_PAYLOAD_OFFSET="${RV64_OPENSBI_PAYLOAD_OFFSET}"', build)
        self.assertIn('RV64_LINUX_PHYS_ENTRY', build)
        self.assertIn('--isa "${RV64_OPENSBI_ISA}"', build)
        self.assertIn('--mmu "${RV64_OPENSBI_MMU}"', build)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="${RV64_OPENSBI_FDT_ADDR}"', build)
        self.assertIn("rv64ima_zicsr_zifencei", (ROOT / "software/rv64/opensbi_first_exec.env").read_text())

    def test_workflow_reuses_real_rv64_top_and_shared_runner(self) -> None:
        workflow = WORKFLOW.read_text()
        self.assertIn("AetherCoreOpenSbiRV64SimTop", workflow)
        self.assertIn("aethercore.ElaborateOpenSbiRV64", workflow)
        self.assertIn("Makefile.l32-linux-boot", workflow)
        self.assertIn("sim/rv64_opensbi_shim", workflow)
        self.assertIn("Linux version 6.6.143", workflow)
        self.assertNotIn("initramfs", workflow.lower())
        self.assertNotIn("busybox", workflow.lower())

    def test_documented_boundary_is_banner_only(self) -> None:
        doc = DOC.read_text()
        self.assertIn("unchanged Linux 6.6.143", doc)
        self.assertIn("Linux version 6.6.143", doc)
        self.assertIn("no initramfs", doc.lower())
        self.assertIn("new CPU feature", doc)
        self.assertIn("performance optimization", doc)


if __name__ == "__main__":
    unittest.main()
