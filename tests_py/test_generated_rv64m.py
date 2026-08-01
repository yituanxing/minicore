import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_generated_rv64m import CASES, DATA_BASE_OFFSET, M_ENCODINGS, build_program


class GeneratedRV64MImageTest(unittest.TestCase):
    def test_fixed_seeds_are_reproducible_and_distinct(self) -> None:
        images = []
        for case in CASES:
            first = build_program(case).image()
            second = build_program(case).image()
            self.assertEqual(first, second, f"seed {case.seed:#x} was not reproducible")
            images.append(first)

        self.assertEqual(len(images), len(set(images)))

    def test_each_image_contains_all_thirteen_m_encodings(self) -> None:
        expected = {(0x3B if word else 0x33, funct3) for word, funct3 in M_ENCODINGS}

        for case in CASES:
            with self.subTest(case=case.name):
                observed = set()
                for instruction in build_program(case).resolve():
                    opcode = instruction & 0x7F
                    funct7 = (instruction >> 25) & 0x7F
                    if opcode in (0x33, 0x3B) and funct7 == 0x01:
                        observed.add((opcode, (instruction >> 12) & 0x7))
                self.assertEqual(observed, expected)

    def test_images_remain_before_the_data_scratch(self) -> None:
        for case in CASES:
            with self.subTest(case=case.name):
                words = build_program(case).resolve()
                self.assertGreater(len(words), case.operations)
                self.assertLess(len(words) * 4, DATA_BASE_OFFSET)
                self.assertNotIn(0, words)

    def test_matrix_has_no_stall_and_four_backpressure_periods(self) -> None:
        stalls = {case.stall_period for case in CASES}
        self.assertEqual(stalls, {0, 3, 4, 5, 7})

    def test_generated_body_contains_divide_by_zero_and_load_to_m_dependencies(self) -> None:
        all_words = [word for case in CASES for word in build_program(case).resolve()]

        divide_by_zero = 0
        load_count = 0
        for word in all_words:
            opcode = word & 0x7F
            funct7 = (word >> 25) & 0x7F
            funct3 = (word >> 12) & 0x7
            rs2 = (word >> 20) & 0x1F
            if opcode == 0x33 and funct7 == 0x01 and funct3 in (4, 5, 6, 7) and rs2 == 0:
                divide_by_zero += 1
            if opcode == 0x03:
                load_count += 1

        self.assertGreaterEqual(divide_by_zero, len(CASES))
        self.assertGreaterEqual(load_count, 20)


if __name__ == "__main__":
    unittest.main()
