import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
HUB = ROOT / "src/main/scala/aethercore/soc/AetherSoCMemoryHub.scala"


class V2SoCMemoryHubSourceContract(unittest.TestCase):
    def test_hub_preserves_client_identity_with_source_tag(self):
        source = HUB.read_text(encoding="utf-8")

        self.assertIn("class AetherSoCMemoryHub", source)
        self.assertIn("private val sourceBits = log2Ceil(clientCount)", source)
        self.assertIn("clientTxnIdBits + sourceBits", source)
        self.assertIn("new RRArbiter(", source)
        self.assertIn("Cat(requestArbiter.io.chosen, requestArbiter.io.out.bits.txnId)", source)
        self.assertIn("private val responseSource =", source)
        self.assertIn("private val responseLocalTxnId =", source)
        self.assertIn("responseSource === client.U", source)

    def test_hub_does_not_collapse_semantic_memory_into_external_bus_protocol(self):
        source = HUB.read_text(encoding="utf-8")

        self.assertIn("AetherMemRequest", source)
        self.assertIn("AetherMemResponse", source)
        self.assertNotIn("class Axi", source)
        self.assertNotIn("class AXI", source)
        self.assertNotIn("class TileLink", source)
        self.assertNotIn("val aw =", source)
        self.assertNotIn("val ar =", source)
        self.assertNotIn("TinyPagedCore", source)
        self.assertNotIn("uartAddress", source)
        self.assertNotIn("plicBase", source)

    def test_hub_routes_backpressure_to_exact_response_owner(self):
        source = HUB.read_text(encoding="utf-8")

        self.assertIn("io.downstreamResponse.ready := MuxCase(", source)
        self.assertIn("io.clients(client).response.ready", source)
        self.assertIn("assert(responseSource < clientCount.U", source)


if __name__ == "__main__":
    unittest.main()
