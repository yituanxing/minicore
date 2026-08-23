import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
F1 = ROOT / "src/main/scala/aethercore/core/v2/TinyRobCommit.scala"
WIDTH_SPEC = ROOT / "src/test/scala/aethercore/DatapathWidthSpec.scala"


class V2F1RobCommitContractTest(unittest.TestCase):
    def test_f1_uses_fixed_tiny_geometry_not_a_public_rob_generator(self) -> None:
        text = F1.read_text(encoding="utf-8")
        self.assertIn("val Entries: Int = 4", text)
        self.assertIn("val IndexBits: Int = 2", text)
        self.assertIn("val GenerationBits: Int = 8", text)
        self.assertIn("val GenerationReuseBudget: Int = 1 << GenerationBits", text)
        self.assertNotRegex(text, r"class TinyRob\([^)]*robEntries")
        self.assertNotIn("MicroArchConfig", text)
        self.assertNotIn("ArchProfile", text)

    def test_allocation_is_the_only_initial_identity_owner(self) -> None:
        text = F1.read_text(encoding="utf-8")
        dispatch_match = re.search(r"class RobDispatch.*?\n}\n", text, re.DOTALL)
        self.assertIsNotNone(dispatch_match)
        dispatch = dispatch_match.group(0)
        self.assertNotIn("RobToken", dispatch)
        self.assertNotIn("ProducerTag", dispatch)
        self.assertNotIn("ValueRef", dispatch)
        self.assertIn("io.allocated.bits.robToken.index := tail", text)
        self.assertIn("io.allocated.bits.producerTag.id := tail", text)
        self.assertIn("io.allocated.bits.valueRef.id := tail", text)

    def test_commit_reuses_existing_architectural_truth_surfaces(self) -> None:
        text = F1.read_text(encoding="utf-8")
        self.assertIn("new CommitTrace", text)
        self.assertIn("new RegisterFile(xlen)", text)
        self.assertNotIn("V2CommitTrace", text)
        self.assertIn("decoded.rd =/= 0.U", text)
        self.assertIn("!exception.valid", text)
        self.assertIn("io.commit.memValid := false.B", text)
        self.assertIn("io.commit.interrupt := false.B", text)

    def test_f1_contains_no_five_stage_or_future_ooo_mechanism_ownership(self) -> None:
        text = F1.read_text(encoding="utf-8")
        for forbidden in (
            "IfId",
            "IdEx",
            "ExMem",
            "MemWb",
            "FreeList",
            "PhysicalRegister",
            "BranchMask",
            "LoadQueue",
            "StoreQueue",
            "MSHR",
            "AXI",
            "TileLink",
        ):
            self.assertNotIn(forbidden, text)

    def test_cross_xlen_fast_smoke_executes_real_f1_lifetime(self) -> None:
        text = WIDTH_SPEC.read_text(encoding="utf-8")
        self.assertIn("new TinyRobCommitBackend(xlen)", text)
        self.assertIn("for (xlen <- Seq(32, 64))", text)
        self.assertIn("dut.io.allocated.bits.robToken.index.peek()", text)
        self.assertIn("dut.io.commit.rdWrite.expect(true.B)", text)
        self.assertIn("dut.io.occupancy.expect(0.U)", text)


if __name__ == "__main__":
    unittest.main()
