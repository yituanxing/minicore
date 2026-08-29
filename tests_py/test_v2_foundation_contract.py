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
            "object OperandSourceKind",
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
        self.assertIn("lhsSource", decoded_body)
        self.assertIn("rhsSource", decoded_body)
        self.assertIn("controlFlow", decoded_body)
        self.assertIn("memory", decoded_body)
        self.assertIn("system", decoded_body)
        self.assertIn("ordering", decoded_body)
        self.assertIn("exception", decoded_body)

    def test_execution_operand_sources_are_semantic_not_v1_mux_controls(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")
        source_body = text.split("object OperandSourceKind", 1)[1].split("object ControlFlowKind", 1)[0]
        decoded_body = text.split("class DecodedInstruction", 1)[1].split("/** First backend-owned representation", 1)[0]

        for source in ("Zero", "Rs1", "Rs2", "Pc", "Immediate"):
            self.assertIn(source, source_body)
        self.assertIn("val lhsSource = OperandSourceKind()", decoded_body)
        self.assertIn("val rhsSource = OperandSourceKind()", decoded_body)
        self.assertNotIn("OpASel", decoded_body)
        self.assertNotIn("OpBSel", decoded_body)

    def test_rv64_word_operation_semantics_survive_decode_and_execute_seams(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")
        decoded_body = text.split("class DecodedInstruction", 1)[1].split("/** First backend-owned representation", 1)[0]
        request_body = text.split("class ExecutionRequest", 1)[1].split("/** Tagged completion", 1)[0]

        self.assertIn("val wordOp = Bool()", decoded_body)
        self.assertIn("val wordOp = Bool()", request_body)
        self.assertIn("RV64 *W", decoded_body)

    def test_compressed_link_length_survives_the_execution_request_seam(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")
        request_body = text.split("class ExecutionRequest", 1)[1].split("/** Tagged completion", 1)[0]

        self.assertIn("val pc = UInt(xlen.W)", request_body)
        self.assertIn("val instBytes = UInt(3.W)", request_body)
        self.assertIn("compressed jumps", request_body)
        self.assertNotIn("PcPlus4", request_body)

    def test_csr_immediate_is_explicit_semantics_not_reinterpreted_rs1(self) -> None:
        text = FOUNDATION.read_text(encoding="utf-8")
        system_body = text.split("class DecodedSystemOperation", 1)[1].split("/** Architectural instruction semantics", 1)[0]

        self.assertIn("val csrUseImmediate = Bool()", system_body)
        self.assertIn("val csrImmediate = UInt(5.W)", system_body)
        self.assertIn("five-bit zimm", system_body)

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
