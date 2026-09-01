import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2VirtualFpgaBoardSimTop.scala"
TICK = ROOT / "src/main/scala/aethercore/soc/phy/AetherFractionalTickGenerator.scala"
FPGA = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2FpgaSoC.scala"


class V2VirtualFpgaBoardContract(unittest.TestCase):
    def test_virtual_board_instantiates_the_real_fpga_top(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("class AetherCoreV2VirtualFpgaBoardSimTop(", source)
        self.assertIn("Module(new AetherCoreV2FpgaSoC(implementedPaddrBits = paddrBits))", source)
        self.assertIn("AetherSoCAxi4HostMemoryAdapter", source)
        self.assertIn("AetherUart8N1Phy", source)
        self.assertNotIn("new AetherCoreV2Axi4SoC", source)

    def test_virtual_board_can_instantiate_the_fail_closed_pa32_fpga_path(self):
        source = TOP.read_text(encoding="utf-8")
        elab = (ROOT / "src/main/scala/aethercore/ElaborateV2VirtualFpgaBoard.scala").read_text(encoding="utf-8")
        self.assertIn(
            "implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits",
            source,
        )
        self.assertIn("private val paddrBits = implementedPaddrBits", source)
        self.assertIn("platform.copy(paddrBits = paddrBits)", source)
        self.assertIn("ElaborateV2VirtualFpgaBoardPA32RV64", elab)
        self.assertIn("implementedPaddrBits = 32", elab)

    def test_linux_console_must_cross_the_serial_pins(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("hostUart.io.serialRx := fpga.io.serialTx", source)
        self.assertIn("fpga.io.serialRx := hostUart.io.serialTx", source)
        self.assertIn("io.uartValid := hostUart.io.rxValid", source)
        self.assertIn("io.uartByte := hostUart.io.rxByte", source)
        self.assertIn("io.rxReady := hostUart.io.txReady", source)
        self.assertNotIn("io.uartValid := fpga.io.uartValid", source)
        self.assertNotIn("io.uartByte := fpga.io.uartByte", source)

    def test_virtual_board_uses_declared_clock_domains_as_enables(self):
        source = TOP.read_text(encoding="utf-8")
        tick = TICK.read_text(encoding="utf-8")
        self.assertIn("virtualClockFrequencyHz: Long = 20_000_000L", source)
        self.assertIn("targetFrequencyHz = boardSpec.uartClockFrequencyHz", source)
        self.assertIn("targetFrequencyHz = boardSpec.timebaseFrequencyHz", source)
        self.assertIn("fpga.io.uartClockTick := uartTickGenerator.io.tick", source)
        self.assertIn("fpga.io.timebaseTick := timebaseTickGenerator.io.tick", source)
        self.assertIn("class AetherFractionalTickGenerator(", tick)
        self.assertIn("private val fire = sum >= source", tick)
        self.assertIn("accumulator := Mux(fire, sum - source, sum)", tick)

    def test_virtual_usb_uart_is_fixed_to_the_board_default_baud(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("hostUart.io.baudDivisor := boardSpec.uartDefaultDivisor.U", source)
        self.assertIn("io.uartBaudDivisor := fpga.io.uartBaudDivisor", source)

    def test_power_on_reset_is_board_owned(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("powerOnResetCycles: Int = 16", source)
        self.assertIn("private val boardReset = reset.asBool || resetCount =/= 0.U", source)
        self.assertIn("withReset(boardReset)", source)
        self.assertIn("io.boardResetActive := boardReset", source)

    def test_production_fpga_top_remains_board_neutral(self):
        source = FPGA.read_text(encoding="utf-8")
        self.assertNotIn("AetherSoCAxi4HostMemoryAdapter", source)
        self.assertNotIn("AetherFractionalTickGenerator", source)
        self.assertNotIn("20_000_000", source)


if __name__ == "__main__":
    unittest.main()
