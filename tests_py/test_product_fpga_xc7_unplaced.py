"""Bounded XC7 mapper contracts; fixture results are never FPGA evidence."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest

from tools.ci.product_fpga_xc7_hierarchy import MappingReportError, parse_yosys_hierarchy


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_fpga_xc7_unplaced.sh"
HIERARCHY = ROOT / "tools/ci/product_fpga_xc7_hierarchy.py"
WORKFLOW = ROOT / ".github/workflows/product-fpga-xc7-mapping.yml"


class ProductFpgaXc7UnplacedContract(unittest.TestCase):
    def test_ci_is_explicit_and_requires_existing_exact_sha_cache(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("types: [labeled]", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("github.event.label.name == 'product-fpga-xc7-map'", workflow)
        self.assertIn("actions/cache/restore@v4", workflow)
        self.assertIn("steps.rtl_cache.outputs.cache-hit", workflow)
        self.assertIn("product-artix7-p0-${{ runner.os }}-${{ runner.arch }}-${{ github.event.pull_request.head.sha || github.sha }}-v2", workflow)
        self.assertIn("product_fpga_artix7_p0.sh prepare", workflow)
        self.assertNotIn("actions/cache/save@v4", workflow)
        self.assertNotIn("sbt ", workflow)
        self.assertNotIn("push:", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)

    def test_mapper_has_one_real_full_top_and_bounded_guards(self):
        script = SCRIPT.read_text()
        self.assertIn("top=AetherCoreProductFpgaUartDdrBoard", script)
        self.assertIn("read_verilog -sv -DSYNTHESIS", script)
        self.assertIn("tools/fpga/aether_product_nexys_axi_bridge.sv", script)
        self.assertIn("hierarchy -check -top aether_product_nexys_axi_bridge", script)
        self.assertIn("nexys_axi_wrapper_hierarchy_checked:true", script)
        self.assertIn("hierarchy -check -top $top", script)
        self.assertIn("synth_xilinx -family xc7 -noiopad -top $top", script)
        self.assertIn("stat -json -tech xilinx", script)
        self.assertIn("tee -o $stats stat -json -tech xilinx", script)
        self.assertNotIn('tee -o \\"$stats\\" stat', script)
        self.assertIn("product_fpga_xc7_hierarchy.py", script)
        self.assertIn("find \"$rtl_dir\" -maxdepth 1", script)
        self.assertIn("RTL_EXPORT_PASS", script)
        self.assertIn("git_sha", script)
        self.assertIn("CGROUP_GUARD_84_PERCENT", script)
        self.assertIn("RSS_GUARD_12_GIB", script)
        self.assertIn("TIME_GUARD_420_SECONDS", script)
        self.assertIn("XC7_UNPLACED_MAPPING_FAILED", script)
        self.assertIn("XC7_UNPLACED_MAPPING_PASS", script)
        self.assertIn("device_part:null,placed:false,routed:false", script)
        self.assertNotIn("write_json", script)
        self.assertNotIn("FPGA_BOARD_PASS", script)

    def test_mocked_mapper_fails_closed_then_writes_qualified_statistics(self):
        """Test control flow with a *fake* yosys; only CI real Yosys is evidence."""
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        with TemporaryDirectory() as temp:
            base = Path(temp)
            exported = base / "export"
            rtl = exported / "rtl"
            rtl.mkdir(parents=True)
            source = rtl / "Core.sv"
            source.write_text("module AetherCoreProductFpgaUartDdrBoard; endmodule\n")
            file_sha = hashlib.sha256(source.read_bytes()).hexdigest()
            hashes = exported / "rtl.sha256"
            hashes.write_text(f"{file_sha}  Core.sv\n")
            (exported / "manifest.json").write_text(json.dumps({
                "status": "RTL_EXPORT_PASS", "git_sha": sha,
                "top": "AetherCoreProductFpgaUartDdrBoard",
                "emitter": "aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc",
                "isa": "rv64gc", "abi": "lp64d", "geometry": "small",
                "spi_sd_enabled": True, "rtl_files": 1,
                "clock_frequency_hz": 50_000_000,
                "vendor_ddr_phy_included": False,
            }))
            fakebin = base / "bin"
            fakebin.mkdir()
            fake = fakebin / "yosys"
            fake.write_text("#!/usr/bin/env bash\n"
                            "if [[ \"${1:-}\" == '-V' ]]; then echo 'MOCK Yosys'; exit 0; fi\n"
                            "if [[ -n \"${FAKE_STATS_PATH:-}\" ]]; then\n"
                            "  printf '%s' '{\"modules\":{\"\\\\AetherCoreProductFpgaUartDdrBoard\":{\"num_cells\":42}}}' > \"$FAKE_STATS_PATH\"\n"
                            "fi\n")
            fake.chmod(0o755)
            output = base / "out"
            env = dict(os.environ, PATH=f"{fakebin}:{os.environ['PATH']}")
            first = subprocess.run(["bash", str(SCRIPT), str(exported), str(output)],
                                   env=env, text=True, capture_output=True, check=False)
            self.assertEqual(first.returncode, 1, first.stdout + first.stderr)
            failed = json.loads((output / "result.json").read_text())
            self.assertEqual(failed["status"], "XC7_UNPLACED_MAPPING_FAILED")
            self.assertEqual(failed["reason"], "MISSING_VALID_STAT_JSON")
            env["FAKE_STATS_PATH"] = str(base / "good" / "stats.json")
            second = subprocess.run(["bash", str(SCRIPT), str(exported), str(base / "good")],
                                    env=env, text=True, capture_output=True, check=False)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            accepted = json.loads((base / "good" / "result.json").read_text())
            self.assertEqual(accepted["status"], "XC7_UNPLACED_MAPPING_PASS")
            self.assertIsNone(accepted["device_part"])
            self.assertFalse(accepted["placed"])
            self.assertFalse(accepted["routed"])
            self.assertFalse(accepted["physical_timing_qualified"])
            self.assertTrue(accepted["nexys_axi_wrapper_hierarchy_checked"])
            self.assertEqual(accepted["rtl_files"], 1)

    def test_hierarchy_parser_weights_instances_and_rejects_false_totals(self):
        fixture = """124.48. Printing statistics.

=== Child ===

   Number of cells:                 2
     FDRE                            1
     LUT6                            1

   Estimated number of LCs:          1

=== AetherCoreProductFpgaUartDdrBoard ===

   Number of cells:                 2
     Child                           2

   Estimated number of LCs:          0

=== design hierarchy ===

   AetherCoreProductFpgaUartDdrBoard      1
     Child                         2

   Number of wires:                20
   Number of cells:                 4
     FDRE                            2
     LUT6                            2

   Estimated number of LCs:          2
"""
        mapped = parse_yosys_hierarchy(fixture)
        self.assertEqual(mapped["total_cells"], 4)
        self.assertEqual(mapped["total_luts"], 2)
        self.assertEqual(mapped["total_ffs"], 2)
        self.assertEqual(mapped["hierarchy_nodes"], 2)
        self.assertEqual(mapped["module_own_hotspots"][0]["instances"], 2)
        self.assertEqual(mapped["module_subtrees"][0]["subtree_luts"], 2)
        with self.assertRaisesRegex(MappingReportError, "weighted hierarchy"):
            parse_yosys_hierarchy(
                fixture.replace("Number of cells:                 4", "Number of cells:                 3")
                .replace("FDRE                            2", "FDRE                            1")
            )
        with self.assertRaisesRegex(MappingReportError, "missing finished"):
            parse_yosys_hierarchy("Yosys failed before the mapped stats")


if __name__ == "__main__":
    unittest.main()
