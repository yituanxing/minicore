"""Offline fail-closed contracts for the real, not-yet-run Vivado route gate."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENTRY = ROOT / "tools/ci/product_fpga_nexys_video_ddr.sh"
LOGICAL = ROOT / "tools/fpga/nexys_video_product_bd.tcl"
PHYSICAL = ROOT / "tools/fpga/nexys_video_product_physical.tcl"
REPORT_GATE = ROOT / "tools/fpga/nexys_video_physical_report_gate.py"


class NexysPhysicalGateContract(unittest.TestCase):
    def test_logical_gate_remains_separate_and_unrouted(self):
        logical = LOGICAL.read_text(encoding="utf-8")
        physical = PHYSICAL.read_text(encoding="utf-8")
        driver = ENTRY.read_text(encoding="utf-8")
        self.assertIn("PRODUCT_NEXYS_LOGICAL_BD_ONLY", logical)
        self.assertNotIn("route_design", logical)
        self.assertIn("physical-product", driver)
        self.assertIn("nexys_video_product_physical.tcl", driver)
        self.assertIn("nexys_video_physical_report_gate.py", driver)
        self.assertIn("NEXYS_ROUTED_TIMING_ONLY", physical)
        self.assertNotIn("FPGA_BOARD_PASS", physical)
        self.assertNotIn("DEBIAN_LOGIN_PASS", physical)

    def test_real_project_flow_and_target_are_pinned(self):
        tcl = PHYSICAL.read_text(encoding="utf-8")
        self.assertIn("2026.1", tcl)
        self.assertIn("version -short", tcl)
        self.assertIn("xc7a200tsbg484-1", tcl)
        self.assertIn("get_board_parts", tcl)
        self.assertIn("open_project", tcl)
        stages = (
            "generate_target all", "make_wrapper -files", "set_property top",
            "launch_runs synth_1", "wait_on_run synth_1", "open_run synth_1",
            "launch_runs impl_1 -to_step route_design", "wait_on_run impl_1",
            "open_run impl_1", "report_timing_summary -report_unconstrained",
            "report_drc", "get_timing_paths", "get_drc_violations",
            "route-evidence.txt",
        )
        for stage in stages:
            self.assertIn(stage, tcl, stage)
        indices = [tcl.index(stage) for stage in stages[:9]]
        self.assertEqual(indices, sorted(indices), "Vivado project stages were reordered")
        self.assertIn("$setup_slack < 0 || $hold_slack < 0", tcl)
        self.assertIn("if {[llength $drc_violations] != 0}", tcl)
        self.assertNotIn("write_bitstream", tcl)

    def test_tcl_blocks_are_syntactically_complete(self):
        if not shutil.which("tclsh"):
            self.skipTest("tclsh unavailable")
        with tempfile.TemporaryDirectory() as temp:
            checker = Path(temp) / "check.tcl"
            checker.write_text(
                f"set fd [open {{{PHYSICAL}}} r]\n"
                "set source [read $fd]\nclose $fd\n"
                "if {![info complete $source]} {error {incomplete Tcl}}\n"
                "puts {TCL_SYNTAX_COMPLETE}\n",
                encoding="utf-8",
            )
            result = subprocess.run(["tclsh", str(checker)], capture_output=True,
                                    text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("TCL_SYNTAX_COMPLETE", result.stdout)

    def test_missing_reference_or_product_export_is_hard_blocked(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "unused-result"
            result = subprocess.run(
                ["bash", str(ENTRY), "physical-product", str(Path(temp) / "no-ref"),
                 str(Path(temp) / "no-rtl"), str(output)],
                capture_output=True, text=True, cwd=ROOT, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
            self.assertNotIn("NEXYS_ROUTED_TIMING_ONLY:", result.stdout)

    def test_negative_slack_or_fake_version_never_creates_result(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            evidence = root / "physical_reports"
            evidence.mkdir()
            (root / "LOGICAL_BD_ONLY.txt").write_text("logical only\n")
            (root / "aether_nexys_product.xpr").write_text("synthetic test\n")
            base = (
                "NEXYS_ROUTED_TIMING_ONLY\n"
                "part=xc7a200tsbg484-1\n"
                "vivado_version=2026.1\n"
                "setup_wns_ns=-0.125\n"
                "hold_whs_ns=0.22\n"
                "drc_violations=0\n"
                "bitstream_generated=false\n"
                "ddr_board_calibrated=false\n"
                "fpga_debian_login=false\n"
            )
            marker = evidence / "route-evidence.txt"
            for invalid in (base, base.replace("2026.1", "2025.2"),
                            base.replace("setup_wns_ns=-0.125", "setup_wns_ns=nan"),
                            base.replace("fpga_debian_login=false", "fpga_debian_login=true")):
                marker.write_text(invalid)
                result = subprocess.run(
                    ["python3", str(REPORT_GATE), "--output-dir", str(root),
                     "--repository", str(ROOT), "--product-export-dir", str(root)],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, 4, result.stderr)
                self.assertIn("NEXYS_PHYSICAL_REPORT_REJECTED", result.stderr)
                self.assertFalse((evidence / "result.json").exists())

    def test_missing_real_reports_and_sha_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            evidence = root / "physical_reports"
            evidence.mkdir()
            (root / "LOGICAL_BD_ONLY.txt").write_text("logical only\n")
            (root / "aether_nexys_product.xpr").write_text("synthetic test\n")
            (evidence / "route-evidence.txt").write_text(
                "NEXYS_ROUTED_TIMING_ONLY\npart=xc7a200tsbg484-1\n"
                "vivado_version=2026.1\nsetup_wns_ns=0.5\nhold_whs_ns=0.5\n"
                "drc_violations=0\nbitstream_generated=false\n"
                "ddr_board_calibrated=false\nfpga_debian_login=false\n")
            result = subprocess.run(
                ["python3", str(REPORT_GATE), "--output-dir", str(root),
                 "--repository", str(ROOT), "--product-export-dir", str(root)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 4)
            self.assertIn("report/checkpoint absent", result.stderr)
            self.assertFalse((evidence / "result.json").exists())


if __name__ == "__main__":
    unittest.main()
