import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TIMER = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherAclintMtimer.scala"
UART = ROOT / "src/main/scala/aethercore/soc/peripheral/AetherUart16550.scala"
FABRIC = ROOT / "src/main/scala/aethercore/soc/AetherSoCPlatformFabric.scala"
LINUX = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2LinuxSoC.scala"
UNIFIED = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2UnifiedMemorySoC.scala"
AXI_TOP = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2Axi4SoC.scala"
AXI_SIM = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2Axi4CompatSimTop.scala"


class V2SoCPhysicalSeamsContract(unittest.TestCase):
    def test_mtimer_requires_an_explicit_architectural_tick(self):
        timer = TIMER.read_text(encoding="utf-8")
        self.assertIn("val timebaseTick = Input(Bool())", timer)
        self.assertIn(
            "WireDefault(Mux(io.timebaseTick, mtime + 1.U, mtime))",
            timer,
        )
        self.assertNotIn("WireDefault(mtime + 1.U)", timer)

    def test_uart_tx_readiness_owns_thre_and_mmio_backpressure(self):
        uart = UART.read_text(encoding="utf-8")
        self.assertIn("val txReady = Input(Bool())", uart)
        self.assertIn("private val txHoldingEmpty = io.txReady", uart)
        self.assertIn("private val threInterrupt = ier(1) && txHoldingEmpty", uart)
        self.assertIn("io.ready := !txDataWrite || io.txReady", uart)
        self.assertIn(
            "private val terminalFire = io.request && io.complete && io.ready",
            uart,
        )

    def test_platform_fabric_routes_physical_seams_to_peripherals(self):
        fabric = FABRIC.read_text(encoding="utf-8")
        self.assertIn("val uartTxReady = Input(Bool())", fabric)
        self.assertIn("val timebaseTick = Input(Bool())", fabric)
        self.assertIn("uart.io.txReady := io.uartTxReady", fabric)
        self.assertIn("timer.io.timebaseTick := io.timebaseTick", fabric)

    def test_legacy_linux_oracle_retains_historical_always_ready_semantics(self):
        linux = LINUX.read_text(encoding="utf-8")
        unified = UNIFIED.read_text(encoding="utf-8")

        self.assertIn("val externalPhysicalSeams: Boolean = false", linux)
        self.assertIn(
            "(if (externalPhysicalSeams) io.uartTxReady.get else true.B)",
            linux,
        )
        self.assertIn(
            "(if (externalPhysicalSeams) io.timebaseTick.get else true.B)",
            linux,
        )
        self.assertIn("val externalPhysicalSeams: Boolean = false", unified)
        self.assertIn(
            "externalPhysicalSeams = externalPhysicalSeams",
            unified,
        )

    def test_fpga_facing_axi_top_exposes_both_physical_seams(self):
        top = AXI_TOP.read_text(encoding="utf-8")
        sim = AXI_SIM.read_text(encoding="utf-8")

        self.assertIn("val uartTxReady = Input(Bool())", top)
        self.assertIn("val timebaseTick = Input(Bool())", top)
        self.assertIn("externalPhysicalSeams = true", top)
        self.assertIn("soc.io.uartTxReady.get := io.uartTxReady", top)
        self.assertIn("soc.io.timebaseTick.get := io.timebaseTick", top)

        self.assertIn("soc.io.uartTxReady := true.B", sim)
        self.assertIn("soc.io.timebaseTick := true.B", sim)


if __name__ == "__main__":
    unittest.main()
