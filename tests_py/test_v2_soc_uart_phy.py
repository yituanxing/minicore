import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BOARD = ROOT / "src/main/scala/aethercore/soc/AetherSoCAddressMap.scala"
UART = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherUart16550.scala"
PHY = ROOT / "src/main/scala/aethercore/soc/phy/AetherUart8N1Phy.scala"
FABRIC = ROOT / "src/main/scala/aethercore/soc/AetherSoCPlatformFabric.scala"
AXI = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2Axi4SoC.scala"
FPGA = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2FpgaSoC.scala"


class V2SoCUartPhyContract(unittest.TestCase):
    def test_board_spec_derives_an_exact_ns16550_divisor(self):
        source = BOARD.read_text(encoding="utf-8")
        self.assertIn("16L * uartBaud.toLong", source)
        self.assertIn("uartClockFrequencyHz % uartDivisorDenominator == 0", source)
        self.assertIn("val uartDefaultDivisor", source)

    def test_ns16550_owns_live_dll_dlm_divisor(self):
        source = UART.read_text(encoding="utf-8")
        self.assertIn("val resetDivisor: Int = 1", source)
        self.assertIn("val baudDivisor = Output(UInt(16.W))", source)
        self.assertIn("io.baudDivisor := Cat(dlm, dll)", source)
        self.assertIn("resetDivisor & 0xff", source)
        self.assertIn("resetDivisor >> 8", source)
        self.assertIn('private val lcr = RegInit("h03".U(8.W))', source)

    def test_phy_is_divisor_driven_standard_8n1(self):
        source = PHY.read_text(encoding="utf-8")
        self.assertIn("private val ticksPerBit = effectiveDivisor << 4", source)
        self.assertIn("private val halfBitTicks = effectiveDivisor << 3", source)
        self.assertIn("Cat(1.U(1.W), io.txByte, 0.U(1.W))", source)
        self.assertIn("io.serialTx := Mux(txActive", source)
        self.assertIn("private val rxMeta = RegInit(true.B)", source)
        self.assertIn("private val rxSync = RegInit(true.B)", source)
        self.assertIn("when(rxSync)", source)
        self.assertIn("rxPendingValid := true.B", source)

    def test_live_divisor_reaches_the_fpga_axi_boundary(self):
        fabric = FABRIC.read_text(encoding="utf-8")
        axi = AXI.read_text(encoding="utf-8")
        self.assertIn("uartResetDivisor = board.uartDefaultDivisor", (ROOT / "src/main/scala/aethercore/soc/AetherCoreV2LinuxSoC.scala").read_text(encoding="utf-8"))
        self.assertIn("io.uartBaudDivisor := uart.io.baudDivisor", fabric)
        self.assertIn("val uartBaudDivisor = Output(UInt(16.W))", axi)
        self.assertIn("io.uartBaudDivisor := soc.io.uartBaudDivisor", axi)

    def test_board_neutral_fpga_top_composes_axi_soc_and_uart_phy(self):
        source = FPGA.read_text(encoding="utf-8")
        self.assertIn("class AetherCoreV2FpgaSoC extends Module", source)
        self.assertIn("new AetherCoreV2Axi4SoC", source)
        self.assertIn("new AetherUart8N1Phy", source)
        self.assertIn("uartPhy.io.baudDivisor := soc.io.uartBaudDivisor", source)
        self.assertIn("soc.io.uartTxReady := uartPhy.io.txReady", source)
        self.assertIn("soc.io.rxValid := uartPhy.io.rxValid", source)
        self.assertIn("val serialRx = Input(Bool())", source)
        self.assertIn("val serialTx = Output(Bool())", source)
        self.assertNotIn("MMCME2", source)
        self.assertNotIn("PLLE2", source)
        self.assertNotIn("DDR", source.replace("DDR/controller", ""))


if __name__ == "__main__":
    unittest.main()
