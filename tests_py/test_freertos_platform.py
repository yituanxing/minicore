from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"
MAKEFILE = ROOT / "Makefile.freertos"
CI_SCRIPT = ROOT / "tools" / "ci" / "full_gate_freertos.sh"
VERILATOR_INSTALLER = ROOT / "tools" / "ensure_verilator_5_024.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "full-gate.yml"
FAST_WORKFLOW = ROOT / ".github" / "workflows" / "fast-gate.yml"


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
        self.assertIn('make -j"$JOBS" -f Makefile.freertos', text)
        self.assertIn('JOBS="${AETHERCORE_JOBS:-0}"', text)
        self.assertIn("workload_messages=64", text)
        self.assertIn("workload_semaphore_batches=8", text)
        self.assertIn("idle_wfi=true", text)
        self.assertIn("idle_wfi_wake=true", text)
        self.assertIn("parallel_jobs=$JOBS", text)
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

    def test_build_is_parallel_and_incremental_without_weakening_clean_full_gate(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("JOBS ?= $(shell nproc)", text)
        self.assertIn("SOURCE_STAMP := $(BUILD_DIR)/.freertos-source-ready", text)
        self.assertNotIn("SOURCE_STAMP := $(SOURCE_DIR)", text)
        self.assertIn("rm -f $(SOURCE_DIR)/.aethercore-source-ready", text)
        self.assertIn("RTL_STAMP :=", text)
        self.assertIn("SIM_BINARY :=", text)
        self.assertIn("$(SIM_BINARY): $(RTL_STAMP) $(GENERATED_MAIN)", text)
        self.assertIn("--build -j $(JOBS) $(VERILATOR_TRACE_FLAGS)", text)
        self.assertIn("TRACE ?= 1", text)
        shell = CI_SCRIPT.read_text(encoding="utf-8")
        self.assertIn('rm -rf "$BUILD_DIR"', shell)
        self.assertIn("TRACE=1 run-local", shell)

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

    def test_full_gate_is_milestone_only_and_keeps_the_complete_order(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("- .github/full-gate-request", text)
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("AETHERCORE_VERILATOR_FAST_VERIFY", text)
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

    def test_fast_gate_preserves_per_pr_outputs_and_skips_wave_traces(self) -> None:
        text = FAST_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("clean: false", text)
        self.assertIn('AETHERCORE_VERILATOR_FAST_VERIFY: "1"', text)
        self.assertIn("chmod +x mill", text)
        self.assertIn("MILL_OUTPUT_DIR=", text)
        self.assertIn("FAST_FREERTOS_BUILD=", text)
        self.assertIn("AETHERCORE_JOBS=$(nproc)", text)
        self.assertIn("TRACE=0 run-local", text)
        self.assertIn("aethercore.WaitForInterruptCoreSpec", text)
        self.assertNotIn("full_gate_freertos_difftest.sh", text)

    def test_verilator_fast_verify_skips_only_the_disposable_probe(self) -> None:
        text = VERILATOR_INSTALLER.read_text(encoding="utf-8")
        self.assertIn('fast_verify="${AETHERCORE_VERILATOR_FAST_VERIFY:-0}"', text)
        self.assertIn('if [[ "$fast_verify" == "1" ]]; then', text)
        self.assertIn("cmp -s \"$build_dir/src/verilator_bin\"", text)
        self.assertIn("cmp -s \"$source_dir/bin/verilator_includer\"", text)
        self.assertIn('probe_dir="$(mktemp -d)"', text)

        activate_body = text.split("activate() {", 1)[1].split("\n}", 1)[0]
        self.assertNotIn("verify_install", activate_body)
        self.assertIn(
            '[[ -f "$marker" ]] && verify_install; then\n  activate',
            text,
        )
        self.assertIn(
            'printf \'%s\\n\' "$revision" > "$marker"\n  verify_install\n  activate',
            text,
        )
        self.assertTrue(text.rstrip().endswith("verify_install\nactivate"))


if __name__ == "__main__":
    unittest.main()
