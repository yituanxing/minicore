import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOP = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2UnifiedMemorySoC.scala"
PLATFORM = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2LinuxSoC.scala"


class V2UnifiedMemorySoCSourceContract(unittest.TestCase):
    def test_product_platform_keeps_legacy_defaults_and_opt_in_seams(self):
        source = PLATFORM.read_text(encoding="utf-8")
        self.assertIn("enableInstructionBackpressure: Boolean = false", source)
        self.assertIn("exposeExternalMemoryAttributes: Boolean = false", source)
        self.assertIn("enableInstructionBackpressure = enableInstructionBackpressure", source)
        self.assertIn("core.io.imemReady.get := io.imemReady.get", source)
        self.assertIn("io.memAttributes.get := pending.attributes", source)
        self.assertIn("io.instructionFence := core.io.instructionFence", source)

    def test_unified_top_has_one_semantic_external_memory_master(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("class AetherCoreV2UnifiedMemorySoC extends Module", source)
        self.assertIn("val memoryRequest =", source)
        self.assertIn("val memoryResponse =", source)
        self.assertIn("new AetherMemRequest", source)
        self.assertIn("new AetherMemResponse", source)

        # Host-memory implementation seams must terminate inside this wrapper.
        self.assertNotIn("val imemValid = Output", source)
        self.assertNotIn("val ptwValid = Output", source)
        self.assertNotIn("val memValid = Output", source)

    def test_unified_top_joins_data_ptw_and_instruction_clients(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("new AetherSoCLegacyDataAdapter", source)
        self.assertIn("new AetherSoCPtwReadAdapter", source)
        self.assertIn("new AetherSoCInstructionCache", source)
        self.assertIn("new AetherSoCMemoryHub", source)
        self.assertIn("hub.io.clients(0).request <> dataAdapter.io.request", source)
        self.assertIn("hub.io.clients(1).request <> ptwAdapter.io.request", source)
        self.assertIn("hub.io.clients(2).request <> instructionCache.io.request", source)

    def test_instruction_backpressure_and_memory_attributes_are_enabled_only_here(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertIn("enableInstructionBackpressure = true", source)
        self.assertIn("exposeExternalMemoryAttributes = true", source)
        self.assertIn("platform.io.imemReady.get := instructionCache.io.frontendReady", source)
        self.assertIn("instructionCache.io.invalidateAll := platform.io.instructionFence", source)
        self.assertIn("dataAdapter.io.legacyAttributes := platform.io.memAttributes.get", source)

    def test_external_bus_protocol_is_still_below_unified_soc(self):
        source = TOP.read_text(encoding="utf-8")
        self.assertNotIn("class AXI", source)
        self.assertNotIn("class TileLink", source)
        self.assertNotIn("val aw =", source)
        self.assertNotIn("val ar =", source)


if __name__ == "__main__":
    unittest.main()
