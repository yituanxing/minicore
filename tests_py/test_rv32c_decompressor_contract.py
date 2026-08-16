from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32CDecompressorContract(unittest.TestCase):
    def test_decompressor_is_standalone_and_c_remains_fail_closed(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()

        self.assertIn("class Rv32CDecompressor extends Module", module)
        self.assertIn("val raw = Input(UInt(16.W))", module)
        self.assertIn("val expanded = Output(UInt(32.W))", module)
        self.assertIn("val legal = Output(Bool())", module)
        self.assertNotIn("Rv32CDecompressor", core)
        self.assertIn("val instructionExtensions: Set[Char] = Set('I', 'M', 'A')", config)

    def test_integer_only_boundary_stays_explicit(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()
        self.assertIn("Other quadrant-0 standard encodings require F/D", module)
        self.assertIn("Other quadrant-2 standard encodings require F/D", module)
        self.assertIn("Quadrant 3 denotes instructions wider than 16 bits", module)


if __name__ == "__main__":
    unittest.main()
