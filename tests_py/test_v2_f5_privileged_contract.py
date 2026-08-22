import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
FOUNDATION = ROOT / "src/main/scala/aethercore/core/v2/FoundationTypes.scala"
ROB = ROOT / "src/main/scala/aethercore/core/v2/TinyRobCommit.scala"
DEPENDENCY = ROOT / "src/main/scala/aethercore/core/v2/TinyDependency.scala"
PRIVILEGED = ROOT / "src/main/scala/aethercore/core/v2/TinyPrivileged.scala"


class V2F5PrivilegedContractTest(unittest.TestCase):
    def test_pending_effect_is_not_architectural_state(self):
        text = FOUNDATION.read_text()
        self.assertIn("class PendingPrivilegedEffect", text)
        self.assertIn("val privileged = new PendingPrivilegedEffect", text)
        self.assertIn("pending", text.lower())
        self.assertNotIn("MachineCsrFile", text)

    def test_rob_remains_full_identity_and_recovery_authority(self):
        text = ROB.read_text()
        self.assertIn("completionEntry.uop.robToken.generation === io.completion.bits.robToken.generation", text)
        self.assertIn("completionEntry.uop.producerTag.generation === io.completion.bits.producerTag.generation", text)
        self.assertIn("completionEntry.uop.valueRef.generation === io.completion.bits.valueRef.generation", text)
        self.assertIn("acceptedPrivilegedRecovery", text)
        self.assertIn("completionIndex === head", text)
        self.assertIn("systemTrapReturn", text)
        self.assertIn("val squashYounger = recoveryMatches || privilegedRecoveryMatches", text)
        self.assertIn("slotGenerations(index) := slotGenerations(index) + 1.U", text)

    def test_non_system_completion_cannot_smuggle_privileged_effect(self):
        text = ROB.read_text()
        self.assertIn("completionEntry.uop.executionClass === ExecutionClass.System", text)
        self.assertIn("0.U.asTypeOf(new PendingPrivilegedEffect(xlen))", text)

    def test_privileged_recovery_clears_speculative_dependency_state(self):
        text = DEPENDENCY.read_text()
        self.assertIn("val privilegedRecovery", text)
        self.assertIn("dependencyState.io.privilegedRecovery := rob.io.acceptedPrivilegedRecovery", text)
        self.assertIn("rename(register).valid := false.B", text)
        self.assertIn("dependencies(index).valid := false.B", text)
        self.assertIn("producers(index).valid := false.B", text)
        self.assertIn("val retiring = Valid(new RobRetirement(xlen))", text)

    def test_machine_csr_file_is_reused_only_at_retirement(self):
        text = PRIVILEGED.read_text()
        self.assertIn("new MachineCsrFile", text)
        self.assertIn("private val retiring = dependencyBackend.io.retiring", text)
        self.assertIn("csrFile.io.writeEnable := retiring.valid", text)
        self.assertIn("csrFile.io.trapEnter := trapAtRetire", text)
        self.assertIn("csrFile.io.trapReturn := returnAtRetire", text)
        self.assertNotIn("class V2CsrFile", text)

    def test_system_completion_is_side_effect_free(self):
        text = PRIVILEGED.read_text()
        before_backend = text.split("class TinyPrivilegedBackend", 1)[0]
        self.assertIn("class TinySystemCompletion", before_backend)
        self.assertNotIn("MachineCsrFile", before_backend)
        self.assertNotIn("writeEnable", before_backend)
        self.assertIn("io.completion.bits.privileged.csrWriteValid", before_backend)
        self.assertIn("io.completion.bits.privileged.trapReturn", before_backend)

    def test_redirects_are_retirement_owned_and_narrow(self):
        text = PRIVILEGED.read_text()
        self.assertIn("class PrivilegedRedirect", text)
        self.assertIn("val robToken = new RobToken", text)
        self.assertIn("val target = UInt(xlen.W)", text)
        self.assertIn("val kind = PrivilegedRedirectKind()", text)
        self.assertNotIn("ProducerTag", text.split("class PrivilegedRedirect", 1)[1].split("class TinySystemCompletion", 1)[0])
        self.assertNotIn("ValueRef", text.split("class PrivilegedRedirect", 1)[1].split("class TinySystemCompletion", 1)[0])
        self.assertIn("io.privilegedRedirect.valid := privilegedBoundary", text)
        self.assertIn("io.dispatch.ready := dependencyBackend.io.dispatch.ready && !privilegedBoundary", text)

    def test_f5_does_not_claim_deferred_owners(self):
        text = PRIVILEGED.read_text()
        # WFI/SFENCE are named only as deliberately unsupported semantics; F5
        # must not introduce their machinery or memory/general OoO structures.
        forbidden = [
            "IssueQueue",
            "ReservationStation",
            "FreeList",
            "PhysicalRegister",
            "LoadQueue",
            "StoreQueue",
            "MSHR",
            "AetherMemLink",
            "PageTableWalker",
            "TranslationTlb",
        ]
        for symbol in forbidden:
            self.assertNotIn(symbol, text)


if __name__ == "__main__":
    unittest.main()
