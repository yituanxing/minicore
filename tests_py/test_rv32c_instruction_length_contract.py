from pathlib import Path
import unittest


class Rv32cInstructionLengthContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.core = Path("src/main/scala/aethercore/core/AetherCore.scala").read_text()

    def test_instruction_length_is_an_explicit_pipeline_fact(self):
        self.assertEqual(self.core.count("val instBytes = UInt(3.W)"), 4)
        self.assertIn("memWb.instBytes := exMem.instBytes", self.core)
        self.assertIn("exMem.instBytes := idEx.instBytes", self.core)
        self.assertIn("idEx.instBytes := ifId.instBytes", self.core)
        self.assertEqual(self.core.count("ifId.instBytes := fetchedInstBytes"), 2)

    def test_architectural_next_pc_consumes_instruction_length(self):
        self.assertIn("memWb.pc + memWb.instBytes", self.core)
        self.assertIn("val idExNextPc = idEx.pc + idEx.instBytes", self.core)
        self.assertEqual(self.core.count("pc := memWb.pc + memWb.instBytes"), 2)
        self.assertEqual(self.core.count("pc := pc + fetchedInstBytes"), 2)
        self.assertNotIn("memWb.pc + 4.U", self.core)
        self.assertNotIn("idEx.pc + 4.U", self.core)
        self.assertNotIn("pc := pc + 4.U", self.core)

    def test_fetch_transaction_is_not_mixed_with_architectural_length_yet(self):
        self.assertIn("val fetchedInstBytes = 4.U(3.W)", self.core)
        self.assertIn("instructionPmp.io.bytes := 4.U", self.core)
        self.assertNotIn("instructionPmp.io.bytes := fetchedInstBytes", self.core)


if __name__ == "__main__":
    unittest.main()
