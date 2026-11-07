"""Fail-closed source and CLI contracts for Product Artix-7 P0 evidence."""

import hashlib
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_fpga_artix7_p0.sh"
TCL = ROOT / "tools/fpga/artix7_product_synth.tcl"
XDC = ROOT / "tools/fpga/artix7_product_ooc.xdc"
EMITTER = ROOT / "src/main/scala/aethercore/soc/ElaborateProductSoC.scala"
WORKFLOW = ROOT / ".github/workflows/product-fpga-artix7-p0.yml"


class ProductFpgaArtixP0Contract(unittest.TestCase):
    def invoke(self, *args):
        return subprocess.run(
            ["bash", str(SCRIPT), *map(str, args)],
            capture_output=True, text=True, check=False,
        )

    def test_uses_full_product_small_rv64gc_sd1_board_neutral_top(self):
        script = SCRIPT.read_text()
        emitter = EMITTER.read_text()
        self.assertIn("object ElaborateProductFpgaUartDdrSpiSd1Rv64gc extends App", emitter)
        self.assertIn("smallOpenSbiDistroTarget(32, geometry)", emitter)
        self.assertIn("boardDevices = ProductSoCBoardProfiles.persistentSpiSd", emitter)
        self.assertIn("emitter=aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc", script)
        self.assertIn("top=AetherCoreProductFpgaUartDdrBoard", script)
        self.assertIn('"$emitter" small --target-dir', script)
        self.assertIn('isa:"rv64gc", abi:"lp64d"', script)
        self.assertIn("spi_sd_enabled:true", script)
        self.assertIn("clock_frequency_hz:50000000", script)
        # GitHub hosted images may not have ripgrep; use the guaranteed grep.
        self.assertIn('command -v grep', script)
        self.assertIn('grep -Eq "module[[:space:]]+', script)
        self.assertNotIn('rg -q', script)

    def test_same_export_is_reused_for_both_fpga_candidates(self):
        script = SCRIPT.read_text()
        self.assertIn("xc7a100tfgg676-2|xc7a200tfbg676-2", script)
        self.assertIn("xc7a200tsbg484-1", script)
        with TemporaryDirectory() as candidate_temp:
            target = self.invoke("synth", candidate_temp, "xc7a200tsbg484-1")
            self.assertIn("incomplete RTL export", target.stderr)
            self.assertNotIn("unsupported or unreviewed", target.stderr)
        self.assertIn("RTL_EXPORT_CACHE_HIT", script)
        self.assertIn("sha256sum --status --check", script)
        self.assertIn('cmp -s "$hashes" -', script)
        self.assertIn("rtl_file_hashes_sha256", script)
        self.assertIn("git_sha", script)
        with TemporaryDirectory() as temp:
            invalid = self.invoke("synth", temp, "xc7a75tfgg484-1")
            self.assertNotEqual(invalid.returncode, 0)
            self.assertIn("unsupported or unreviewed", invalid.stderr)
            incomplete = self.invoke("synth", temp, "xc7a100tfgg676-2")
            self.assertNotEqual(incomplete.returncode, 0)
            self.assertIn("incomplete RTL export", incomplete.stderr)

    def test_complete_export_cache_is_verified_not_regenerated(self):
        sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True,
        ).strip()
        with TemporaryDirectory() as temp:
            output = Path(temp)
            rtl = output / "rtl"
            rtl.mkdir()
            source = rtl / "ProductTop.sv"
            source.write_text("module AetherCoreProductFpgaUartDdrBoard; endmodule\n")
            file_sha = hashlib.sha256(source.read_bytes()).hexdigest()
            hashes = output / "rtl.sha256"
            hashes.write_text(f"{file_sha}  {source.name}\n")
            bundle_sha = hashlib.sha256(hashes.read_bytes()).hexdigest()
            (output / "manifest.json").write_text(json.dumps({
                "status": "RTL_EXPORT_PASS", "git_sha": sha,
                "top": "AetherCoreProductFpgaUartDdrBoard",
                "emitter": "aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc",
                "geometry": "small", "isa": "rv64gc", "abi": "lp64d",
                "spi_sd_enabled": True, "clock_frequency_hz": 50_000_000,
                "vendor_ddr_phy_included": False,
                "rtl_file_hashes_sha256": bundle_sha,
            }))
            hit = self.invoke("prepare", output)
            self.assertEqual(hit.returncode, 0, hit.stderr)
            self.assertIn("RTL_EXPORT_CACHE_HIT", hit.stdout)
            unlisted = rtl / "Injected.sv"
            unlisted.write_text("module Injected; endmodule\n")
            injected = self.invoke("prepare", output)
            self.assertNotEqual(injected.returncode, 0)
            self.assertIn("synthesized RTL file set differs", injected.stderr)
            unlisted.unlink()
            source.write_text("module AetherCoreProductFpgaUartDdrBoard; wire x; endmodule\n")
            stale = self.invoke("prepare", output)
            self.assertNotEqual(stale.returncode, 0)
            self.assertIn("emitted RTL bytes differ", stale.stderr)

    def test_only_real_vivado_can_produce_synthesis_evidence(self):
        script = SCRIPT.read_text()
        tcl = TCL.read_text()
        xdc = XDC.read_text()
        self.assertIn("command -v vivado", script)
        self.assertIn("ARTIX7_SYNTH_NOT_RUN", script)
        self.assertIn("ARTIX7_LOGIC_SYNTH_ONLY", script)
        self.assertIn("vendor_ddr_phy_included:false", script)
        self.assertIn("physical_timing_qualified:false", script)
        self.assertIn("synth_design -mode out_of_context", tcl)
        self.assertLess(tcl.index("read_xdc $reference_xdc"), tcl.index("synth_design -mode out_of_context"))
        self.assertIn("-period 20.000 [get_ports clock]", xdc)
        self.assertIn("get_clocks -quiet p0_product_ui_clock", tcl)
        self.assertIn('period_ns:20, clock_frequency_hz:50000000', script)
        self.assertIn("report_utilization", tcl)
        self.assertIn("report_timing_summary", tcl)
        self.assertIn("write_checkpoint", tcl)
        self.assertNotIn("place_design", tcl)
        self.assertNotIn("route_design", tcl)
        self.assertNotIn("FPGA_BOARD_PASS", script)

    def test_ci_is_explicit_only_and_preserves_output(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("types: [labeled]", workflow)
        self.assertIn("github.event.label.name == 'product-fpga-artix7-p0'", workflow)
        self.assertIn("product_fpga_artix7_p0.sh prepare", workflow)
        self.assertIn("actions/cache/restore@v4", workflow)
        self.assertIn("actions/cache/save@v4", workflow)
        self.assertIn("product-artix7-p0-${{ runner.os }}-${{ runner.arch }}-${{ github.event.pull_request.head.sha || github.sha }}-v2", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertNotIn("synth_ecp5", workflow)
        self.assertNotIn("vivado -mode", workflow)
        self.assertNotIn("push:", workflow)


if __name__ == "__main__":
    unittest.main()
