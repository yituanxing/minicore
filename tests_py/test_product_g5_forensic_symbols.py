from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SYMBOLIZE = ROOT / "tools/ci/product_g5_forensic_symbols.py"
WORKFLOW = ROOT / ".github/workflows/product-g5-debian-rv64gc.yml"


class ProductG5ForensicSymbolsTest(unittest.TestCase):
    def test_exact_kernel_symbols_and_interrupt_boundary_ranking(self):
        with tempfile.TemporaryDirectory(prefix="g5-symbols-", dir=ROOT.parent) as directory:
            temporary = Path(directory)
            trace = temporary / "diagnostic.log"
            system_map = temporary / "System.map"
            vmlinux = temporary / "vmlinux"
            system_map.write_text(
                "ffffffff80000000 T _stext\n"
                "ffffffff80001100 T wait_for_initramfs\n"
                "ffffffff80001300 T kthread\n"
            )
            vmlinux.write_bytes(b"exact-test-kernel")
            trace.write_text(
                "PRODUCT_G5_RESUME_DIAGNOSTIC_PROGRESS cycles=1000000000\n"
                "PRODUCT_G5_RESUME_DIAGNOSTIC_RESULT status=14 cycles=1300000000 "
                "last-exception-pc=0xffffffff80001104 current-commit-pc=0x80000000 "
                "recent-commit-pcs=0xffffffff80001308,0x80000200 "
                "timer-boundary-pc-top=0xffffffff80001108:154,0x80000100:23 snapshot=x\n"
            )
            result = subprocess.run(
                [sys.executable, str(SYMBOLIZE), "--trace", str(trace),
                 "--system-map", str(system_map), "--vmlinux", str(vmlinux)],
                text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("wait_for_initramfs+0x8", result.stdout)
            self.assertIn("interrupted-pc pc=0xffffffff80001108 samples=154", result.stdout)
            self.assertIn("kthread+0x8", result.stdout)
            self.assertIn("UNMAPPED_NOT_PROVEN_LINUX", result.stdout)
            self.assertIn("vmlinux_sha256=", result.stdout)

    def test_refuses_missing_finalized_checkpoint_trace(self):
        with tempfile.TemporaryDirectory(prefix="g5-symbols-", dir=ROOT.parent) as directory:
            temporary = Path(directory)
            trace = temporary / "diagnostic.log"
            system_map = temporary / "System.map"
            vmlinux = temporary / "vmlinux"
            trace.write_text("PRODUCT_G5_RESUME_DIAGNOSTIC_PROGRESS cycles=900000000\n")
            system_map.write_text("ffffffff80000000 T _stext\n")
            vmlinux.write_bytes(b"kernel")
            result = subprocess.run(
                [sys.executable, str(SYMBOLIZE), "--trace", str(trace),
                 "--system-map", str(system_map), "--vmlinux", str(vmlinux)],
                text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("no finalized G5 diagnostic result", result.stderr)

    def test_manual_forensic_path_never_executes_30m_speed_ab(self):
        text = WORKFLOW.read_text()
        self.assertIn("AETHERCORE_G5_FORENSIC", text)
        self.assertIn("AETHERCORE_ALLOW_PRODUCT_G5_LONG_DIAGNOSE: '1'", text)
        self.assertIn("env.AETHERCORE_G5_FORENSIC != '1'", text)
        self.assertIn("bash tools/ci/product_checkpoint_g5_short.sh diagnose", text)
        self.assertIn("build/product-g5-long-forensic/**", text)


if __name__ == "__main__":
    unittest.main()
