from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "tools" / "make_aethercore_nuttx_n3_overlay.py"
SCRIPT = ROOT / "tools" / "ci" / "nuttx_n3_timer.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"


class NuttxN3ContractTest(unittest.TestCase):
    def test_timer_overlay_preserves_n2_and_excludes_plic(self) -> None:
        text = OVERLAY.read_text()
        self.assertIn("CONFIG_AETHERCORE_TIMER", text)
        self.assertIn("SET_CSR(CSR_STATUS, STATUS_IE);", text)
        self.assertIn('"CONFIG_SUPPRESS_INTERRUPTS": False', text)
        self.assertIn('"CONFIG_INIT_ENTRYPOINT": \'"ostest_main"\'', text)
        self.assertNotIn("RISCV_IRQ_EXT", text)
        self.assertNotIn("QEMU_RV_PLIC", text)

    def test_bounded_ostest_requires_timer_wakeup(self) -> None:
        text = SCRIPT.read_text()
        required = (
            "AETHERCORE_NUTTX_N3_MAX_CYCLES",
            "CONFIG_TESTING_OSTEST_LOOPS=1",
            "CONFIG_TESTING_OSTEST_RR_RANGE=100",
            "user_main: Begin argument test",
            "proof=ostest-woke-after-500ms-usleep",
            "external_interrupts=disabled-until-n4",
        )
        for fragment in required:
            self.assertIn(fragment, text)
        self.assertIn('[[ "${rc}" -eq 2 ]]', text)
        self.assertIn("PANIC|EXCEPTION:|irq_unexpected_isr", text)

    def test_n3_remains_in_the_single_bounded_stage_job(self) -> None:
        text = WORKFLOW.read_text()
        self.assertEqual(
            text.count("runs-on: [self-hosted, Linux, X64, minicore]"), 1
        )
        self.assertEqual(text.count("timeout-minutes: 45"), 1)
        self.assertNotIn("Fast Gate", text)
        self.assertNotIn("full-validation", text)


if __name__ == "__main__":
    unittest.main()
