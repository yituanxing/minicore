import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "src/main/scala/aethercore/core/v2"
CONFIG = ROOT / "src/main/scala/aethercore/config/CoreConfig.scala"
GEOMETRY = ROOT / "src/main/scala/aethercore/config/PageTableGeometry.scala"


class V2XlenBoundaryContract(unittest.TestCase):
    """Keep RV32/RV64 as one parameterized v2 implementation."""

    def test_v2_has_no_xlen_specific_production_files(self):
        bad = [
            path.name
            for path in sorted(V2.glob("*.scala"))
            if re.search(r"(?:^|_)(?:rv32|rv64)(?:_|$)", path.stem, re.IGNORECASE)
        ]
        self.assertEqual(
            bad,
            [],
            "RV32/RV64 must not grow separate v2 production source files",
        )

    def test_top_level_v2_composition_does_not_select_modules_by_xlen(self):
        # Width-dependent arithmetic inside an execution unit is legitimate.
        # Choosing a different core/backend/frontend module solely from XLEN is not.
        composition_files = [
            "TinyBareCore.scala",
            "TinyPagedCore.scala",
            "TinyMemoryBackend.scala",
            "TinyPrivileged.scala",
        ]
        module_fork = re.compile(
            r"if\s*\(\s*(?:isa\.)?[xX]len\s*==\s*(?:32|64)\s*\)"
            r"[\s\S]{0,160}?Module\s*\(",
            re.MULTILINE,
        )
        named_fork = re.compile(r"\b(?:Rv|RV)(?:32|64)[A-Za-z0-9_]*Core\b")
        violations = []
        for name in composition_files:
            text = (V2 / name).read_text(encoding="utf-8")
            if module_fork.search(text) or named_fork.search(text):
                violations.append(name)
        self.assertEqual(
            violations,
            [],
            "top-level v2 composition must parameterize XLEN instead of selecting RV32/RV64 module forks",
        )

    def test_architecture_and_production_capability_remain_separate(self):
        config = CONFIG.read_text(encoding="utf-8")
        geometry = GEOMETRY.read_text(encoding="utf-8")

        self.assertIn("final case class IsaConfig", config)
        self.assertIn("object AetherCoreCapabilities", config)
        self.assertIn('val virtualMemoryModes: Set[String] = Set("Sv32", "Sv39")', config)
        self.assertIn("final case class PageTableGeometry", geometry)
        self.assertIn("val Sv32: PageTableGeometry", geometry)
        self.assertIn("val Sv39: PageTableGeometry", geometry)
        self.assertIn("modes.forall(_.xlen == xlen)", geometry)

    def test_rv64_word_semantics_remain_a_capability_not_a_core_fork(self):
        config = CONFIG.read_text(encoding="utf-8")
        self.assertIn("val hasWordOps: Boolean = xlen == 64", config)


if __name__ == "__main__":
    unittest.main()
