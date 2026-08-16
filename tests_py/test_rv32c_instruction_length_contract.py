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
        self.assertIn("fetchedInstBytes := parcel.io.instructionBytes", self.core)

    def test_architectural_next_pc_consumes_instruction_length(self):
        self.assertIn("memWb.pc + memWb.instBytes", self.core)
        self.assertIn("val idExNextPc = idEx.pc + idEx.instBytes", self.core)
        self.assertEqual(self.core.count("pc := memWb.pc + memWb.instBytes"), 2)
        self.assertEqual(self.core.count("pc := pc + fetchedInstBytes"), 2)
        self.assertNotIn("memWb.pc + 4.U", self.core)
        self.assertNotIn("idEx.pc + 4.U", self.core)
        self.assertNotIn("pc := pc + 4.U", self.core)

    def test_physical_transaction_width_stays_separate_from_architectural_length(self):
        self.assertIn("if (config.isa.hasC) 2.U(3.W) else 4.U(3.W)", self.core)
        self.assertIn("instructionPmp.io.bytes := instructionTransactionBytes", self.core)
        self.assertNotIn("instructionPmp.io.bytes := fetchedInstBytes", self.core)
        self.assertNotIn("io.imem.bytes := fetchedInstBytes", self.core)


if __name__ == "__main__":
    unittest.main()
