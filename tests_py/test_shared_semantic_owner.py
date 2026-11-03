"""S batch: one backend-neutral decoder and v2 compatibility shell."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEM = ROOT / "src/main/scala/aethercore/core/semantic"
V2 = ROOT / "src/main/scala/aethercore/core/v2"

class SharedSemanticOwnershipContract(unittest.TestCase):
    def test_five_semantic_enums_and_four_bundles_have_one_definition(self):
        shared = (SEM/"SemanticTypes.scala").read_text()
        legacy = (V2/"FoundationTypes.scala").read_text()
        compat = (V2/"package.scala").read_text()
        self.assertEqual(len(re.findall(r"\bclass DecodedInstruction\b", shared)), 1)
        for name in ("OperandSourceKind", "ControlFlowKind", "MemoryOperationKind",
                     "SystemOperationKind", "OrderingClass"):
            self.assertIn(f"object {name} extends ChiselEnum", shared)
            self.assertNotIn(f"object {name} extends ChiselEnum", legacy)
            self.assertIn(f"val {name} = aethercore.core.semantic.{name}", compat)
        for name in ("DecodedInstruction", "DecodedMemoryOperation",
                     "DecodedControlFlow", "DecodedSystemOperation"):
            self.assertIn(f"class {name}", shared)
            self.assertNotIn(f"class {name}", legacy)
            self.assertIn(f"type {name} = aethercore.core.semantic.{name}", compat)

    def test_backend_never_owns_canonical_decoder(self):
        owner = (SEM/"SemanticDecode.scala").read_text()
        bridge = (V2/"TinySemanticDecode.scala").read_text()
        self.assertIn("class SemanticDecode", owner)
        self.assertIn("val decoder = Module(new Decoder(isa))", owner)
        self.assertIn("val decoded = Output(new DecodedInstruction(xlen))", owner)
        self.assertNotIn("RobDispatch", owner)
        self.assertNotIn("ExecutionClass", owner)
        self.assertNotIn("core.v2", owner)
        self.assertNotIn("new Decoder(", bridge)
        self.assertIn("val semantic = Module(new SemanticDecode(isa))", bridge)
        self.assertIn("io.dispatch.decoded := semantic.io.decoded", bridge)
        self.assertIn("io.dispatch.executionClass := classification", bridge)

    def test_qualified_v2_decode_tests_preserved_and_independent_shared_test_added(self):
        legacy = ROOT/"src/test/scala/aethercore/V2F7SemanticDecodeChecks.scala"
        direct = ROOT/"src/test/scala/aethercore/SharedSemanticDecodeSpec.scala"
        self.assertTrue(legacy.is_file())
        self.assertIn("simulate(new SemanticDecode(", direct.read_text())
        self.assertNotIn("new TinySemanticDecode", direct.read_text())
        f7 = (ROOT/".github/workflows/v2-f7-frontier.yml").read_text()
        self.assertIn("aethercore.SharedSemanticDecodeSpec",f7)
        self.assertIn("aethercore.V2FoundationTypesSpec",f7)

if __name__ == "__main__":
    unittest.main()
