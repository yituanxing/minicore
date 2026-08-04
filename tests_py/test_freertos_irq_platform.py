from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
IRQ_MAKEFILE = ROOT / "Makefile.freertos-irq"
IRQ_CI_SCRIPT = ROOT / "tools" / "ci" / "full_gate_freertos_irq.sh"
FULL_WORKFLOW = ROOT / ".github" / "workflows" / "full-gate.yml"
APP = ROOT / "software" / "freertos" / "aethercore"


class FreeRtosIrqPlatformTest(unittest.TestCase):
    def test_irq_full_gate_script_has_valid_syntax_and_frozen_contract(self) -> None:
        result = subprocess.run(
            ["bash", "-n", str(IRQ_CI_SCRIPT)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

        text = IRQ_CI_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("contract=freertos-rv32-machine-external-unified-v1", text)
        self.assertIn("stall_periods=0,2,3,5,7", text)
        self.assertIn("negative_wrong_byte=true", text)
        self.assertIn("negative_missing_external_event=true", text)
        self.assertIn("event_groups=true", text)
        self.assertIn("software_timers=true", text)
        self.assertIn("stream_buffers=true", text)
        self.assertIn("message_buffers=true", text)
        self.assertIn("port_yield_from_isr=true", text)

    def test_irq_makefile_runs_positive_matrix_and_fail_closed_probes(self) -> None:
        text = IRQ_MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("IRQ_STALL_PERIODS := 0 2 3 5 7", text)
        self.assertIn("stream_buffer.c", text)
        self.assertIn("buffer_qualification.c", text)
        self.assertIn("--inject-uart-rx 0x5a", text)
        self.assertIn("--inject-uart-rx 0x5b", text)
        self.assertIn("irq-negative-wrong-byte.log", text)
        self.assertIn("irq-negative-missing-event.log", text)
        self.assertIn("FREERTOS STREAM BUFFER PASS bytes=8 handoff=1", text)
        self.assertIn("FREERTOS MESSAGE BUFFER PASS bytes=7 handoff=1", text)
        self.assertIn("FAIL: timeout after 600000 cycles", text)
        self.assertIn("if [ \"$$stall\" -eq 0 ]", text)

    def test_kernel_object_sources_require_priority_handoffs(self) -> None:
        buffers = (APP / "buffer_qualification.c").read_text(encoding="utf-8")
        events = (APP / "event_group_qualification.c").read_text(encoding="utf-8")
        self.assertIn("xStreamBufferCreate", buffers)
        self.assertIn("xStreamBufferSend", buffers)
        self.assertIn("xStreamBufferReceive", buffers)
        self.assertIn("xMessageBufferCreate", buffers)
        self.assertIn("xMessageBufferSend", buffers)
        self.assertIn("xMessageBufferReceive", buffers)
        self.assertIn("streamReceiverDone == 1U", buffers)
        self.assertIn("messageReceiverDone == 1U", buffers)
        self.assertIn("xEventGroupWaitBits", events)
        self.assertIn("xTimerCreate", events)
        self.assertIn("aether_start_buffer_qualification", events)
        self.assertIn("kernel_object_report_task", events)
        self.assertIn("aetherStreamBufferDone == 1U", events)
        self.assertIn("aetherMessageBufferDone == 1U", events)

    def test_full_gate_places_irq_matrix_before_reference_difftest(self) -> None:
        text = FULL_WORKFLOW.read_text(encoding="utf-8")
        base = text.index("FreeRTOS V11.3.0 preemptive queue workload")
        irq = text.index("FreeRTOS Machine external IRQ and kernel-object stress matrix")
        reference = text.index("Build both frozen RV32 NEMU references once")
        difftest = text.index("FreeRTOS exact RV32 NEMU DiffTest")
        self.assertLess(base, irq)
        self.assertLess(irq, reference)
        self.assertLess(reference, difftest)
        self.assertIn("bash tools/ci/full_gate_freertos_irq.sh", text)
        self.assertIn("build/freertos-irq-qualification/evidence/", text)


if __name__ == "__main__":
    unittest.main()
