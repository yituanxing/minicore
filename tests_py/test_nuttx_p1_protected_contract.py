from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ci" / "nuttx_p1_protected_build.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"


class NuttxP1ProtectedContractTest(unittest.TestCase):
    def test_p1_builds_separate_kernel_and_user_images(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "rv-virt:pnsh",
            "CONFIG_BUILD_PROTECTED",
            "CONFIG_ARCH_USE_MPU",
            "CONFIG_NUTTX_USERSPACE=0x80040000",
            "nuttx_user",
            "aethercore-protected.bin",
            "kernel_privilege=M",
            "userspace_privilege=U",
            "user_programs=nsh,hello",
        ):
            self.assertIn(fragment, text)

    def test_p1_reuses_frozen_aethercore_os_boundaries(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "make_aethercore_nuttx_overlay.py",
            "make_aethercore_nuttx_n3_overlay.py",
            "make_aethercore_nuttx_n4_overlay.py",
            "CONFIG_AETHERCORE_UART",
            "CONFIG_AETHERCORE_TIMER",
            "CONFIG_AETHERCORE_UART_RX_IRQ",
        ):
            self.assertIn(fragment, text)

    def test_p1_is_fail_closed_on_isolation_and_isa(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "qemu_rv_configure_mpu",
            "riscv_append_pmp_region",
            "riscv_swint",
            "pmp_entries_required=2",
            "pmp_mode=NAPOT",
            "forbidden extension",
            "kernel load image",
            "runtime=not-yet-qualified",
        ):
            self.assertIn(fragment, text)
        self.assertIn("CONFIG_ARCH_USE_S_MODE", text)
        self.assertIn("CONFIG_ARCH_CHIP_QEMU_RV_ISA_A", text)
        self.assertIn("CONFIG_ARCH_CHIP_QEMU_RV_ISA_C", text)

    def test_p1_stays_in_one_bounded_self_hosted_job(self) -> None:
        text = WORKFLOW.read_text()
        self.assertEqual(
            text.count("runs-on: [self-hosted, Linux, X64, minicore]"), 1
        )
        self.assertNotIn("full-validation", text)
        self.assertNotIn("Fast Gate", text)


if __name__ == "__main__":
    unittest.main()
