from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools" / "run_until_uart.py"


class UartSignatureRunnerTest(unittest.TestCase):
    def invoke(self, program: str, success: str, timeout: float = 2.0):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "uart.log"
            result = subprocess.run(
                [
                    sys.executable,
                    str(RUNNER),
                    "--success",
                    success,
                    "--log",
                    str(log),
                    "--timeout",
                    str(timeout),
                    "--",
                    sys.executable,
                    "-u",
                    "-c",
                    program,
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=5,
            )
            return result, log.read_bytes()

    def test_accepts_signature_split_across_writes(self) -> None:
        result, log = self.invoke(
            "import sys,time; sys.stdout.write('nx'); sys.stdout.flush(); "
            "time.sleep(.05); sys.stdout.write('sh> '); sys.stdout.flush(); "
            "time.sleep(10)",
            "nsh> ",
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn(b"nsh> ", log)
        self.assertIn(b"PASS: observed UART signature", result.stdout)

    def test_fails_when_child_exits_without_signature(self) -> None:
        result, log = self.invoke("print('boot failed')", "nsh> ")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"boot failed", log)
        self.assertIn(b"before UART signature", result.stderr)

    def test_times_out_without_signature(self) -> None:
        result, _ = self.invoke("import time; time.sleep(10)", "nsh> ", 0.1)
        self.assertEqual(result.returncode, 124)
        self.assertIn(b"UART signature timeout", result.stderr)


if __name__ == "__main__":
    unittest.main()
