import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "widen_rv32imu_pmp_csr_decode.py"


class PmpCsrDecodeWideningTest(unittest.TestCase):
    def run_script(self, text: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.cpp"
            path.write_text(text, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(path)],
                text=True,
                capture_output=True,
                check=False,
            )
            return result, path.read_text(encoding="utf-8")

    def test_accepts_three_full_address_switches_without_rewriting(self) -> None:
        source = "\n".join(["switch (address) {", "}"] * 3) + "\n"
        result, output = self.run_script(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, source)
        self.assertIn("validated 3 full-address", result.stdout)

    def test_widens_three_low_byte_switches(self) -> None:
        source = "\n".join(["switch (address & 0xffU) {", "}"] * 3) + "\n"
        result, output = self.run_script(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("address & 0xffU", output)
        self.assertEqual(output.count("switch (address & 0xfffU) {"), 3)
        self.assertIn("widened 3 PMP CSR decode", result.stdout)

    def test_accepts_three_already_widened_switches(self) -> None:
        source = "\n".join(["switch (address & 0xfffU) {", "}"] * 3) + "\n"
        result, output = self.run_script(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, source)
        self.assertIn("validated 3 already-widened", result.stdout)

    def test_rejects_mixed_or_partial_decode_shapes(self) -> None:
        source = (
            "switch (address) {\n}\n"
            "switch (address & 0xffU) {\n}\n"
            "switch (address & 0xfffU) {\n}\n"
        )
        result, output = self.run_script(source)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, source)
        self.assertIn("unexpected PMP CSR decode shape", result.stderr)


if __name__ == "__main__":
    unittest.main()
