import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_regressions import build_programs


class DirectedRegressionImageTest(unittest.TestCase):
    def test_all_programs_resolve_to_nonempty_instruction_images(self) -> None:
        programs = build_programs()
        self.assertEqual(
            set(programs),
            {"forwarding", "load_use", "branch_flush", "jal_jalr"},
        )

        for name, (program, stall_period) in programs.items():
            with self.subTest(name=name):
                words = program.resolve()
                image = program.image()
                self.assertGreater(len(words), 0)
                self.assertEqual(len(image), len(words) * 4)
                self.assertNotIn(0, words, "an unresolved label fixup remained")
                self.assertGreaterEqual(stall_period, 0)

    def test_control_flow_fixups_emit_riscv_opcodes(self) -> None:
        programs = build_programs()
        branch_words = programs["branch_flush"][0].resolve()
        jump_words = programs["jal_jalr"][0].resolve()

        self.assertEqual(branch_words[3] & 0x7F, 0x63)
        self.assertEqual(jump_words[0] & 0x7F, 0x6F)
        self.assertIn(0x67, [word & 0x7F for word in jump_words])

    def test_load_use_regression_enables_deterministic_backpressure(self) -> None:
        self.assertEqual(build_programs()["load_use"][1], 3)


if __name__ == "__main__":
    unittest.main()
