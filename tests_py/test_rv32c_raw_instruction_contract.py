from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cRawInstructionContract(unittest.TestCase):
    def test_pipeline_preserves_raw_alongside_execution_instruction(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertEqual(core.count("val rawInst = UInt(32.W)"), 4)
        self.assertEqual(core.count("ifId.rawInst := io.imem.inst"), 2)
        self.assertIn("idEx.rawInst := ifId.rawInst", core)
        self.assertIn("exMem.rawInst := idEx.rawInst", core)
        self.assertIn("memWb.rawInst := exMem.rawInst", core)
        self.assertIn("io.commit.rawInst := memWb.rawInst", core)

    def test_trap_values_use_raw_encoding(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("if (xlen == 32) ifId.rawInst", core)
        self.assertIn("if (xlen == 32) idEx.rawInst", core)

    def test_difftest_separates_image_bits_from_execution_semantics(self):
        header = (ROOT / "sim/nemu_difftest.h").read_text()
        generic = (ROOT / "sim/nemu_difftest.cpp").read_text()
        rv32 = (ROOT / "sim/nemu_difftest_rv32.cpp").read_text()
        self.assertIn("std::uint32_t rawInst = 0", header)
        self.assertIn("imageInst != commit.rawInst", generic)
        self.assertIn("imageInst != commit.rawInst", rv32)
        self.assertIn("isZicsrInstruction(commit.inst)", rv32)
        self.assertIn("explicitMemoryAddress(before, commit.inst)", rv32)
        self.assertIn("instructionAt(pc, commit.instBytes) != commit.rawInst", rv32)
        self.assertIn("instruction != commit.rawInst || value != commit.rawInst", rv32)
        self.assertNotIn("instruction != commit.inst || value != commit.rawInst", rv32)

    def test_current_fetch_keeps_raw_and_execution_encodings_identical(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertEqual(core.count("ifId.inst := io.imem.inst"), 2)
        self.assertEqual(core.count("ifId.rawInst := io.imem.inst"), 2)


if __name__ == "__main__":
    unittest.main()
