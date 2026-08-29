import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "src/main/scala/aethercore/core"


class VmPermissionBoundaryContract(unittest.TestCase):
    def test_page_table_entry_policy_has_a_single_owner(self):
        checker = (CORE / "PageTableEntryChecker.scala").read_text(encoding="utf-8")
        walker = (CORE / "PageTableWalker.scala").read_text(encoding="utf-8")

        # The checker must own the architectural PTE permission vocabulary.
        for token in (
            "readAllowed",
            "accessAllowed",
            "privilegeAllowed",
            "adAllowed",
            "invalidEncoding",
            "leafAccessFault",
        ):
            self.assertIn(token, checker)

        # The walker may consume the checker result but must not recreate the
        # R/W/X/U/SUM/MXR/A/D permission equation locally.
        forbidden = (
            r"val\s+readAllowed\s*=",
            r"val\s+accessAllowed\s*=",
            r"val\s+privilegeAllowed\s*=",
            r"val\s+adAllowed\s*=",
            r"val\s+invalidEncoding\s*=",
            r"val\s+nonLeafReserved\s*=",
        )
        for pattern in forbidden:
            self.assertIsNone(re.search(pattern, walker), pattern)

        self.assertIn("Module(new PageTableEntryChecker(geometry))", walker)
        self.assertIn("entryChecker.io.invalidEncoding", walker)
        self.assertIn("entryChecker.io.leafAccessFault", walker)

    def test_vm_layers_remain_geometry_driven(self):
        for name in (
            "PageTableEntryChecker.scala",
            "PageTableWalker.scala",
            "TranslationTlb.scala",
            "TranslationUnit.scala",
            "InstructionFetchAdapter.scala",
            "DataPathAdapter.scala",
            "PtwArbiter.scala",
        ):
            text = (CORE / name).read_text(encoding="utf-8")
            self.assertIn("PageTableGeometry", text, name)


if __name__ == "__main__":
    unittest.main()
