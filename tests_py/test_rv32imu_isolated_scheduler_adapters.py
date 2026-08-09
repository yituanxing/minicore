import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"


class IsolatedSchedulerAdapterCompositionTest(unittest.TestCase):
    def run_tool(self, *arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, *(str(argument) for argument in arguments)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def generate_pmp_runner(self, directory: Path) -> Path:
        output = directory / "sim_main_rv32imu_pmp.cpp"
        result = self.run_tool(
            TOOLS / "make_rv32imu_pmp_runner.py",
            ROOT / "sim" / "sim_main.cpp",
            output,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(output.is_file())
        return output

    def generate_pmp_difftest(self, directory: Path) -> Path:
        rv32imu = directory / "nemu_difftest_rv32imu.cpp"
        pmp = directory / "nemu_difftest_rv32imu_pmp.cpp"

        result = self.run_tool(
            TOOLS / "make_rv32imu_difftest.py",
            ROOT / "sim" / "nemu_difftest_rv32.cpp",
            rv32imu,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

        result = self.run_tool(
            TOOLS / "widen_rv32imu_pmp_csr_decode.py",
            rv32imu,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

        result = self.run_tool(
            TOOLS / "make_rv32imu_pmp_difftest.py",
            rv32imu,
            pmp,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(pmp.is_file())
        return pmp

    def test_runner_composition_exports_interrupt_commit_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            pmp_runner = self.generate_pmp_runner(directory)
            output = directory / "sim_main_rv32imu_isolated_scheduler.cpp"

            result = self.run_tool(
                TOOLS / "make_rv32imu_isolated_scheduler_adapters.py",
                "runner",
                pmp_runner,
                output,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            generated = output.read_text(encoding="utf-8")
            self.assertIn("VAetherCoreRV32IMUPmpSimTop", generated)
            self.assertIn("commit.interrupt = top.io_commit_interrupt;", generated)
            self.assertIn("commit.interruptCause", generated)
            self.assertIn("commit.interruptPc", generated)
            self.assertIn("interrupt-shadow=", generated)
            self.assertIn("difftest->interruptShadowSteps()", generated)

    def test_difftest_composition_contains_timer_and_pmp_models(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            pmp_difftest = self.generate_pmp_difftest(directory)
            output = directory / "nemu_difftest_rv32imu_isolated_scheduler.cpp"

            result = self.run_tool(
                TOOLS / "make_rv32imu_isolated_scheduler_adapters.py",
                "difftest",
                pmp_difftest,
                output,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            generated = output.read_text(encoding="utf-8")
            for required_fragment in (
                "kMtimecmpAddress",
                "kMachineTimerCause",
                "timerPending",
                "validateInterrupt",
                "executeInterrupt",
                "observeTimerStore",
                "mappedPointer",
                "kPmpcfg0",
                "pmpAllows",
                "currentPrivilege",
                "interruptShadowSteps",
            ):
                with self.subTest(required_fragment=required_fragment):
                    self.assertIn(required_fragment, generated)

            self.assertEqual(generated.count("constexpr std::uint32_t kMachineTimerCause"), 1)
            self.assertEqual(generated.count("void validateInterrupt("), 1)
            self.assertEqual(generated.count("bool pmpAllows("), 1)

    def test_runner_composition_rejects_source_shape_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            pmp_runner = self.generate_pmp_runner(directory)
            source = pmp_runner.read_text(encoding="utf-8")
            anchor = (
                "  commit.exceptionValue = "
                "static_cast<std::uint64_t>(top.io_commit_exceptionValue);\n"
            )
            self.assertIn(anchor, source)
            pmp_runner.write_text(
                source.replace(anchor, "  // intentionally drifted exception metadata\n", 1),
                encoding="utf-8",
            )
            output = directory / "should-not-exist.cpp"

            result = self.run_tool(
                TOOLS / "make_rv32imu_isolated_scheduler_adapters.py",
                "runner",
                pmp_runner,
                output,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("combined trap/interrupt metadata", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
