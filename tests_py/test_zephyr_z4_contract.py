from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ci" / "zephyr_z4_external_irq.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "zephyr-stage-push.yml"
SIM = ROOT / "sim" / "sim_main.cpp"
UART_KCONFIG = ROOT / "software" / "zephyr" / "drivers" / "serial" / "Kconfig"
APP = ROOT / "software" / "zephyr" / "apps" / "uart_irq_smoke" / "src" / "main.c"


class ZephyrZ4ContractTest(unittest.TestCase):
    def test_external_interrupt_path_is_end_to_end_and_fail_closed(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")
        app = APP.read_text(encoding="utf-8")
        sim = SIM.read_text(encoding="utf-8")
        uart_kconfig = UART_KCONFIG.read_text(encoding="utf-8")

        for token in (
            "--rx-byte 0x5a",
            "--rx-byte 0x34",
            "--rx-start-cycle",
            "--rx-gap-cycles",
            "STALL_PERIODS=(0 3)",
            "AETHERCORE ZEPHYR IRQ PASS bytes=2",
            "negative-no-rx.log",
            "expected 3",
            "contract=zephyr-v3.7.2-aethercore-z4-external-interrupt-v1",
        ):
            self.assertIn(token, script)

        self.assertIn("select SERIAL_HAS_DRIVER", uart_kconfig)
        self.assertIn("select SERIAL_SUPPORT_INTERRUPT", uart_kconfig)

        for token in (
            "uart_irq_callback_user_data_set",
            "uart_irq_rx_enable",
            "uart_fifo_read",
            "k_is_in_isr",
            "k_msgq_put",
            "k_work_submit",
            "k_sem_take(&rx_done, K_MSEC(250))",
        ):
            self.assertIn(token, app)

        for token in (
            'arg == "--rx-byte"',
            'arg == "--rx-start-cycle"',
            'arg == "--rx-gap-cycles"',
            "rxAccepted",
            "accepted \" << rxIndex",
        ):
            self.assertIn(token, sim)

    def test_single_cached_slot_runs_and_archives_z4(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("group: zephyr-stage-slot-global", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        self.assertIn("runs-on: [self-hosted, Linux, X64, minicore]", workflow)
        self.assertEqual(workflow.count("runs-on: [self-hosted, Linux, X64, minicore]"), 1)
        self.assertIn("Run Zephyr Z4 external interrupt qualification", workflow)
        self.assertIn("bash tools/ci/zephyr_z4_external_irq.sh", workflow)
        self.assertIn("minicore/build/zephyr-z4/evidence/", workflow)
        self.assertNotIn(".github/full-gate-request", workflow)


if __name__ == "__main__":
    unittest.main()
