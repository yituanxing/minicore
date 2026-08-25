import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE_CONFIG = ROOT / "src/main/scala/aethercore/config/CoreConfig.scala"
ABI_CONFIG = ROOT / "src/main/scala/aethercore/config/AbiConfig.scala"
MAIN_SCALA = ROOT / "src/main/scala"


class V2AbiProfileOwnershipContract(unittest.TestCase):
    def test_isa_config_owns_march_but_not_mabi(self):
        source = CORE_CONFIG.read_text(encoding="utf-8")
        isa_section = source.split("final case class PlatformConfig", 1)[0]
        self.assertIn("val march: String", isa_section)
        self.assertNotIn("mabi", isa_section)
        self.assertIn("Software ABI selection lives in AbiConfig/SoftwareTarget", isa_section)

    def test_abi_config_is_an_independent_compatibility_owner(self):
        source = ABI_CONFIG.read_text(encoding="utf-8")
        self.assertIn("final case class AbiConfig(", source)
        self.assertIn("requiredExtensions: Set[Char]", source)
        self.assertIn("def validateAgainst(isa: IsaConfig)", source)
        self.assertIn("isa.xlen == xlen", source)
        self.assertIn("requiredExtensions.subsetOf(isa.extensions)", source)
        for abi in ("ilp32", "ilp32f", "ilp32d", "lp64", "lp64f", "lp64d"):
            self.assertIn(f'AbiConfig("{abi}"', source)

    def test_software_target_combines_isa_and_abi_without_reowning_core_capability(self):
        source = ABI_CONFIG.read_text(encoding="utf-8")
        self.assertIn("final case class SoftwareTarget(", source)
        self.assertIn("isa: IsaConfig", source)
        self.assertIn("abi: AbiConfig", source)
        self.assertIn("abi.validateAgainst(isa)", source)
        self.assertIn("val march: String = isa.march", source)
        self.assertIn("val mabi: String = abi.name", source)
        self.assertIn("CoreConfig/AetherCoreCapabilities remain the separate production", source)

    def test_production_scala_does_not_read_abi_from_isa(self):
        offenders = []
        for path in MAIN_SCALA.rglob("*.scala"):
            text = path.read_text(encoding="utf-8")
            if ".isa.mabi" in text or "isa.mabi" in text:
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
