from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BASE_ADAPTER = ROOT / "tools" / "make_freertos_difftest_adapter.py"
WFI_ADAPTER = ROOT / "tools" / "make_freertos_wfi_difftest_adapter.py"
RUNNER = ROOT / "tools" / "make_freertos_runner.py"
DIFFTEST_RUNNER = ROOT / "tools" / "make_freertos_difftest_runner.py"


class FreeRtosWfiDifftestTest(unittest.TestCase):
    def run_tool(self, *arguments: str) -> None:
        result = subprocess.run(
            [sys.executable, *arguments],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_generated_adapter_shadows_precise_wfi_wakeup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary) / "base.cpp"
            output = Path(temporary) / "wfi.cpp"
            self.run_tool(
                str(BASE_ADAPTER),
                str(ROOT / "sim" / "nemu_difftest_rv32_timer.cpp"),
                str(base),
            )
            self.run_tool(str(WFI_ADAPTER), str(base), str(output))
            text = output.read_text(encoding="utf-8")
            self.assertIn("constexpr std::uint32_t kWfi = 0x10500073U", text)
            self.assertIn("const bool wfiStep = commit.inst == kWfi", text)
            self.assertIn("after = executeWfi(before, commit)", text)
            self.assertIn("++wfiShadowSteps_", text)
            self.assertIn("reference=wfi-shadow", text)
            self.assertIn("!commit.interrupt", text)
            self.assertIn("interruptPc != before.pc + 4U", text)
            self.assertIn("wfiShadowSteps() const", text)

    def test_generated_runner_rejects_retirement_during_sleep(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            local = Path(temporary) / "local.cpp"
            output = Path(temporary) / "difftest.cpp"
            self.run_tool(
                str(RUNNER),
                str(ROOT / "sim" / "sim_main.cpp"),
                str(local),
                "--trace",
                "0",
            )
            self.run_tool(str(DIFFTEST_RUNNER), str(local), str(output))
            text = output.read_text(encoding="utf-8")
            self.assertIn("if (options.selfCheckExit && top.io_halted)", text)
            self.assertIn("instruction retired while WFI sleep was asserted", text)
            self.assertIn("wfi-commits=", text)
            self.assertIn("wfi-sleep-cycles=", text)
            self.assertIn("wfi-shadow=", text)

    def test_makefile_runs_both_runtime_proofs_incrementally(self) -> None:
        text = (ROOT / "Makefile.freertos-difftest").read_text(encoding="utf-8")
        self.assertIn("FREERTOS_BASE_DIFFTEST_CPP", text)
        self.assertIn("FREERTOS_DIFFTEST_CPP", text)
        self.assertIn("DIFFTEST_GENERATED_MAIN", text)
        self.assertIn("DIFFTEST_SIM_BINARY", text)
        self.assertIn("tools/make_freertos_wfi_difftest_adapter.py", text)
        self.assertIn("tools/make_freertos_difftest_runner.py", text)
        self.assertIn("wfi-shadow=[1-9][0-9]*", text)
        self.assertIn("wfi-sleep-cycles=[1-9][0-9]*", text)
        self.assertIn("retired while WFI sleep was asserted", text)


if __name__ == "__main__":
    unittest.main()
