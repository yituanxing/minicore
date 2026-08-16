from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32CDecompressorContract(unittest.TestCase):
    def test_decompressor_is_integrated_through_rv32c_parcel_frontend(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()
        self.assertIn("class Rv32CDecompressor extends Module", module)
        self.assertIn("val decompressor = Module(new Rv32CDecompressor)", parcel)
        self.assertIn("val compressedFetch = if (config.isa.hasC)", core)
        self.assertIn("Set('I', 'M', 'A', 'C')", config)
        self.assertIn("!isa.hasC || isa.xlen == 32", config)

    def test_integer_only_boundary_stays_explicit(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()
        self.assertIn("Other quadrant-0 standard encodings require F/D", module)
        self.assertIn("Other quadrant-2 standard encodings require F/D", module)
        self.assertIn("Quadrant 3 denotes instructions wider than 16 bits", module)


if __name__ == "__main__":
    unittest.main()
