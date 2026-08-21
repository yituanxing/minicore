from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
FOUNDATION = ROOT / "src/main/scala/aethercore/core/v2/FoundationTypes.scala"
MEM_LINK = ROOT / "src/main/scala/aethercore/memory/AetherMemLink.scala"


class V2FoundationContractTest(unittest.TestCase):
    def test_foundation_separates_semantics_uops_and_lifetime_identities(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")

        for required in (
            "class DecodedInstruction",
            "class BackendUop",
            "class RobToken",
            "class ProducerTag",
            "class ValueRef",
            "object OrderingClass",
            "class ExecutionRequest",
            "class ExecutionResponse",
        ):
            self.assertIn(required, text)

        self.assertIn("val decoded = new DecodedInstruction", text)
        self.assertIn("val robToken = new RobToken", text)
        self.assertIn("val producerTag = new ProducerTag", text)
        self.assertIn("val valueRef = new ValueRef", text)
        self.assertIn("val generation", text)

    def test_semantic_decode_contract_does_not_leak_v1_frontend_or_backend_implementation_controls(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")
        decoded_body = text.split("class DecodedInstruction", 1)[1].split("/** First backend-owned representation", 1)[0]
        backend_body = text.split("class BackendUop", 1)[1].split("/** Narrow request contract", 1)[0]

        for forbidden in (
            "IfId",
            "IdEx",
            "ExMem",
            "MemWb",
            "WbSel",
            "OpASel",
            "OpBSel",
            "forwarding",
            "pipelineStages",
            "physicalReg",
            "branchMask",
            "predictedNextPc",
            "predictionMeta",
            "ldq",
            "stq",
            "mshrId",
        ):
            self.assertNotIn(forbidden, decoded_body)

        self.assertNotIn("executionClass", decoded_body)
        self.assertIn("executionClass", backend_body)
        self.assertIn("Architectural instruction semantics after decode", text)
        self.assertIn("controlFlow", decoded_body)
        self.assertIn("memory", decoded_body)
        self.assertIn("system", decoded_body)
        self.assertIn("ordering", decoded_body)
        self.assertIn("exception", decoded_body)

    def test_existing_isa_semantic_enums_are_reused_instead_of_forked(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")

        self.assertIn("AluOp", text)
        self.assertIn("AtomicOp", text)
        self.assertIn("BranchType", text)
        self.assertIn("CsrOp", text)
        self.assertIn("MemSize", text)
        self.assertIn("XRetOp", text)
        self.assertNotIn("object V2AluOp", text)
        self.assertNotIn("object V2MemSize", text)

    def test_internal_memory_link_has_transaction_identity_without_soc_protocol_leakage(self) -> None:
        text = MEM_LINK.read_text(encoding="utf-8")

        self.assertIn("class AetherMemRequest", text)
        self.assertIn("class AetherMemResponse", text)
        self.assertIn("val txnId", text)
        self.assertIn("class MemoryAttributes", text)
        self.assertIn("cacheable", text)
        self.assertIn("sideEffecting", text)
        self.assertIn("ordered", text)

        for forbidden in ("AXI4", "TileLink", "UART", "PLIC", "DDR"):
            self.assertNotIn(forbidden, text)

    def test_foundation_does_not_create_a_new_arch_profile_or_microarch_generator(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8") + MEM_LINK.read_text(encoding="utf-8")

        self.assertNotIn("ArchProfile", text)
        self.assertNotIn("MicroArchConfig", text)
        self.assertNotIn("robEntries", text)
        self.assertNotIn("issueEntries", text)
        self.assertNotIn("fetchWidth", text)
        self.assertNotIn("commitWidth", text)


if __name__ == "__main__":
    unittest.main()
