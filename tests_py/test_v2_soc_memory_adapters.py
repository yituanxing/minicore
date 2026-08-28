import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
ADAPTERS = ROOT / "src/main/scala/aethercore/soc/AetherSoCMemoryAdapters.scala"


class V2SoCMemoryAdaptersSourceContract(unittest.TestCase):
    def setUp(self):
        self.source = ADAPTERS.read_text(encoding="utf-8")

    def test_data_adapter_separates_request_acceptance_from_terminal_ready(self):
        source = self.source
        self.assertIn("class AetherSoCLegacyDataAdapter", source)
        self.assertIn("private val active = RegInit(false.B)", source)
        self.assertIn("io.request.valid := io.legacyValid && !active", source)
        self.assertIn("when(io.request.fire)", source)
        self.assertIn("io.legacyReady := active && io.response.valid", source)
        self.assertIn("when(io.response.fire)", source)
        self.assertNotIn("io.legacyReady := io.request.ready", source)

    def test_instruction_adapter_drops_redirected_stale_response(self):
        source = self.source
        self.assertIn("class AetherSoCInstructionReadAdapter", source)
        self.assertIn("requestAddr := io.legacyAddr", source)
        self.assertIn("io.legacyAddr === requestAddr", source)
        self.assertIn(
            "io.legacyReady := active && io.response.valid && currentRequestMatches",
            source,
        )
        self.assertIn("io.response.ready := active", source)
        self.assertIn("io.legacyBytes === 2.U || io.legacyBytes === 4.U", source)

    def test_ptw_adapter_drops_replaced_walk_response(self):
        source = self.source
        self.assertIn("class AetherSoCPtwReadAdapter", source)
        self.assertIn("io.legacyAddr === requestAddr", source)
        self.assertIn(
            "io.legacyReady := active && io.response.valid && currentRequestMatches",
            source,
        )
        self.assertIn("MemSize.DWord", source)

    def test_read_adapters_emit_semantic_aethermem_not_external_bus_channels(self):
        source = self.source
        self.assertIn("AetherMemRequest", source)
        self.assertIn("AetherMemResponse", source)
        self.assertIn("AetherMemOp.Read", source)
        self.assertNotIn("class AXI", source)
        self.assertNotIn("class TileLink", source)
        self.assertNotIn("val aw =", source)
        self.assertNotIn("val ar =", source)


if __name__ == "__main__":
    unittest.main()
