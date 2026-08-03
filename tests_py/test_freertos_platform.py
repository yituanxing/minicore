from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"
MAKEFILE = ROOT / "Makefile.freertos"
CI_SCRIPT = ROOT / "tools" / "ci" / "full_gate_freertos.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "full-gate.yml"


class FreeRtosPlatformTest(unittest.TestCase):
    def test_shell_gate_has_valid_syntax_and_exact_evidence_contract(self) -> None:
        result = subprocess.run(
            ["bash", "-n", str(CI_SCRIPT)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        text = CI_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("make -f Makefile.freertos run-local", text)
        self.assertIn("workload_messages=64", text)
        self.assertIn("workload_semaphore_batches=8", text)
        self.assertIn("local_stall_period=5", text)
        self.assertIn("binary_sha256=", text)

    def test_build_uses_only_the_initial_rv32im_zicsr_profile(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("-march=rv32im_zicsr", text)
        self.assertIn("-mabi=ilp32", text)
        self.assertIn("-msmall-data-limit=0", text)
        self.assertNotIn("-march=rv32imac", text)
        self.assertNotIn("-march=rv32ima", text)
        self.assertNotIn("-march=rv32imaf", text)
        self.assertIn("KERNEL_C_SOURCES := tasks.c queue.c list.c", text)
        self.assertNotIn("event_groups.c", text)
        self.assertNotIn("stream_buffer.c", text)
        self.assertNotIn("croutine.c", text)
        self.assertIn("portable/MemMang/heap_4.c", text)
        self.assertIn("portable/GCC/RISC-V", text)
        self.assertIn("RISCV_MTIME_CLINT_no_extensions", text)
        self.assertIn("--self-check-exit --stall-period 5", text)

    def test_startup_delegates_traps_and_initializes_invariant_gp(self) -> None:
        text = (APP / "startup.S").read_text(encoding="utf-8")
        self.assertIn("freertos_risc_v_trap_handler", text)
        self.assertIn("csrw mtvec, t0", text)
        self.assertIn(".option norelax", text)
        self.assertIn("la gp, __global_pointer$", text)
        self.assertIn("call main", text)
        self.assertNotIn("vTaskSwitchContext", text)
        self.assertNotIn("xTaskIncrementTick", text)
        linker = (APP / "linker.ld").read_text(encoding="utf-8")
        self.assertIn("__global_pointer$ = . + 0x800", linker)

    def test_configuration_leaves_headroom_before_the_first_tick(self) -> None:
        text = (APP / "FreeRTOSConfig.h").read_text(encoding="utf-8")
        self.assertIn("configUSE_PREEMPTION                    1", text)
        self.assertIn("configCPU_CLOCK_HZ                      1000000UL", text)
        self.assertIn("configTICK_RATE_HZ                      1000UL", text)
        self.assertIn("configMTIME_BASE_ADDRESS                0x0200bff8UL", text)
        self.assertIn("configMTIMECMP_BASE_ADDRESS             0x02004000UL", text)
        self.assertIn("configENABLE_FPU                        0", text)
        self.assertIn("configENABLE_VPU                        0", text)
        self.assertIn("configNUMBER_OF_CORES                   1", text)

    def test_timer_glue_uses_the_safe_rv32_compare_sequence(self) -> None:
        text = (APP / "platform.c").read_text(encoding="utf-8")
        self.assertIn('csrr %0, mhartid', text)
        first = text.index("compare[ 0 ] = UINT32_MAX")
        high = text.index("compare[ 1 ] =", first)
        low = text.index("compare[ 0 ] = ( uint32_t ) firstDeadline", high)
        self.assertLess(first, high)
        self.assertLess(high, low)
        self.assertIn("ullNextTime = firstDeadline +", text)
        self.assertIn("pullMachineTimerCompareRegister", text)

    def test_workload_requires_tick_preemption_queue_and_semaphore_progress(self) -> None:
        text = (APP / "main.c").read_text(encoding="utf-8")
        self.assertIn("xQueueCreate", text)
        self.assertIn("xQueueSend", text)
        self.assertIn("xQueueReceive", text)
        self.assertIn("xSemaphoreCreateBinary", text)
        self.assertIn("xSemaphoreGive", text)
        self.assertIn("xSemaphoreTake", text)
        self.assertIn("vTaskDelay( 1 )", text)
        self.assertIn("taskYIELD()", text)
        self.assertIn("consumedSum == EXPECTED_SUM", text)
        self.assertIn("ticks >= 16U", text)
        self.assertIn("aether_exit( 0U )", text)

    def test_full_gate_runs_freertos_immediately_after_fast_source_tests(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        newlib = text.index("Provision pinned RISC-V newlib sysroot")
        source_tests = text.index("Fast source and image tests")
        freertos = text.index("FreeRTOS V11.3.0 preemptive queue workload")
        chisel = text.index("Chisel unit tests")
        isolated = text.index("RV32IMU isolated preemptive scheduler")
        self.assertLess(newlib, source_tests)
        self.assertLess(source_tests, freertos)
        self.assertLess(freertos, chisel)
        self.assertLess(freertos, isolated)
        self.assertIn("bash tools/ci/full_gate_freertos.sh", text)


if __name__ == "__main__":
    unittest.main()
