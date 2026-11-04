"""Source and report contracts for the pin-only FPGA qualification boundary."""

import importlib.util
import json
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / "src/main/scala/aethercore/soc/AetherCoreProductFpgaPins.scala"
EMITTER = ROOT / "src/main/scala/aethercore/soc/ElaborateProductSoC.scala"
SCRIPT = ROOT / "tools/compare_product_fpga_pin_cost.sh"
REPORT = ROOT / "tools/report_product_fpga_pin_cost.py"
WORKFLOW = ROOT / ".github/workflows/product-fpga-physical-pin-ppa.yml"


class ProductPhysicalFpgaContract(unittest.TestCase):
    def test_physical_boundary_reuses_exact_instrumented_machine(self):
        source = PINS.read_text()
        self.assertIn("new AetherCoreProductFpgaSoC(", source)
        self.assertIn("productConfigOverride = Some(config)", source)
        self.assertIn("requireBoardCompatible(config, implementedPaddrBits)", source)
        for port in ("axi", "uartClockTick", "timebaseTick", "serialRx", "serialTx"):
            self.assertIn(f"val {port} =", source)
        for debug in ("val commit =", "val retire =", "val frontendPc =",
                      "val mtime =", "val dcacheHitCount ="):
            self.assertNotIn(debug, source)
        for channel in ("aw", "w", "b", "ar", "r"):
            self.assertIn(f"io.axi.{channel}", source)

    def test_full_soc_comparison_selects_same_cpu_geometry_in_four_modes(self):
        emitter = EMITTER.read_text()
        script = SCRIPT.read_text()
        for entry in (
            "ElaborateProductFpgaSoCGeometry",
            "ElaborateProductFpgaPinsGeometry",
            "ElaborateProductFpgaSoCRv64gc",
            "ElaborateProductFpgaPinsRv64gc",
        ):
            self.assertIn(f"object {entry} extends App", emitter)
            self.assertIn(f"emitter={entry}", script)
        self.assertIn("smallOpenSbiDistroTarget(32, geometry)", emitter)
        self.assertIn("new AetherCoreProductFpgaPins(implementedPaddrBits = 32", emitter)
        self.assertIn("for isa in integer floating; do", script)
        self.assertIn("for boundary in probe pins; do", script)
        self.assertIn("synth_ecp5", script)
        self.assertIn("report_product_fpga_pin_cost.py", script)
        workflow = WORKFLOW.read_text()
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertIn("compare_product_fpga_pin_cost.sh", workflow)

    def test_report_keeps_probe_and_fp_cost_separate(self):
        spec = importlib.util.spec_from_file_location("pin_cost", REPORT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with TemporaryDirectory() as temp:
            root = Path(temp)
            for isa, boundary, size in (
                ("integer", "probe", 110), ("integer", "pins", 80),
                ("floating", "probe", 210), ("floating", "pins", 165),
            ):
                top = ("AetherCoreProductFpgaPins" if boundary == "pins"
                       else "AetherCoreProductFpgaSoC")
                payload = {"capacity": "small", "isa": isa,
                           "boundary": boundary, "top": top,
                           **{metric: size for metric in module.METRICS}}
                path = root / "small" / isa / boundary
                path.mkdir(parents=True)
                (path / "metrics.json").write_text(json.dumps(payload))
            with redirect_stdout(StringIO()):
                result = module.report("small", root)
            self.assertEqual(result["probe_overhead"]["integer"]["lut4"], 30)
            self.assertEqual(result["probe_overhead"]["floating"]["lut4"], 45)
            self.assertEqual(result["floating_overhead"]["probe"]["lut4"], 100)
            self.assertEqual(result["floating_overhead"]["pins"]["lut4"], 85)
            self.assertEqual(result["issue_width"], 1)
            self.assertIn("not routed Fmax".lower(), result["qualification"].lower())
            self.assertTrue((root / "small/summary.json").is_file())


if __name__ == "__main__":
    unittest.main()
