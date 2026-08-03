from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.freertos-difftest"


class FreeRtosDifftestBuildTest(unittest.TestCase):
    def test_build_generates_a_dedicated_adapter_without_replacing_the_timer_reference(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("include Makefile.freertos", text)
        self.assertIn("nemu_difftest_rv32_freertos.cpp", text)
        self.assertIn("tools/make_freertos_difftest_adapter.py", text)
        self.assertIn("sim/nemu_difftest_rv32_timer.cpp", text)
        self.assertIn("DIFFTEST_SIM_OBJ_DIR", text)
        self.assertNotIn("update_file", text)

    def test_positive_gate_keeps_local_self_check_and_external_reference(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("run-difftest: image difftest-sim", text)
        self.assertIn("--self-check-exit --stall-period 5", text)
        self.assertIn('--difftest "$(abspath $(RV32_NEMU_SO))"', text)
        self.assertIn("FREERTOS BOOT V11.3.0 RV32IM", text)
        self.assertIn("FREERTOS PASS queue=64 semaphore=8 ticks>=16", text)
        self.assertIn("difftest=[1-9][0-9]*", text)

    def test_negative_gate_requires_a_first_event_mismatch(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("run-difftest-negative", text)
        self.assertIn("AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=0", text)
        self.assertIn("test $$status -ne 0", text)
        self.assertIn("mismatch after 0 matched events", text)


if __name__ == "__main__":
    unittest.main()
