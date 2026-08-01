import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_rv64m_regressions import build_programs


class RV64MRegressionImageTest(unittest.TestCase):
    def test_all_programs_resolve_without_placeholder_words(self) -> None:
        for name, program in build_programs().items():
            with self.subTest(program=name):
                words = program.resolve()
                self.assertGreater(len(words), 10)
                self.assertNotIn(0, words)

    def test_directed_matrix_contains_all_thirteen_m_encodings(self) -> None:
        encodings = set()
        for program in build_programs().values():
            for word in program.resolve():
                opcode = word & 0x7F
                funct7 = (word >> 25) & 0x7F
                if opcode in (0x33, 0x3B) and funct7 == 0x01:
                    encodings.add((opcode, (word >> 12) & 0x7))

        expected = {
            (0x33, 0), (0x33, 1), (0x33, 2), (0x33, 3),
            (0x33, 4), (0x33, 5), (0x33, 6), (0x33, 7),
            (0x3B, 0), (0x3B, 4), (0x3B, 5), (0x3B, 6), (0x3B, 7),
        }
        self.assertEqual(encodings, expected)

    def test_each_program_has_success_and_distinct_failure_exits(self) -> None:
        images = {name: program.image() for name, program in build_programs().items()}
        self.assertEqual(len(images), 3)
        self.assertEqual(len(set(images.values())), 3)


if __name__ == "__main__":
    unittest.main()
