from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "sim" / "nemu_difftest_rv32_timer.cpp"
ADAPTER = ROOT / "tools" / "make_freertos_difftest_adapter.py"


class FreeRtosDifftestAdapterTest(unittest.TestCase):
    def run_adapter(self, source: Path, output: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(ADAPTER), str(source), str(output)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_real_timer_adapter_gains_ecall_mhartid_and_mtime_shadows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "nemu_difftest_rv32_freertos.cpp"
            result = self.run_adapter(SOURCE, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            text = output.read_text(encoding="utf-8")

            self.assertIn("constexpr std::uint32_t kEcall = 0x00000073U", text)
            self.assertIn("constexpr std::uint32_t kMhartid = 0xf14U", text)
            self.assertIn("constexpr std::uint32_t kMachineEcallCause = 11U", text)
            self.assertIn("constexpr std::uint32_t kLoadOpcode = 0x03U", text)
            self.assertIn("constexpr std::uint32_t kLwFunct3 = 0x02U", text)
            self.assertIn("after = executeEcallTrap(before, commit)", text)
            self.assertIn("mtimeLoadStep = synchronizeMtimeLoad(commit)", text)
            self.assertIn("reference=machine-ecall-shadow", text)
            self.assertIn("reference=mtime-load-shadow", text)
            self.assertIn("case kMhartid: return 0U", text)
            self.assertIn("case kMhartid:\n        return false", text)
            self.assertIn("machine_.mepc = before.pc", text)
            self.assertIn("machine_.mcause = kMachineEcallCause", text)
            self.assertIn("machine_.mtval = 0U", text)
            self.assertIn("after.pc = machine_.mtvec", text)
            self.assertIn("return trapShadowSteps_", text)
            self.assertIn("std::uint64_t trapShadowSteps_ = 0", text)

            self.assertIn(
                "address != kMtimeAddress && address != kMtimeAddress + 4U",
                text,
            )
            self.assertIn(
                "opcode != kLoadOpcode || funct3 != kLwFunct3",
                text,
            )
            self.assertIn(
                "std::memcpy(mapped, &value, sizeof(value))",
                text,
            )
            self.assertIn(
                "FreeRTOS mtime shadow received an invalid architectural load event",
                text,
            )
            self.assertNotIn(
                "timer workload reported an unexpected synchronous exception",
                text,
            )

            self.assertEqual(text.count("executeEcallTrap("), 2)
            self.assertEqual(text.count("synchronizeMtimeLoad("), 2)
            self.assertEqual(text.count("case kMhartid:"), 3)
            self.assertEqual(text.count("kMachineEcallCause"), 3)

    def test_adapter_rejects_source_shape_drift_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "timer.cpp"
            output = root / "freertos.cpp"
            text = SOURCE.read_text(encoding="utf-8")
            source.write_text(
                text.replace(
                    "constexpr std::uint32_t kMret = 0x30200073U;",
                    "constexpr std::uint32_t kMretChanged = 0x30200073U;",
                    1,
                ),
                encoding="utf-8",
            )
            result = self.run_adapter(source, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SYSTEM instruction", result.stderr)
            self.assertFalse(output.exists())

    def test_adapter_rejects_missing_mtime_anchor_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "timer.cpp"
            output = root / "freertos.cpp"
            text = SOURCE.read_text(encoding="utf-8")
            source.write_text(
                text.replace(
                    "constexpr std::uint32_t kMtimeAddress = 0x0200bff8U;",
                    "constexpr std::uint32_t kMtimeAddressChanged = 0x0200bff8U;",
                    1,
                ),
                encoding="utf-8",
            )
            result = self.run_adapter(source, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("mtime load instruction constants", result.stderr)
            self.assertFalse(output.exists())

    def test_adapter_script_compiles_as_python(self) -> None:
        result = subprocess.run(
            [sys.executable, "-m", "py_compile", str(ADAPTER)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
