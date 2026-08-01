import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOFTWARE = ROOT / "software" / "compiled"
MASK32 = (1 << 32) - 1
MASK64 = (1 << 64) - 1


class CompiledWorkloadSourceTest(unittest.TestCase):
    def test_startup_sets_stack_clears_bss_and_exits_through_mmio(self) -> None:
        startup = (SOFTWARE / "crt0.S").read_text(encoding="utf-8")
        self.assertIn("la sp, __stack_top", startup)
        self.assertIn("la t0, __bss_start", startup)
        self.assertIn("la t1, __bss_end", startup)
        self.assertIn("li t0, 0x10000008", startup)
        self.assertIn("sd a0, 0(t0)", startup)

    def test_linker_uses_the_simulator_ram_base_and_a_separate_stack(self) -> None:
        linker = (SOFTWARE / "linker.ld").read_text(encoding="utf-8")
        self.assertIn("ORIGIN = 0x80000000", linker)
        self.assertIn("LENGTH = 64M", linker)
        self.assertIn("__stack_top = ORIGIN(RAM) + 0x00100000", linker)
        self.assertIn("__bss_start", linker)
        self.assertIn("__bss_end", linker)

    def test_matrix_contains_real_programs_and_optimization_variants(self) -> None:
        builder = (ROOT / "tools" / "build_compiled_workloads.sh").read_text(
            encoding="utf-8"
        )
        for entry in (
            '"call_stack call_stack O2 0"',
            '"memory memory O2 4"',
            '"arithmetic arithmetic O2 3"',
            '"sort_O0 sort O0 3"',
            '"sort_O2 sort O2 0"',
            '"sort_Os sort Os 5"',
            '"crc_hash_O0 crc_hash O0 4"',
            '"crc_hash_O2 crc_hash O2 0"',
            '"crc_hash_Os crc_hash Os 7"',
            '"mixed_integer_O0 mixed_integer O0 5"',
            '"mixed_integer_O2 mixed_integer O2 3"',
            '"mixed_integer_Os mixed_integer Os 0"',
        ):
            self.assertIn(entry, builder)

        self.assertIn("-march=rv64im", builder)
        self.assertIn("-mabi=lp64", builder)
        self.assertIn("-ffreestanding", builder)
        self.assertIn("-fno-tree-loop-distribute-patterns", builder)
        self.assertIn("-nostdlib", builder)

        for name in (
            "call_stack",
            "memory",
            "arithmetic",
            "sort",
            "crc_hash",
            "mixed_integer",
        ):
            source = (SOFTWARE / f"{name}.c").read_text(encoding="utf-8")
            self.assertIn("int main(void)", source)
            self.assertIn("return 0;", source)

    def test_crc_constants_match_host_reference(self) -> None:
        state = 0x12345678
        data = []
        for index in range(257):
            state ^= (state << 13) & MASK32
            state ^= state >> 17
            state ^= (state << 5) & MASK32
            state &= MASK32
            data.append((state ^ (index * 29)) & 0xFF)

        crc = MASK32
        for byte in data:
            crc ^= byte
            for _ in range(8):
                crc = ((crc >> 1) ^ (0xEDB88320 if crc & 1 else 0)) & MASK32
        crc ^= MASK32

        fnv = 0xCBF29CE484222325
        mixed = 0x9E3779B97F4A7C15
        for index, byte in enumerate(data):
            fnv = ((fnv ^ byte) * 0x100000001B3) & MASK64
            mixed ^= (byte + index * 0x10001) & MASK64
            mixed = ((mixed << 7) | (mixed >> 57)) & MASK64
            mixed = (mixed * 0x2545F4914F6CDD1D) & MASK64

        self.assertEqual(data[:3], [0xA5, 0xBE, 0xFE])
        self.assertEqual(data[-1], 0xE4)
        self.assertEqual(crc, 0x8C054D91)
        self.assertEqual(fnv, 0x57A5F31C0B3B7B6A)
        self.assertEqual(mixed, 0x8DE6D956CB70C08D)

    def test_mixed_integer_constants_match_host_reference(self) -> None:
        state = 0x31415926
        size = 7
        left = [[0] * size for _ in range(size)]
        right = [[0] * size for _ in range(size)]
        for row in range(size):
            for column in range(size):
                state = (state * 1664525 + 1013904223) & MASK32
                left[row][column] = ((state >> 16) % 201) - 100
                state = (state * 1664525 + 1013904223) & MASK32
                right[row][column] = ((state >> 16) % 201) - 100

        result = [
            [
                sum(left[row][k] * right[k][column] for k in range(size))
                for column in range(size)
            ]
            for row in range(size)
        ]
        self.assertEqual(result[0][0], -10501)
        self.assertEqual(result[0][1], -12923)
        self.assertEqual(result[0][3], 23294)
        self.assertEqual(result[6][6], -24811)

        acc = 0xCBF29CE484222325
        for row in range(size):
            for column in range(size):
                index = row * size + column
                acc ^= ((result[row][column] & MASK64) + index * 0x9E37) & MASK64
                acc = (acc * 0x100000001B3) & MASK64
        self.assertEqual(acc, 0xF4F31BDC65BFCC5E)

        for index in range(1, 98):
            magnitude = ((acc ^ (index * 0x9E3779B97F4A7C15)) & MASK64) >> 1
            value = -magnitude if index & 1 else magnitude
            divisor = index % 17 + 1
            quotient = abs(value) // divisor
            if value < 0:
                quotient = -quotient
            remainder = value - quotient * divisor
            acc ^= quotient & MASK64
            acc = (acc + (remainder & MASK64) * 0x100000001B3) & MASK64
            acc = ((acc << 9) | (acc >> 55)) & MASK64
        self.assertEqual(acc, 0xDD824284D6D2042A)


if __name__ == "__main__":
    unittest.main()
