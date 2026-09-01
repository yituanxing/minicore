import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/v2-soc-fpga-synth-proxy.yml"
FPGA = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2FpgaSoC.scala"
ELAB = ROOT / "src/main/scala/aethercore/ElaborateV2FpgaSoC.scala"
BOARD = ROOT / "src/main/scala/aethercore/soc/AetherSoCAddressMap.scala"


class V2FpgaSynthesisProxyContract(unittest.TestCase):
    def test_proxy_targets_the_production_fpga_top(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        fpga = FPGA.read_text(encoding="utf-8")
        elab = ELAB.read_text(encoding="utf-8")

        self.assertIn("class AetherCoreV2FpgaSoC(", fpga)
        self.assertIn("new AetherCoreV2FpgaSoC", elab)
        self.assertIn("aethercore.ElaborateV2FpgaSoCRV64", workflow)
        self.assertIn("hierarchy -check -top AetherCoreV2FpgaSoC", workflow)
        self.assertIn("synth_ecp5 -top AetherCoreV2FpgaSoC", workflow)

    def test_physical_fpga_default_disables_cache_counters(self):
        fpga = FPGA.read_text(encoding="utf-8")
        self.assertIn("enableCacheCounters: Boolean = false", fpga)
        self.assertIn("enableCacheCounters = enableCacheCounters", fpga)

    def test_product_fpga_profile_is_frozen_to_pa32(self):
        fpga = FPGA.read_text(encoding="utf-8")
        board = BOARD.read_text(encoding="utf-8")
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("val FpgaImplementedPaddrBits: Int = 32", board)
        self.assertIn(
            "implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits",
            fpga,
        )
        self.assertIn("PRODUCT_IMPLEMENTED_PADDR_BITS: '32'", workflow)
        self.assertIn('if (( lut4 >= 50000 )); then', workflow)

    def test_proxy_measures_structure_without_claiming_board_fmax(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("ltp -noff AetherCoreV2FpgaSoC", workflow)
        self.assertIn("LUT4", workflow)
        self.assertIn("TRELLIS_FF", workflow)
        self.assertIn("DP16KD", workflow)
        self.assertNotIn("nextpnr", workflow)
        self.assertNotIn("Fmax", workflow)
        self.assertNotIn("MHz", workflow)


if __name__ == "__main__":
    unittest.main()
