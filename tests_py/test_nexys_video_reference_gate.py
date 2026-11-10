"""Offline tests: Nexys reference gate must never imply a physical pass."""

import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PY_GATE = ROOT / "tools/fpga/nexys_video_reference_gate.py"
TCL_IMPORT = ROOT / "tools/fpga/nexys_video_ddr_import.tcl"
loader = importlib.util.spec_from_file_location("nexys_gate", PY_GATE)
gate = importlib.util.module_from_spec(loader)
loader.loader.exec_module(gate)


def contract_fixture() -> dict[str, str]:
    tcl = "\n".join((
        "proc create_hier_cell_DDR { parent name } { }",
        "proc write_mig_file_riscv_mig_7series_0_0 { path } { }",
        "<C0_S_AXI_ADDR_WIDTH>29</C0_S_AXI_ADDR_WIDTH>",
        "<C0_S_AXI_DATA_WIDTH>128</C0_S_AXI_DATA_WIDTH>",
        "<C0_S_AXI_ID_WIDTH>4</C0_S_AXI_ID_WIDTH>",
        "<C0_S_AXI_SUPPORTS_NARROW_BURST>0</C0_S_AXI_SUPPORTS_NARROW_BURST>",
        "CONFIG.NUM_CLKS {2}",
        "get_bd_intf_pins axi_smc_1/S00_AXI",
        "get_bd_intf_pins mig_7series_0/S_AXI",
        "get_bd_pins axi_smc_1/aclk1",
        "get_bd_pins mig_7series_0/ui_clk",
        "get_bd_pins mig_7series_0/device_temp_i",
        "CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {50.000}",
        "CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000}",
    ))
    return {
        "board/nexys-video/riscv-2026.1.tcl": tcl,
        "board/nexys-video/top.xdc":
            "set_property PACKAGE_PIN R4 [get_ports sys_clock]\n"
            "set_property PACKAGE_PIN G4 [get_ports reset]",
        "board/nexys-video/uart.xdc":
            "set_property PACKAGE_PIN V18 [get_ports usb_uart_rxd]\n"
            "set_property PACKAGE_PIN AA19 [get_ports usb_uart_txd]",
        "board/nexys-video/sdc.xdc": "\n".join(
            f"set_property PACKAGE_PIN {pin} [get_ports {signal}]"
            for pin, signal in (("W19", "sdio_clk"), ("W20", "sdio_cmd"),
                                ("V19", "sdio_dat[0]"), ("U18", "sdio_dat[3]"),
                                ("V20", "sdio_reset"), ("T18", "sdio_cd"))),
        "board/mem-reset-control.v":
            "mmcm_locked calib_complete ui_clk_sync_rst mem_ok",
    }


class NexysVideoReferenceGate(unittest.TestCase):
    def test_official_reference_contract_does_not_claim_routed(self):
        gate.validate_contract(contract_fixture())
        self.assertEqual(gate.COMMIT, "1e6b86aebe5ba6675a1193ec12b417c1f7cb8bf0")
        self.assertEqual(gate.git_blob_sha(b"hello"),
                         "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0")

    def test_mig_narrow_mismatch_must_be_explicit(self):
        files = contract_fixture()
        path = "board/nexys-video/riscv-2026.1.tcl"
        files[path] = files[path].replace("SUPPORTS_NARROW_BURST>0", "SUPPORTS_NARROW_BURST>1")
        with self.assertRaisesRegex(ValueError, "upstream DDR/clock contract changed"):
            gate.validate_contract(files)

    def test_ddr_temperature_and_spi_pins_are_not_optional(self):
        files = contract_fixture()
        path = "board/nexys-video/sdc.xdc"
        files[path] = files[path].replace("PACKAGE_PIN U18", "PACKAGE_PIN R18")
        with self.assertRaisesRegex(ValueError, "board pin changed"):
            gate.validate_contract(files)
        files = contract_fixture()
        path = "board/nexys-video/riscv-2026.1.tcl"
        files[path] = files[path].replace("mig_7series_0/device_temp_i", "unused_device_temp")
        with self.assertRaisesRegex(ValueError, "upstream DDR/clock contract changed"):
            gate.validate_contract(files)

    def test_missing_reference_never_produces_a_source_or_physical_pass(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            reference = Path(temp_dir) / "not-cloned"
            output = Path(temp_dir) / "would-be-evidence.json"
            result = subprocess.run(
                ["python3", str(PY_GATE), "--reference-root", str(reference),
                 "--output", str(output)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 4)
            self.assertIn("NEXYS_REFERENCE_NOT_VERIFIED", result.stderr)
            self.assertFalse(output.exists())

    def test_tcl_import_only_two_procedures_and_flips_mig_narrow(self):
        if not shutil.which("tclsh"):
            self.skipTest("tclsh unavailable")
        script = "\n".join((
            f"source {{{TCL_IMPORT}}}",
            "set reference {",
            "proc write_mig_file_riscv_mig_7series_0_0 {path} {",
            "  set f [open $path w]",
            "  puts $f {<C0_S_AXI_SUPPORTS_NARROW_BURST>0</C0_S_AXI_SUPPORTS_NARROW_BURST>}",
            "  close $f",
            "}",
            "proc create_hier_cell_DDR {parent name} {return $name}",
            "# Top-level commands in upstream must never execute:",
            "error {ROCKET_ROOT_WOULD_EXECUTE}",
            "}",
            "set methods [::aether_nexys::extract_ddr_commands $reference]",
            "foreach name {write_mig_file_riscv_mig_7series_0_0 create_hier_cell_DDR} {",
            "  uplevel #0 [dict get $methods $name]",
            "}",
            "set fd [file tempfile path]",
            "close $fd",
            "write_mig_file_riscv_mig_7series_0_0 $path",
            "set fd [open $path r]",
            "set value [read $fd]",
            "close $fd",
            "file delete $path",
            "if {![string match {*NARROW_BURST>1*} $value]} {error {MIG_NARROW_NOT_ENABLED}}",
            "puts {NEXYS_PROCEDURE_EXTRACTION_PASS}",
        ))
        # Running Tcl from a script file makes errors fatal; feeding stdin
        # starts interactive semantics and can falsely report success.
        with tempfile.TemporaryDirectory() as temp_dir:
            script_path = Path(temp_dir) / "extract_test.tcl"
            script_path.write_text(script, encoding="utf-8")
            result = subprocess.run(["tclsh", str(script_path)], text=True,
                                    capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("NEXYS_PROCEDURE_EXTRACTION_PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
