import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_completion_regressions import build_programs


class CompletionRegressionImageTest(unittest.TestCase):
    def test_completion_programs_resolve(self) -> None:
        programs = build_programs()
        self.assertEqual(set(programs), {"alu_logic", "pc_relative", "fence_retire"})

        for name, program in programs.items():
            with self.subTest(name=name):
                words = program.resolve()
                self.assertGreater(len(words), 0)
                self.assertEqual(len(program.image()), len(words) * 4)
                self.assertNotIn(0, words, "an unresolved branch/jump fixup remained")

    def test_alu_program_contains_remaining_opcode_classes(self) -> None:
        words = build_programs()["alu_logic"].resolve()
        opcodes = {word & 0x7F for word in words}
        funct3_values = {(word >> 12) & 0x7 for word in words if (word & 0x7F) in {0x13, 0x33}}

        self.assertTrue({0x13, 0x33}.issubset(opcodes))
        self.assertTrue({1, 2, 3, 4, 5, 6, 7}.issubset(funct3_values))

    def test_pc_program_contains_auipc_jal_and_jalr(self) -> None:
        words = build_programs()["pc_relative"].resolve()
        opcodes = {word & 0x7F for word in words}
        self.assertTrue({0x17, 0x37, 0x67, 0x6F}.issubset(opcodes))

    def test_fence_program_contains_fence_and_fence_i(self) -> None:
        words = build_programs()["fence_retire"].resolve()
        self.assertIn(0x0000000F, words)
        self.assertIn(0x0000100F, words)


if __name__ == "__main__":
    unittest.main()
