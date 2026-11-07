"""Fast contract for an opt-in FPGA F/D cost comparison, never Debian signoff."""

import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[1]
EMITTER = ROOT / "src/main/scala/aethercore/ElaborateProductFloatingSlice.scala"
SCRIPT = ROOT / "tools/compare_product_fpga_floating.sh"
REPORT = ROOT / "tools/report_product_fpga_floating_cost.py"


class FloatingFpgaCostContract(unittest.TestCase):
    def test_integer_and_fp_compare_the_same_one_wide_core(self):
        emitter = EMITTER.read_text()
        for fragment in (
            'case "small" => ProductCoreProfiles.smallGeometry',
            'case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry',
            'case "integer" => ProductCoreProfiles.engineeringSoftware',
            'case "floating" => ProductCoreProfiles.distroSoftware',
            'geometry.issueWidth == 1 && geometry.retireWidth == 1',
            'platform = ProductCoreProfiles.smallEngineering.platform.copy(paddrBits = 32)',
            'new ProductSmallMemoryCore(config)',
        ):
            self.assertIn(fragment, emitter)
        self.assertNotIn("new ProductSmallPagedCore", emitter)
        # The existing physical FPGA SoC A/B still measures the old no-F path.
        original = (ROOT / ".github/workflows/product-fpga-geometry-ppa.yml").read_text()
        self.assertIn("ElaborateProductFpgaSoCGeometry", original)
        self.assertNotIn("ElaborateProductFloatingSlice", original)

    def test_measurement_is_real_synthesis_but_explicitly_opt_in(self):
        script = SCRIPT.read_text()
        for fragment in (
            'if [[ $# -ne 2',
            'for isa in integer floating; do',
            'ElaborateProductFloatingSlice',
            'yosys -Q -p',
            'synth_ecp5',
            'ltp -noff',
            'netlist.json',
            'metrics.json',
            'report_product_fpga_floating_cost.py',
        ):
            self.assertIn(fragment, script)
        self.assertIn("not a linux boot", script.lower())

    def test_summary_reports_exact_fp_overhead_and_qualifications(self):
        module_spec = importlib.util.spec_from_file_location("product_fpga_cost", REPORT)
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        with TemporaryDirectory() as temp:
            root = Path(temp)
            capacity = root / "small"
            integer = {"capacity": "small", "isa": "integer", **{
                k: 12 for k in module.METRICS}}
            floating = {"capacity": "small", "isa": "floating", **{
                k: 17 for k in module.METRICS}}
            for isa, metrics in (("integer", integer), ("floating", floating)):
                folder = capacity / isa
                folder.mkdir(parents=True)
                (folder / "metrics.json").write_text(json.dumps(metrics))
            report = module.report("small", root)
            self.assertEqual(report["issue_width"], 1)
            self.assertEqual(report["paddr_bits"], 32)
            self.assertEqual(report["floating_overhead"], {
                metric: 5 for metric in module.METRICS})
            self.assertIn("support is opt-in", report["qualification"])
            self.assertIn("Linux distro and board Fmax not qualified",
                          report["qualification"])
            self.assertTrue((capacity / "summary.json").is_file())


if __name__ == "__main__":
    unittest.main()
