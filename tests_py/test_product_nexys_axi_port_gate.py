"""Offline interface tests before any Vivado/SD1 physical assertion."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "tools/fpga/product_nexys_axi_port_gate.py"
BRIDGE = ROOT / "tools/fpga/aether_product_nexys_axi_bridge.sv"
BD_SCRIPT = ROOT / "tools/fpga/nexys_video_product_bd.tcl"
ENTRY = ROOT / "tools/ci/product_fpga_nexys_video_ddr.sh"
loader = importlib.util.spec_from_file_location("nexys_axi_gate", GATE)
gate = importlib.util.module_from_spec(loader)
loader.loader.exec_module(gate)


def synthetic_generated_product(contract=None):
    """Source-compatible fake Chisel emitter header; never a physical claim."""
    contract = contract or gate.axi_contract()
    lines = []
    for name, (direction, width) in contract.items():
        width_syntax = f"[{width - 1}:0] " if width > 1 else ""
        lines.append(f"    {direction} logic {width_syntax}{name}")
    return f"module {gate.TOP}(\n" + ",\n".join(lines) + "\n);\nendmodule\n"


class ProductNexysAxiPortGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bridge = BRIDGE.read_text(encoding="utf-8")

    def test_complete_small_geometry_axi_but_not_physical_pass(self):
        result = gate.verify_bridge(self.bridge, synthetic_generated_product())
        self.assertEqual(result["status"], "PRODUCT_NEXYS_AXIMM_PORTS_ONLY")
        self.assertEqual(result["smartconnect_axi_addr_bits"], 29)
        self.assertEqual(result["product_axi_addr_bits"], 28)
        for key in ("vivado_bus_inference_verified", "mig_generated", "routed",
                    "ddr_calibrated", "debian_login"):
            self.assertIs(result[key], False)

    def test_wrong_generated_id_width_rejected(self):
        changed = gate.axi_contract()
        changed["io_localDdrAxi_aw_bits_id"] = ("output", 5)
        with self.assertRaisesRegex(ValueError, "Product generated port mismatch"):
            gate.verify_bridge(self.bridge, synthetic_generated_product(changed))

    def test_real_circt_grouped_ansi_port_syntax(self):
        # Observed in a real historical CIRCT output: direction/width are
        # inherited by following names rather than repeated on every line.
        real_style_header = """\
          input clock, // source location metadata
                reset, // another grouped one-bit input
                io_localDdrAxi_aw_ready,
          output [3:0] io_localDdrAxi_aw_bits_id,
                       io_localDdrAxi_ar_bits_id,
          output io_spiSclk,
                 io_spiMosi
        """
        self.assertEqual(gate.ports(real_style_header, grouped_ansi=True), {
            "clock": ("input", 1), "reset": ("input", 1),
            "io_localDdrAxi_aw_ready": ("input", 1),
            "io_localDdrAxi_aw_bits_id": ("output", 4),
            "io_localDdrAxi_ar_bits_id": ("output", 4),
            "io_spiSclk": ("output", 1), "io_spiMosi": ("output", 1),
        })

    def test_unmapped_new_required_input_rejected(self):
        changed = gate.axi_contract()
        changed["io_newUnconnectedBoardInput"] = ("input", 1)
        with self.assertRaisesRegex(ValueError, "new Product input has no physical source"):
            gate.verify_bridge(self.bridge, synthetic_generated_product(changed))

    def test_aw_ar_swap_and_nonzero_upper_address_rejected(self):
        mutated = self.bridge.replace(".io_localDdrAxi_aw_bits_id(M_AXI_AWID)",
                                      ".io_localDdrAxi_aw_bits_id(M_AXI_ARID)")
        with self.assertRaisesRegex(ValueError, "bridge AXI wiring mismatch"):
            gate.verify_bridge(mutated, synthetic_generated_product())
        mutated = self.bridge.replace("{1'b0, product_awaddr}",
                                      "{1'b1, product_awaddr}")
        with self.assertRaisesRegex(ValueError, "zero-extend"):
            gate.verify_bridge(mutated, synthetic_generated_product())

    def test_missing_generated_rtl_never_claims_port_verification(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / "report.json"
            result = subprocess.run(
                ["python3", str(GATE), "--rtl-dir", temp_dir,
                 "--bridge", str(BRIDGE), "--output", str(target)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 4)
            self.assertFalse(target.exists())
            self.assertIn("PRODUCT_NEXYS_PORTS_NOT_VERIFIED", result.stderr)

    def test_bd_preserves_real_clock_reset_xadc_and_axi_cdc(self):
        tcl = BD_SCRIPT.read_text(encoding="utf-8")
        for required in (
            "connect_bd_intf_net [get_bd_intf_pins Product/M_AXI] [get_bd_intf_pins DDR/S00_AXI]",
            "get_bd_pins $clk/clk_out1", "get_bd_pins DDR/axi_clock",
            "get_bd_pins $clk/clk_out2", "get_bd_pins DDR/clock_200MHz",
            "get_bd_pins DDR/axi_reset", "get_bd_pins Product/cpu_aresetn",
            "get_bd_pins DDR/mem_ok", "get_bd_pins Product/ddr_mem_ok",
            "get_bd_pins $xadc/temp_out", "get_bd_pins DDR/device_temp",
            "assign_bd_address -offset 0x00000000 -range 0x10000000",
            "validate_bd_design", "PRODUCT_NEXYS_LOGICAL_BD_ONLY",
        ):
            self.assertIn(required, tcl)
        self.assertNotIn("RocketChip", tcl)
        self.assertNotIn("route_design", tcl)
        self.assertNotIn("DEBIAN_LOGIN_PASS", tcl)

    def test_integrate_entry_rejects_missing_evidence_without_vivado(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / "new-result"
            result = subprocess.run(
                ["bash", str(ENTRY), "integrate-product", "missing-reference",
                 str(Path(temp_dir) / "missing-pinned-export"), str(target)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(target.exists())
            self.assertNotIn("PRODUCT_NEXYS_LOGICAL_BD_ONLY", result.stdout)


if __name__ == "__main__":
    unittest.main()
