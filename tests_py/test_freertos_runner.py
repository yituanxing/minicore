from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "sim" / "sim_main.cpp"
TOOL = ROOT / "tools" / "make_freertos_runner.py"


class FreeRtosRunnerTest(unittest.TestCase):
    def generate(self, trace: int) -> str:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "runner.cpp"
            result = subprocess.run(
                [sys.executable, str(TOOL), str(SOURCE), str(output), "--trace", str(trace)],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return output.read_text(encoding="utf-8")

    def assert_wfi_wait_semantics(self, text: str) -> None:
        self.assertIn("options.selfCheckExit || !top.io_halted", text)
        self.assertIn("if (cycles >= options.maxCycles && !exitRequested)", text)
        self.assertNotIn(
            "cycles < options.maxCycles && !top.io_halted && !exitRequested",
            text,
        )
        self.assertNotIn("!top.io_halted && cycles >= options.maxCycles", text)

    def test_fast_runner_removes_all_vcd_link_dependencies(self) -> None:
        text = self.generate(0)
        self.assertIn("VAetherCoreRV32IMTrapSimTop", text)
        self.assertNotIn("verilated_vcd_c.h", text)
        self.assertNotIn("VerilatedVcdC", text)
        self.assertNotIn("wave->dump", text)
        self.assertNotIn("top.trace", text)
        self.assertIn("--trace is unavailable in this fast runner", text)
        self.assertIn("commit.interruptPc", text)
        self.assert_wfi_wait_semantics(text)

    def test_full_runner_retains_vcd_support(self) -> None:
        text = self.generate(1)
        self.assertIn("VAetherCoreRV32IMTrapSimTop", text)
        self.assertIn("verilated_vcd_c.h", text)
        self.assertIn("VerilatedVcdC", text)
        self.assertIn("wave->dump", text)
        self.assertIn("top.trace", text)
        self.assertIn("commit.interruptPc", text)
        self.assert_wfi_wait_semantics(text)


if __name__ == "__main__":
    unittest.main()
