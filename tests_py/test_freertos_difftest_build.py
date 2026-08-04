from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.freertos-difftest"
CI_SCRIPT = ROOT / "tools" / "ci" / "full_gate_freertos_difftest.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "full-gate.yml"


class FreeRtosDifftestBuildTest(unittest.TestCase):
    def test_build_generates_layered_adapters_without_replacing_timer_reference(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("include Makefile.freertos", text)
        self.assertIn("nemu_difftest_rv32_freertos_base.cpp", text)
        self.assertIn("nemu_difftest_rv32_freertos_wfi.cpp", text)
        self.assertIn("tools/make_freertos_difftest_adapter.py", text)
        self.assertIn("tools/make_freertos_wfi_difftest_adapter.py", text)
        self.assertIn("sim/nemu_difftest_rv32_timer.cpp", text)
        self.assertIn("DIFFTEST_SIM_OBJ_DIR", text)
        self.assertNotIn("update_file", text)

    def test_generated_runner_emits_all_shadow_counters(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        runner = (ROOT / "tools" / "make_freertos_difftest_runner.py").read_text(
            encoding="utf-8"
        )
        self.assertIn("difftest-runner-source: $(DIFFTEST_GENERATED_MAIN)", text)
        self.assertIn("difftest-sim: $(DIFFTEST_SIM_BINARY)", text)
        self.assertIn("tools/make_freertos_difftest_runner.py", text)
        for accessor in (
            "checkedCommits()",
            "zicsrShadowSteps()",
            "trapShadowSteps()",
            "fenceShadowSteps()",
            "wfiShadowSteps()",
            "mretShadowSteps()",
            "interruptShadowSteps()",
        ):
            self.assertIn(accessor, runner)

    def test_positive_gate_keeps_local_self_check_and_external_reference(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("run-difftest: qualification image difftest-sim", text)
        self.assertIn("STALL_PERIOD ?= 5", text)
        self.assertIn("--self-check-exit --stall-period $(STALL_PERIOD)", text)
        self.assertIn('--difftest "$(abspath $(RV32_NEMU_SO))"', text)
        self.assertIn("FREERTOS BOOT V11.3.0 RV32IM", text)
        self.assertIn(
            "FREERTOS TICKLESS PASS sleep>=1 wake>=1 suppressed>=2", text
        )
        self.assertIn("FREERTOS PASS queue=64 semaphore=8 ticks>=48", text)
        for counter in (
            "wfi-commits=",
            "masked-wfi-commits=",
            "wfi-sleep-cycles=",
            "difftest=",
            "zicsr-shadow=",
            "trap-shadow=",
            "fence-shadow=",
            "wfi-shadow=",
            "mret-shadow=",
            "interrupt-shadow=",
        ):
            self.assertIn(counter, text)
        self.assertIn("retired while WFI sleep was asserted", text)

    def test_negative_gate_requires_a_first_event_mismatch(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("run-difftest-negative", text)
        self.assertIn("AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=0", text)
        self.assertIn("test $$status -ne 0", text)
        self.assertIn("mismatch after 0 matched events", text)

    def test_ci_gate_freezes_tickless_counts_hashes_and_clean_trace_zero_build(self) -> None:
        syntax = subprocess.run(
            ["bash", "-n", str(CI_SCRIPT)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        text = CI_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            'BUILD_DIR="$QUALIFICATION_ROOT/difftest-build"', text
        )
        self.assertIn('rm -rf "$BUILD_DIR" "$EVIDENCE_DIR"', text)
        self.assertIn('BUILD_DIR="$BUILD_DIR" JOBS="$JOBS" TRACE=0', text)
        self.assertIn("for stall in 0 5", text)
        self.assertIn("run-difftest-negative", text)
        self.assertIn("negative_mismatch_at=0", text)
        self.assertIn("tickless_idle=true", text)
        self.assertIn("masked_wfi_wake=true", text)
        self.assertIn("fence_shadow=true", text)
        self.assertIn("wfi_shadow=true", text)
        self.assertIn("wfi_quiescence=true", text)
        self.assertIn(
            "contract=freertos-rv32-exact-difftest-tickless-v1", text
        )
        self.assertIn("reference_revision=8601834e4889e6bf3b6113eb5f824ba7689126f5", text)
        self.assertIn(
            'EXPECTED_REFERENCE_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"',
            text,
        )
        self.assertIn(
            'EXPECTED_ADAPTER_SHA256="ef8871db8119ec7ae3091e289f55c8c621042fd44d044bfb746028572d616810"',
            text,
        )
        self.assertIn(
            'EXPECTED_RUNNER_SHA256="91f8831718240acdc6541f0b749ba3983d4627487fbebe0d056e5f19c0794ab9"',
            text,
        )
        self.assertIn(
            'EXPECTED_ELF_SHA256="81870780703da81e49d521f8137d8ec2f695d8fc0f7e6bd86aeb96b6fca91396"',
            text,
        )
        self.assertIn(
            'EXPECTED_BINARY_SHA256="b7c29d7da5bc29cb98bb747e2bb34251f27801810003b108db9e7417f3ae2cc3"',
            text,
        )
        self.assertIn(
            "wfi-commits=1, masked-wfi-commits=1, wfi-sleep-cycles=30199",
            text,
        )
        self.assertIn("uart-rx-injected=0, external-seen=0", text)
        self.assertIn("difftest=168046", text)
        self.assertIn("fence-shadow=1", text)
        self.assertIn(
            "wfi-commits=1, masked-wfi-commits=1, wfi-sleep-cycles=30059",
            text,
        )
        self.assertIn("difftest=169735", text)
        self.assertIn('cp "$adapter" "$EVIDENCE_DIR/"', text)
        self.assertIn('cp "$runner" "$EVIDENCE_DIR/"', text)
        self.assertIn('cp "$elf" "$EVIDENCE_DIR/"', text)
        self.assertIn('cp "$binary" "$EVIDENCE_DIR/"', text)
        self.assertIn("stall0_summary=", text)
        self.assertIn("stall5_summary=", text)

    def test_full_gate_runs_freertos_difftest_after_reference_build(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        local = text.index("FreeRTOS V11.3.0 preemptive queue workload")
        reference = text.index("Build both frozen RV32 NEMU references once")
        freertos_difftest = text.index("FreeRTOS exact RV32 NEMU DiffTest")
        rv32_gcc = text.index("RV32 GCC workload and 585-retirement DiffTest")
        self.assertLess(local, reference)
        self.assertLess(reference, freertos_difftest)
        self.assertLess(freertos_difftest, rv32_gcc)
        self.assertIn("bash tools/ci/full_gate_freertos_difftest.sh", text)
        self.assertIn("build/freertos-qualification/difftest-evidence/", text)


if __name__ == "__main__":
    unittest.main()
