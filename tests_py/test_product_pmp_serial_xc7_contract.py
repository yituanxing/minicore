"""Fail-closed checks for the opt-in FPGA PMP serial-geometry experiment."""

from pathlib import Path
import runpy
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SERIAL = ROOT / "src/main/scala/aethercore/core/PmpSerialRangePredecoder.scala"
SPEC = ROOT / "src/test/scala/aethercore/PmpSerialRangePredecoderSpec.scala"
WORKFLOW = ROOT / ".github/workflows/product-pmp-serial-xc7.yml"
REPORTER = ROOT / "tools/ci/product_pmp_serial_map_report.py"


class ProductPmpSerialXc7Contract(unittest.TestCase):
    def test_opt_in_only_does_not_change_live_or_snapshot_product_owners(self):
        serial = SERIAL.read_text()
        product = (ROOT / "src/main/scala/aethercore/core/product/ProductSmallPagedCore.scala").read_text()
        data = (ROOT / "src/main/scala/aethercore/core/product/ProductDataResolver.scala").read_text()
        self.assertIn("new PmpRangeDecoder(Xlen, PmpConstants.MaxEntries, PA)", product)
        self.assertIn("new PmpRangeDecoder(64, PmpConstants.MaxEntries, PA)", data)
        self.assertNotIn("PmpSerialRangePredecoder", product)
        self.assertNotIn("PmpSerialRangePredecoder", data)
        self.assertIn("val refreshing = RegInit(true.B)", serial)
        self.assertIn("io.busy := refreshing || io.refresh", serial)
        self.assertIn("assert(!refreshing", serial)
        self.assertIn("new PmpRangeDecoder(xlen, 2, paddrBits)", serial)

    def test_reference_test_and_manual_only_workflow(self):
        spec, workflow = SPEC.read_text(), WORKFLOW.read_text()
        self.assertIn("new PmpRangeDecoder(64, 16, 56)", spec)
        self.assertIn("dut.clock.step(16)", spec)
        self.assertIn("previousLower.get", spec)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertIn("PmpSerialRangePredecoderSpec", workflow)
        self.assertIn("yowasp-yosys==0.70.0.0.post1259", workflow)
        self.assertIn("synth_xilinx -family xc7 -noiopad", workflow)
        self.assertIn("mapping-summary.json", workflow)

    def test_summary_reader_rejects_missing_or_truncated_mapping(self):
        read_counts = runpy.run_path(str(REPORTER))["read_counts"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "sample.log"
            path.write_text("ERROR: incomplete")
            with self.assertRaises(ValueError):
                read_counts(path, "PmpSerialRangePredecoder")
            path.write_text("=== design hierarchy ===\n 5 LUT2\n")
            with self.assertRaises(ValueError):
                read_counts(path, "PmpSerialRangePredecoder")
            path.write_text("=== design hierarchy ===\n 5 LUT2\n 3 FDRE\n Estimated number of LCs: 4\n")
            self.assertEqual(read_counts(path, "PmpSerialRangePredecoder")["lut1_6"], 5)


if __name__ == "__main__":
    unittest.main()
