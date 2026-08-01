import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_generated_difftest import CASES, DATA_BASE_OFFSET, build_program


class GeneratedDifftestImageTest(unittest.TestCase):
    def test_fixed_seeds_are_reproducible_and_distinct(self) -> None:
        images = []
        for case in CASES:
            first = build_program(case).image()
            second = build_program(case).image()
            self.assertEqual(first, second, f"seed {case.seed:#x} was not reproducible")
            images.append(first)

        self.assertEqual(len(set(images)), len(images), "different seeds produced duplicate images")

    def test_generated_images_stay_before_the_data_scratch(self) -> None:
        for case in CASES:
            with self.subTest(case=case.name):
                program = build_program(case)
                words = program.resolve()
                self.assertGreater(len(words), case.operations)
                self.assertLess(len(words) * 4, DATA_BASE_OFFSET)
                self.assertNotIn(0, words, "an unresolved fixup or zero instruction remained")

    def test_generated_streams_cover_alu_word_memory_and_fence_families(self) -> None:
        all_words = [word for case in CASES for word in build_program(case).resolve()]
        opcodes = {word & 0x7F for word in all_words}

        self.assertTrue({0x03, 0x0F, 0x13, 0x1B, 0x23, 0x33, 0x3B}.issubset(opcodes))
        self.assertGreaterEqual(sum((word & 0x7F) == 0x03 for word in all_words), 20)
        self.assertGreaterEqual(sum((word & 0x7F) == 0x23 for word in all_words), 20)

    def test_backpressure_variants_are_part_of_the_fixed_matrix(self) -> None:
        stalls = {case.stall_period for case in CASES}
        self.assertIn(0, stalls)
        self.assertTrue({3, 4, 5, 7}.issubset(stalls))


if __name__ == "__main__":
    unittest.main()
