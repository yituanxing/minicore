"""Offline contract for WSL invoking native Windows Vivado (not a routed PASS)."""

from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENTRY = ROOT / "tools/ci/product_fpga_nexys_video_ddr.sh"
WINDOWS = ROOT / "tools/ci/nexys_video_windows_vivado.ps1"


class NexysWindowsVivadoBridgeContract(unittest.TestCase):
    def test_entry_remains_valid_bash_and_mode_is_explicit(self):
        check = subprocess.run(["bash", "-n", str(ENTRY)], capture_output=True,
                               text=True, check=False)
        self.assertEqual(check.returncode, 0, check.stderr)
        source = ENTRY.read_text(encoding="utf-8")
        self.assertIn("physical-product-windows", source)
        self.assertIn("[[ $# -eq 4 || $# -eq 5 ]]", source)
        self.assertIn("physical-product", source)
        self.assertIn("command -v vivado", source)

    def test_missing_inputs_never_start_vivado_or_create_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "absent-run"
            result = subprocess.run(
                ["bash", str(ENTRY), "physical-product-windows",
                 str(Path(tmp) / "missing-reference"),
                 str(Path(tmp) / "missing-export"), str(output)],
                capture_output=True, text=True, check=False, cwd=ROOT,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            self.assertNotIn("NEXYS_ROUTED_TIMING_ONLY:", result.stdout)
            self.assertFalse((output / "physical_reports/result.json").exists())

    def test_existing_output_is_rejected_without_overwriting(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "saved-failed-run"
            output.mkdir()
            sentinel = output / "original.log"
            sentinel.write_text("keep previous evidence\n", encoding="utf-8")
            result = subprocess.run(
                ["bash", str(ENTRY), "physical-product-windows",
                 str(Path(tmp) / "missing-reference"),
                 str(Path(tmp) / "missing-export"), str(output)],
                capture_output=True, text=True, check=False, cwd=ROOT,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("output already exists", result.stderr)
            self.assertEqual(sentinel.read_text(), "keep previous evidence\n")

    def test_windows_paths_must_be_drive_backed_and_converted(self):
        source = ENTRY.read_text(encoding="utf-8")
        self.assertIn("powershell.exe", source)
        self.assertIn("wslpath -w", source)
        self.assertIn("^/mnt/[a-zA-Z]/.+", source)
        self.assertIn("win_repo=", source)
        self.assertIn("win_reference=", source)
        self.assertIn("win_rtl=", source)
        self.assertIn("win_output=", source)
        self.assertIn("win_script=", source)
        self.assertIn('[[ -d "$(dirname "$output")" ]]', source)
        self.assertIn("NEXYS_WINDOWS_NOT_READY", source)

    def test_windows_wrapper_runs_two_fail_closed_separate_vivado_processes(self):
        source = WINDOWS.read_text(encoding="utf-8")
        self.assertIn("ValidateSet('Logical', 'Physical')", source)
        self.assertIn("Get-Command git.exe", source)
        self.assertIn("Get-Command vivado.bat", source)
        self.assertIn("-PathType Leaf", source)
        self.assertIn("Set-Location -LiteralPath $Repository", source)
        for variable in ("NEXYS_REFERENCE_DIR", "NEXYS_PRODUCT_RTL_DIR",
                         "NEXYS_OUTPUT_DIR"):
            self.assertIn(f"$env:{variable}", source)
        self.assertIn("nexys_video_product_bd.tcl", source)
        self.assertIn("nexys_video_product_physical.tcl", source)
        self.assertIn("$LASTEXITCODE -ne 0", source)
        self.assertIn("exit 1", source)
        self.assertNotIn("FPGA_BOARD_PASS", source)
        self.assertNotIn("DEBIAN_LOGIN_PASS", source)

    def test_no_fake_pass_on_missing_or_failed_windows_route(self):
        source = ENTRY.read_text(encoding="utf-8")
        self.assertIn('"$output/physical_reports/route-evidence.txt"', source)
        self.assertIn("nexys_video_physical_report_gate.py", source)
        self.assertIn("physical-vivado.log", source)
        self.assertIn("logical-vivado.log", source)
        self.assertIn('"$reference_root" >/dev/null', source)
        self.assertIn('prepare "$product_export_dir" >/dev/null', source)
        self.assertIn("--rtl-dir \"$product_rtl_dir\"", source)
        self.assertIn("set -euo pipefail", source)
        self.assertNotIn("FPGA_BOARD_PASS", source)
        self.assertNotIn("DEBIAN_LOGIN_PASS", source)


if __name__ == "__main__":
    unittest.main()
