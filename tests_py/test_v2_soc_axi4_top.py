import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOP = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2Axi4SoC.scala"


class V2Axi4SoCSourceContract(unittest.TestCase):
    def test_axi_top_composes_unified_soc_and_bridge(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("class AetherCoreV2Axi4SoC extends Module", source)
        self.assertIn("new AetherCoreV2UnifiedMemorySoC", source)
        self.assertIn("new AetherMemToAxi4Bridge", source)
        self.assertIn("bridge.io.request <> soc.io.memoryRequest", source)
        self.assertIn("soc.io.memoryResponse <> bridge.io.response", source)
        self.assertIn("soc.externalTxnIdBits == TxnIdBits", source)

    def test_axi_is_the_only_external_memory_protocol(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("val axi = new Axi4MasterIO", source)
        self.assertNotIn("val memoryRequest =", source)
        self.assertNotIn("val memoryResponse =", source)
        self.assertNotIn("val imemValid =", source)
        self.assertNotIn("val ptwValid =", source)
        self.assertNotIn("val memValid =", source)

    def test_bootrom_and_platform_devices_remain_internal_to_the_logical_soc(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("BootROM", source)
        self.assertIn("Only requests that escaped the internal SoC address map", source)
        self.assertNotIn("new AetherSoCBootRom", source)
        self.assertNotIn("new AetherUart16550", source)
        self.assertNotIn("new AetherPlic", source)
        self.assertNotIn("new AetherAclintMtimer", source)
        self.assertIn("io.icacheHitCount := soc.io.icacheHitCount", source)
        self.assertIn("io.icacheMissCount := soc.io.icacheMissCount", source)

    def test_board_specific_phy_and_ddr_remain_outside(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("Board-specific clock/reset generation", source)
        self.assertIn("serial UART PHY remain outside", source)
        self.assertNotIn("MMCME2", source)
        self.assertNotIn("PLLE2", source)
        self.assertNotIn("IBUF", source)


if __name__ == "__main__":
    unittest.main()
