import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
ADAPTER = ROOT / "src/main/scala/aethercore/sim/AetherSoCAxi4HostMemoryAdapter.scala"
TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2Axi4CompatSimTop.scala"


class V2Axi4LinuxPathSourceContract(unittest.TestCase):
    def test_axi_host_adapter_routes_memoryhub_source_ids(self):
        source = ADAPTER.read_text(encoding="utf-8")
        self.assertIn("private val DataSource = 0", source)
        self.assertIn("private val PtwSource = 1", source)
        self.assertIn("private val InstructionSource = 2", source)
        self.assertIn("readId(idBits - 1, localTxnIdBits)", source)

    def test_axi_reads_return_lane_aligned_data(self):
        source = ADAPTER.read_text(encoding="utf-8")
        self.assertIn("semanticReadData << readBitShift", source)
        self.assertIn("io.axi.r.bits.data := laneReadDataWide", source)
        self.assertIn("io.axi.r.bits.resp := Mux(readFault", source)

    def test_axi_writes_are_lowered_to_historical_data_port(self):
        source = ADAPTER.read_text(encoding="utf-8")
        self.assertIn("private val lowWriteData = wData >> writeBitShift", source)
        self.assertIn("private val lowWriteMask = wStrb >> writeByteOffset", source)
        self.assertIn("io.memAtomic := false.B", source)
        self.assertIn("io.memOp := Mux(writeActive, AetherMemOp.Write, AetherMemOp.Read)", source)

    def test_compat_top_forces_linux_through_real_axi_soc(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn('override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"', source)
        self.assertIn("new AetherCoreV2Axi4SoC", source)
        self.assertIn("new AetherSoCAxi4HostMemoryAdapter", source)
        self.assertNotIn("new AetherCoreV2UnifiedMemorySoC", source)
        self.assertNotIn("new AetherCoreV2LinuxSoC", source)


if __name__ == "__main__":
    unittest.main()
