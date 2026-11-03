"""Product CPU configuration axes are independent; target != implementation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/scala/aethercore/core/product/ProductCoreConfig.scala"
TESTS = ROOT / "src/test/scala/aethercore/ProductCoreConfigSpec.scala"

class ProductCoreConfigContract(unittest.TestCase):
    def test_geometry_is_single_type_with_three_named_presets(self):
        s = SOURCE.read_text()
        self.assertEqual(s.count("final case class ProductCoreGeometry"), 1)
        for n in ("smallGeometry", "mediumGeometry", "largeGeometry"):
            self.assertIn("val " + n + " = ProductCoreGeometry(", s)
        for n in ("robEntries", "issueWidth", "retireWidth", "integerPhysicalRegisters",
                  "integerReadPorts", "integerWritePorts", "loadQueueEntries", "storeQueueEntries"):
            self.assertIn(n + ":", s)
        self.assertIn("freeRegisterPressureBeforeFullRob", s)

    def test_distro_is_independent_of_small_and_cannot_claim_rtl(self):
        s = SOURCE.read_text()
        for a in ("AbiProfiles.lp64", "AbiProfiles.lp64d",
                  "val smallDebianTarget", "val largeDebianTarget",
                  "extensions = Set('I', 'M', 'A', 'F', 'D', 'C')",
                  "rtlImplemented: Boolean = false", "SoftwareTarget"):
            self.assertIn(a, s)
        self.assertNotIn("import aethercore.core.v2", s)
        self.assertNotIn("new TinyRob", s)
        self.assertTrue(TESTS.is_file())

    def test_chisel_gate_exercises_same_source_config(self):
        workflow = (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text()
        self.assertIn("aethercore.ProductCoreConfigSpec", workflow)
        self.assertIn("src/main/scala/aethercore/core/product/**", workflow)

if __name__ == "__main__":
    unittest.main()
