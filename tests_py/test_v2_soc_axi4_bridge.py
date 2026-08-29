import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
AXI = ROOT / "src/main/scala/aethercore/soc/AetherAxi4.scala"
BRIDGE = ROOT / "src/main/scala/aethercore/soc/AetherMemToAxi4Bridge.scala"


class V2Axi4BridgeSourceContract(unittest.TestCase):
    def test_axi_boundary_has_standard_five_channels(self):
        source = AXI.read_text(encoding="utf-8")
        for channel in (
            "val aw = Decoupled",
            "val w = Decoupled",
            "val b = Flipped(Decoupled",
            "val ar = Decoupled",
            "val r = Flipped(Decoupled",
        ):
            self.assertIn(channel, source)
        self.assertIn("val len = UInt(8.W)", source)
        self.assertIn("val size = UInt(3.W)", source)
        self.assertIn("val burst = UInt(2.W)", source)
        self.assertIn("val strb = UInt((dataBits / 8).W)", source)

    def test_bridge_keeps_axi_below_aethermem(self):
        source = BRIDGE.read_text(encoding="utf-8")
        self.assertIn("AetherMemRequest", source)
        self.assertIn("AetherMemResponse", source)
        self.assertIn("new Axi4MasterIO", source)
        self.assertNotIn("TinyPagedCore", source)
        self.assertNotIn("AetherCoreV2LinuxSoC", source)
        self.assertNotIn("uartAddress", source)
        self.assertNotIn("plicBase", source)

    def test_bridge_handles_narrow_lane_alignment(self):
        source = BRIDGE.read_text(encoding="utf-8")
        self.assertIn("private val byteOffset =", source)
        self.assertIn("private val bitShift = byteOffset << 3", source)
        self.assertIn("writeData << bitShift", source)
        self.assertIn("writeMask << byteOffset", source)
        self.assertIn("io.axi.r.bits.data >> bitShift", source)

    def test_bridge_preserves_full_atomic_a_extension_boundary(self):
        source = BRIDGE.read_text(encoding="utf-8")
        for op in (
            "AtomicOp.Lr",
            "AtomicOp.Sc",
            "AtomicOp.Swap",
            "AtomicOp.Add",
            "AtomicOp.Xor",
            "AtomicOp.And",
            "AtomicOp.Or",
            "AtomicOp.Min",
            "AtomicOp.Max",
            "AtomicOp.Minu",
            "AtomicOp.Maxu",
        ):
            self.assertIn(op, source)
        self.assertIn("reservationValid", source)
        self.assertIn("reservationAddress", source)
        self.assertIn("reservationSize", source)
        self.assertIn("responseData := 1.U", source)
        self.assertIn("responseData := Mux(responseOkay && responseMatches, 0.U, 1.U)", source)

    def test_v0_atomic_domain_is_explicitly_single_writer(self):
        source = BRIDGE.read_text(encoding="utf-8")
        self.assertIn("only coherent writer", source)
        self.assertIn("multi-master/coherent FPGA fabric", source)
        self.assertIn("AXI-exclusive/retry", source)


if __name__ == "__main__":
    unittest.main()
