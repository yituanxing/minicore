import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLASSIFIER = ROOT / "src/main/scala/aethercore/core/v2/TinyBackendClassifier.scala"
WRAPPER = ROOT / "src/main/scala/aethercore/core/v2/TinySemanticDecode.scala"
ARCH_DECODE = ROOT / "src/main/scala/aethercore/core/v2/TinyArchitecturalSemanticDecode.scala"


class V2DecodeUopBoundarySourceContract(unittest.TestCase):
    def test_backend_classifier_is_encoding_blind_by_interface(self):
        source = CLASSIFIER.read_text(encoding="utf-8")

        # Backend classification must be impossible to implement by re-decoding
        # instruction encoding. Architectural decode owns those bits.
        self.assertNotIn("DecodedInstruction", source)
        self.assertNotIn("RobDispatch", source)
        self.assertNotIn("rawInst", source)
        self.assertNotIn("opcode", source)
        self.assertNotIn("funct", source)
        self.assertNotIn("io.inst", source)

        for semantic_input in (
            "val aluOp = Input(AluOp())",
            "val systemKind = Input(SystemOperationKind())",
            "val memoryKind = Input(MemoryOperationKind())",
            "val controlFlowKind = Input(ControlFlowKind())",
            "val writesRd = Input(Bool())",
            "val rd = Input(UInt(5.W))",
            "val exceptionValid = Input(Bool())",
        ):
            self.assertIn(semantic_input, source)

    def test_compatibility_wrapper_is_the_only_composition_owner(self):
        source = WRAPPER.read_text(encoding="utf-8")
        self.assertIn("new TinyArchitecturalSemanticDecode(isa)", source)
        self.assertIn("new TinyBackendClassifier", source)
        self.assertIn("io.dispatch.decoded := decoded", source)
        self.assertIn(
            "io.dispatch.executionClass := backendClassifier.io.executionClass", source
        )
        self.assertIn(
            "io.dispatch.producesValue := backendClassifier.io.producesValue", source
        )

    def test_architectural_decode_owns_encoding_evidence(self):
        source = ARCH_DECODE.read_text(encoding="utf-8")
        self.assertIn("val inst = Input(UInt(32.W))", source)
        self.assertIn("val rawInst = Input(UInt(32.W))", source)
        self.assertIn("val decoded = Output(new DecodedInstruction(xlen))", source)
        self.assertNotIn("ExecutionClass", source)
        self.assertNotIn("producesValue", source)


if __name__ == "__main__":
    unittest.main()
