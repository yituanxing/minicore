from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "tools" / "make_aethercore_nuttx_n4_overlay.py"
SCRIPT = ROOT / "tools" / "ci" / "nuttx_n4_uart_irq.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"


class NuttxN4ContractTest(unittest.TestCase):
    def test_overlay_uses_the_frozen_uart_rx_and_single_word_plic(self) -> None:
        text = OVERLAY.read_text()
        required = (
            "CONFIG_AETHERCORE_UART_RX_IRQ",
            "0x10000100u",
            "RISCV_IRQ_EXT + 1",
            "irq_attach(priv->irq, aethercore_interrupt, dev)",
            "uart_recvchars(dev)",
            "QEMU_RV_PLIC_ENABLE1",
            "QEMU_RV_PLIC_PRIORITY + 4",
            "QEMU_RV_PLIC_THRESHOLD",
            "SET_CSR(CSR_IE, IE_EIE)",
            '"CONFIG_INIT_ENTRYPOINT": \'"nsh_main"\'',
        )
        for fragment in required:
            self.assertIn(fragment, text)
        irq_n4 = text.split("IRQ_N4 = r'''", 1)[1].split("'''", 1)[0]
        self.assertNotIn("putreg32(0, QEMU_RV_PLIC_ENABLE2);", irq_n4)

    def test_bounded_gate_requires_real_nsh_console_input_and_isr_return(self) -> None:
        text = SCRIPT.read_text()
        required = (
            "AETHERCORE_NUTTX_N4_MAX_CYCLES",
            "--rx-byte 0x65",
            "--rx-byte 0x0a",
            "N4-IRQ-PASS",
            "prompt_count",
            "stall_periods=0,3",
            "plic-claim-dispatch-complete-and-nsh-fd-console-return",
            "PANIC|EXCEPTION:|irq_unexpected_isr",
        )
        for fragment in required:
            self.assertIn(fragment, text)
        self.assertIn('[[ "${rc}" -eq 2 ]]', text)

    def test_n4_stays_in_the_single_bounded_stage_job(self) -> None:
        text = WORKFLOW.read_text()
        self.assertEqual(
            text.count("runs-on: [self-hosted, Linux, X64, minicore]"), 1
        )
        self.assertEqual(text.count("timeout-minutes: 45"), 1)
        self.assertIn("tests_py.test_nuttx_n4_contract", text)
        self.assertIn("bash tools/ci/nuttx_n4_uart_irq.sh", text)
        self.assertNotIn("Fast Gate", text)
        self.assertNotIn("full-validation", text)


if __name__ == "__main__":
    unittest.main()
