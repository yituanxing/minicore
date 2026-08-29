from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32CDecompressorContract(unittest.TestCase):
    def test_decompressor_is_integrated_through_common_rvc_parcel_frontend(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()

        self.assertIn("class RvcDecompressor(val xlen: Int) extends Module", module)
        self.assertIn("require(Set(32, 64).contains(xlen)", module)
        self.assertIn("class Rv32CDecompressor extends RvcDecompressor(32)", module)

        self.assertIn("class RvcParcelController(val xlen: Int = 32) extends Module", parcel)
        self.assertIn("val decompressor = Module(new RvcDecompressor(xlen))", parcel)
        self.assertIn("class Rv32CParcelController(xlen: Int = 32) extends RvcParcelController(xlen)", parcel)

        self.assertIn("val compressedFetch = if (config.isa.hasC)", core)
        self.assertIn("Some(Module(new Rv32CParcelController(xlen)))", core)
        self.assertIn("Set('I', 'M', 'A', 'C')", config)
        self.assertNotIn("!isa.hasC || isa.xlen == 32", config)

    def test_cross_xlen_integer_only_boundary_stays_explicit(self):
        module = (ROOT / "src/main/scala/aethercore/core/Rv32CDecompressor.scala").read_text()

        self.assertIn("RV32C C.JAL", module)
        self.assertIn("RV64C C.ADDIW", module)
        self.assertIn("RV64C C.LD", module)
        self.assertIn("RV64C C.SD", module)
        self.assertIn("C.SUBW", module)
        self.assertIn("C.ADDW", module)
        self.assertIn("RV64 uses c[12] as shamt[5]", module)

        self.assertIn("unsupported C.FLW", module)
        self.assertIn("unsupported C.FSW", module)
        self.assertIn("The remaining quadrant-0 standard encodings require F/D", module)
        self.assertIn("Remaining quadrant-2 standard encodings require F/D", module)
        self.assertIn("Quadrant 3 denotes instructions wider than 16 bits", module)


if __name__ == "__main__":
    unittest.main()
