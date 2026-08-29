import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
STATUS = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryLifetimeStatus.scala"
LSU = ROOT / "src/main/scala/aethercore/core/v2/TinyBlockingLsu.scala"
BACKEND = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryBackend.scala"
STAGES = ROOT / "src/test/scala/aethercore/V2StageSpecs.scala"


class V2P84MemoryLifetimeStatusSourceContract(unittest.TestCase):
    def test_status_is_fact_only_and_has_no_issue_policy(self):
        source = STATUS.read_text(encoding="utf-8")
        self.assertIn("class TinyMemoryLifetimeStatus(", source)
        for field in (
            "val valid = Bool()",
            "val drained = Bool()",
            "val robToken = new RobToken",
            "val kind = MemoryOperationKind()",
            "val atomicOp = AtomicOp()",
            "val size = MemSize()",
            "val effectiveAddress = UInt(xlen.W)",
            "val writeLike = Bool()",
            "val physicalAddressValid = Bool()",
            "val physicalAddress = UInt(paddrBits.W)",
            "val attributesValid = Bool()",
            "val attributes = new MemoryAttributes",
            "val writePermitMatched = Bool()",
            "val physicalRequestIssued = Bool()",
            "val completionPending = Bool()",
        ):
            self.assertIn(field, source)

        lowered = source.lower()
        for policy_name in ("mayissue", "canbypass", "canspeculate", "youngerload"):
            self.assertNotIn(policy_name, lowered)

    def test_lsu_status_tracks_flow_through_and_externalized_lifetime(self):
        source = LSU.read_text(encoding="utf-8")
        self.assertIn("val lifetimeStatus = Output(", source)
        self.assertIn("val workingValid = busy || io.request.fire", source)
        self.assertIn("io.lifetimeStatus.drained := !workingValid", source)
        self.assertIn("io.lifetimeStatus.writeLike := accessNeedsWritePermission", source)
        self.assertIn("io.lifetimeStatus.physicalAddressValid := adapter.io.dataValid", source)
        self.assertIn("io.lifetimeStatus.attributesValid := adapter.io.dataValid", source)
        self.assertIn("io.lifetimeStatus.writePermitMatched := accessNeedsWritePermission && permitMatches", source)
        self.assertIn(
            "io.lifetimeStatus.physicalRequestIssued := physicalIssued || io.memoryRequest.fire",
            source,
        )
        self.assertIn("io.lifetimeStatus.completionPending := io.completion.valid", source)

    def test_exact_head_store_visibility_and_one_outstanding_rules_are_unchanged(self):
        source = LSU.read_text(encoding="utf-8")
        self.assertIn(
            "val permitMatches = io.storePermit.valid && sameRobToken(io.storePermit.bits, workingRequest.robToken)",
            source,
        )
        self.assertIn("val writeMayExternalize = !accessNeedsWritePermission || permitMatches", source)
        self.assertIn("!localScFailure && !physicalIssued && writeMayExternalize && !completionHeldValid", source)
        self.assertIn("when(io.memoryRequest.fire) {", source)
        self.assertIn("physicalIssued := true.B", source)
        self.assertIn("when(io.completion.fire) {", source)
        self.assertIn("physicalIssued := false.B", source)

    def test_backend_does_not_consume_m1_status_for_scheduling(self):
        source = BACKEND.read_text(encoding="utf-8")
        self.assertNotIn("lifetimeStatus", source)
        self.assertIn("lsu.io.request.valid := headIsMemory &&", source)
        self.assertIn("dependencyBackend.io.headDependenciesValid &&", source)
        self.assertIn("dependencyBackend.io.headOperandsReady &&", source)
        self.assertIn("!memoryAlreadyIssued", source)
        self.assertIn("sameRobToken(memoryIssuedToken, head.bits.robToken)", source)
        self.assertIn("lsu.io.storePermit.valid := headIsMemory && (", source)
        self.assertIn("lsu.io.storePermit.bits := head.bits.robToken", source)
        self.assertIn("assert(PopCount(Cat(", source)
        self.assertIn("branchIssue.io.request.fire", source)
        self.assertIn("selectiveIssue.io.request.fire", source)
        self.assertIn("lsu.io.request.fire", source)
        self.assertIn(
            ")) <= 1.U, \"A8 selective backend must remain single-issue per cycle\")",
            source,
        )

    def test_dynamic_status_checks_are_part_of_the_f6_stage_gate(self):
        source = STAGES.read_text(encoding="utf-8")
        self.assertIn("with V2P8MemoryLifetimeStatusChecks", source)


if __name__ == "__main__":
    unittest.main()
