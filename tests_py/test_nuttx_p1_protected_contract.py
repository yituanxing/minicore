from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ci" / "nuttx_p1_protected_build.sh"
PREPARE = ROOT / "tools" / "ci" / "nuttx_prepare_host_tools.sh"
OVERLAY = ROOT / "tools" / "make_aethercore_nuttx_protected_overlay.py"
PROTECTED_WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-protected-stage.yml"
FROZEN_WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"


class NuttxP1ProtectedContractTest(unittest.TestCase):
    def test_p1_builds_separate_kernel_and_user_images(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "rv-virt:pnsh",
            "CONFIG_BUILD_PROTECTED",
            "CONFIG_ARCH_USE_MPU",
            "CONFIG_LIB_SYSCALL",
            "CONFIG_RISCV_PERCPU_SCRATCH",
            '"CONFIG_ARCH_ADDRENV": False',
            '"CONFIG_ARCH_KERNEL_STACK": False',
            "CONFIG_NUTTX_USERSPACE=0x80040000",
            "nuttx_user",
            "aethercore-protected.bin",
            "kernel_privilege=M",
            "userspace_privilege=U",
            "user_programs=nsh,hello",
        ):
            self.assertIn(fragment, text)
        self.assertNotIn("CONFIG_ARCH_KERNEL_STACKSIZE=2048", text)

    def test_p1_reuses_frozen_aethercore_os_boundaries(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "make_aethercore_nuttx_overlay.py",
            "make_aethercore_nuttx_n3_overlay.py",
            "make_aethercore_nuttx_n4_overlay.py",
            "make_aethercore_nuttx_protected_overlay.py",
            "CONFIG_AETHERCORE_UART",
            "CONFIG_AETHERCORE_TIMER",
            "CONFIG_AETHERCORE_UART_RX_IRQ",
        ):
            self.assertIn(fragment, text)

    def test_platform_binds_userspace_to_implemented_pmp_entries(self) -> None:
        text = OVERLAY.read_text()
        for fragment in (
            "riscv_config_pmp_region(0, UFLASH_F",
            "riscv_config_pmp_region(1, USRAM_F",
            '"CONFIG_LIB_SYSCALL": True',
            '"CONFIG_RISCV_PERCPU_SCRATCH": True',
            '"CONFIG_ARCH_ADDRENV": False',
            '"CONFIG_ARCH_KERNEL_STACK": False',
            "AetherCore exposes four PMP entries",
            "Dedicated kernel-stack hardening is a later architecture milestone",
        ):
            self.assertIn(fragment, text)
        self.assertIn('if "riscv_append_pmp_region(" in generated', text)
        self.assertNotIn("CONFIG_ARCH_KERNEL_STACKSIZE", text)

    def test_p1_requires_real_user_transition_and_syscall_dispatch(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "riscv_jump_to_user",
            "exception_common",
            "dispatch_syscall",
            "syscall_boundary=ecall-riscv_swint-dispatch_syscall",
            "user_transition=riscv_jump_to_user-mret",
            "percpu_scratch=enabled",
            "syscall_stack=caller-user-stack-upstream-protected-pmp",
            "kernel_stack_hardening=deferred-requires-addrenv-aware-port",
        ):
            self.assertIn(fragment, text)
        self.assertNotIn("user_stack_trap_frames=forbidden", text)

    def test_p1_is_fail_closed_on_isolation_and_isa(self) -> None:
        text = SCRIPT.read_text()
        for fragment in (
            "qemu_rv_configure_mpu",
            "riscv_config_pmp_region",
            "riscv_swint",
            "pmp_entries_implemented=4",
            "pmp_entries_used=0,1",
            "pmp_free_scan=disabled-in-platform-init",
            "pmp_mode=NAPOT",
            "address_environment=disabled",
            "forbidden extension",
            "kernel load image",
            "runtime=not-yet-qualified",
        ):
            self.assertIn(fragment, text)
        self.assertNotIn("riscv_append_pmp_region riscv_swint", text)
        for forbidden in (
            "CONFIG_ARCH_ADDRENV",
            "CONFIG_ARCH_KERNEL_STACK",
            "CONFIG_ARCH_USE_MMU",
            "CONFIG_ARCH_USE_S_MODE",
            "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A",
            "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C",
        ):
            self.assertIn(forbidden, text)

    def test_protected_stage_prepares_tools_without_rebuilding_n1_to_n4(self) -> None:
        prepare = PREPARE.read_text()
        workflow = PROTECTED_WORKFLOW.read_text()
        for fragment in (
            'KCONFIGLIB_VERSION="14.1.0"',
            '"kconfiglib==${KCONFIGLIB_VERSION}"',
            "ensure_genromfs.sh",
            "P0 PASS: protected NuttX host tools are ready",
        ):
            self.assertIn(fragment, prepare)
        for fragment in (
            "agent/umode-**",
            "P1 build and P2 U-mode hello",
            "nuttx_prepare_host_tools.sh",
            "nuttx_p1_protected_build.sh",
            "nuttx_p2_protected_boot.sh",
            "context\":\"umode/nuttx-protected",
        ):
            self.assertIn(fragment, workflow)
        for forbidden in (
            "nuttx_n1_build.sh",
            "nuttx_n2_boot.sh",
            "nuttx_n3_timer.sh",
            "nuttx_n4_uart_irq.sh",
        ):
            self.assertNotIn(forbidden, workflow)

    def test_protected_stage_is_one_bounded_self_hosted_job(self) -> None:
        text = PROTECTED_WORKFLOW.read_text()
        self.assertEqual(
            text.count("runs-on: [self-hosted, Linux, X64, minicore]"), 1
        )
        self.assertIn("timeout-minutes: 60", text)
        self.assertNotIn("ubuntu-latest", text)
        self.assertNotIn("full-validation", text)

    def test_frozen_n1_to_n4_stage_does_not_run_on_umode_branches(self) -> None:
        text = FROZEN_WORKFLOW.read_text()
        self.assertIn("agent/nuttx-**", text)
        self.assertNotIn("agent/umode-**", text)
        self.assertIn("N1 build, N2 NSH, N3 timer, and N4 UART RX IRQ", text)
        self.assertNotIn("nuttx_p1_protected_build.sh", text)
        self.assertNotIn("nuttx_p2_protected_boot.sh", text)


if __name__ == "__main__":
    unittest.main()
