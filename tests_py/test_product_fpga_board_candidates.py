"""Purchase-gate contracts: exact package, pinned RTL, honest evidence labels."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_fpga_board_candidates.sh"
TCL = ROOT / "tools/fpga/product_7series_candidate.tcl"
LEGACY = ROOT / "tools/ci/product_fpga_artix7_p0.sh"
BOARDS = (
    "xc7k325tffg676-2",  # Bochenjingxin Kintex7-BaseC
    "xc7a200tfbg484-2",  # Puzhi PA200T-StarLite
    "xc7a200tsbg484-1",  # Digilent Nexys Video
    "xc7k325tffg900-2",  # Digilent Genesys 2 / AMD KC705
)


class BoardCandidateGateContract(unittest.TestCase):
    def invoke(self, *args, env=None):
        return subprocess.run(
            ["bash", str(SCRIPT), *map(str, args)], cwd=ROOT, env=env,
            capture_output=True, text=True, check=False,
        )

    @staticmethod
    def fixture(directory):
        directory = Path(directory)
        rtl_dir = directory / "rtl"
        rtl_dir.mkdir()
        sv = rtl_dir / "board.sv"
        sv.write_text("module AetherCoreProductFpgaUartDdrBoard; endmodule\n")
        hashes = directory / "rtl.sha256"
        hashes.write_text(f"{hashlib.sha256(sv.read_bytes()).hexdigest()}  board.sv\n")
        sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True,
        ).strip()
        (directory / "manifest.json").write_text(json.dumps({
            "status": "RTL_EXPORT_PASS", "git_sha": sha,
            "top": "AetherCoreProductFpgaUartDdrBoard",
            "emitter": "aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc",
            "geometry": "small", "isa": "rv64gc", "abi": "lp64d",
            "spi_sd_enabled": True, "clock_frequency_hz": 50_000_000,
            "vendor_ddr_phy_included": False,
            "rtl_file_hashes_sha256": hashlib.sha256(hashes.read_bytes()).hexdigest(),
        }))
        return sv

    def test_existing_pinned_artix_export_is_reused_not_rewritten(self):
        self.assertIn('exec bash "$export_gate" prepare', SCRIPT.read_text())
        self.assertIn('bash "$export_gate" prepare "$output_dir" >/dev/null', SCRIPT.read_text())
        self.assertIn('status --porcelain --untracked-files=all', SCRIPT.read_text())
        self.assertIn('src/main build.mill mill tools/ci tools/fpga', SCRIPT.read_text())
        with TemporaryDirectory() as temp:
            sv = self.fixture(temp)
            hit = self.invoke("prepare", temp)
            self.assertEqual(hit.returncode, 0, hit.stderr)
            self.assertIn("RTL_EXPORT_CACHE_HIT", hit.stdout)
            # Existing export verifier must reject an extra synthesis file.
            (sv.parent / "injected.sv").write_text("module injected; endmodule\n")
            rejected = self.invoke("synth", temp, BOARDS[0])
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("synthesized RTL file set differs", rejected.stderr)

    def test_exact_board_devices_only_and_no_implicit_prepare(self):
        with TemporaryDirectory() as temp:
            for part in BOARDS:
                unprepared = self.invoke("synth", temp, part)
                self.assertNotEqual(unprepared.returncode, 0)
                self.assertIn("RTL export missing", unprepared.stderr)
            for other in (
                "xc7a200tfbg676-2", "xc7a200tsbg484-2",
                "xc7k325tffg676-1", "xc7a75tfgg484-1",
            ):
                rejected = self.invoke("synth", temp, other)
                self.assertEqual(rejected.returncode, 2)
                self.assertIn("unreviewed candidate", rejected.stderr)
            self.assertFalse((Path(temp) / "manifest.json").exists())

    def test_real_vivado_required_before_any_probe_or_synthesis(self):
        with TemporaryDirectory() as temp:
            self.fixture(temp)
            # Disable Vivado without altering system dependencies used by verifier.
            env = os.environ.copy()
            with TemporaryDirectory() as fake_bin:
                # Force a stable absence regardless of the test runner PATH.
                env["PATH"] = fake_bin + ":/usr/bin:/bin"
                for mode in ("probe", "synth"):
                    result = self.invoke(mode, temp, BOARDS[0], env=env)
                    self.assertEqual(result.returncode, 3, result.stderr)
                    self.assertIn("NOT_RUN: Vivado unavailable", result.stderr)
            self.assertFalse((Path(temp) / "7series").exists())

    def test_board_validation_stays_blocked_without_real_physical_inputs(self):
        with TemporaryDirectory() as temp:
            for part in BOARDS:
                blocked = self.invoke("board-gate", temp, part)
                self.assertEqual(blocked.returncode, 4)
                self.assertIn("BOARD_PHYSICAL_INPUTS_MISSING", blocked.stderr)
                self.assertIn("route_design", blocked.stderr)
        source = SCRIPT.read_text()
        tcl = TCL.read_text()
        self.assertIn("get_parts -quiet $part", tcl)
        self.assertIn("synth_design -mode out_of_context", tcl)
        self.assertLess(tcl.index("read_xdc $reference_xdc"), tcl.index("synth_design -mode out_of_context"))
        self.assertIn("report_utilization", tcl)
        self.assertIn("report_timing_summary", tcl)
        self.assertIn("physical_timing_qualified:false", source)
        self.assertIn("routed:false", source)
        self.assertNotIn("FPGA_BOARD_PASS", source)
        self.assertNotIn("place_design", tcl)
        self.assertNotIn("route_design", tcl)
        self.assertTrue(LEGACY.is_file())


if __name__ == "__main__":
    unittest.main()
