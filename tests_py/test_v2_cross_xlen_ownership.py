import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
V2 = ROOT / "src/main/scala/aethercore/core/v2"
CORE = ROOT / "src/main/scala/aethercore/core"
CONFIG = ROOT / "src/main/scala/aethercore/config/CoreConfig.scala"
GEOMETRY = ROOT / "src/main/scala/aethercore/config/PageTableGeometry.scala"
WALKER = CORE / "PageTableWalker.scala"
LSU = V2 / "TinyBlockingLsu.scala"
BACKEND = V2 / "TinyMemoryBackend.scala"
PAGED_CORE = V2 / "TinyPagedCore.scala"
FOUNDATION = V2 / "FoundationTypes.scala"


class V2CrossXlenOwnershipContract(unittest.TestCase):
    def test_v2_does_not_grow_rv32_rv64_named_implementation_forks(self):
        offenders = []
        declaration = re.compile(r"\b(?:class|object|trait)\s+(?:Rv|RV)(?:32|64)[A-Za-z0-9_]*")

        for path in sorted(V2.glob("*.scala")):
            if re.search(r"(?:rv|RV)(?:32|64)", path.stem):
                offenders.append(f"file:{path.name}")
            source = path.read_text(encoding="utf-8")
            for match in declaration.finditer(source):
                offenders.append(f"declaration:{path.name}:{match.group(0)}")

        self.assertEqual(
            offenders,
            [],
            "RV32/RV64 must remain one parameterized v2 implementation; "
            "named backend/core forks require explicit architecture review",
        )

    def test_foundation_and_backend_keep_xlen_as_data(self):
        foundation = FOUNDATION.read_text(encoding="utf-8")
        backend = BACKEND.read_text(encoding="utf-8")
        lsu = LSU.read_text(encoding="utf-8")
        paged = PAGED_CORE.read_text(encoding="utf-8")

        self.assertIn("class DecodedInstruction(val xlen: Int)", foundation)
        self.assertIn("class BackendUop(", foundation)
        self.assertIn("val xlen: Int", foundation)

        self.assertIn("class TinyMemoryBackend(", backend)
        self.assertIn("private val xlen = isa.xlen", backend)
        self.assertIn("class TinyBlockingLsu(", lsu)
        self.assertIn("private val Xlen = geometry.xlen", lsu)
        self.assertIn("class TinyPagedCore(", paged)
        self.assertIn("private val Xlen = isa.xlen", paged)

    def test_vm_shape_is_geometry_data_not_per_xlen_walkers(self):
        geometry = GEOMETRY.read_text(encoding="utf-8")
        walker = WALKER.read_text(encoding="utf-8")

        self.assertIn("final case class PageTableGeometry(", geometry)
        self.assertIn('val Sv32: PageTableGeometry', geometry)
        self.assertIn('val Sv39: PageTableGeometry', geometry)
        self.assertIn("modes.forall(_.xlen == xlen)", geometry)

        self.assertIn("class PageTableWalker(val geometry: PageTableGeometry)", walker)
        self.assertIn("geometry.levels", walker)
        self.assertIn("geometry.vpnBitsPerLevel", walker)
        self.assertIn("new PageTableEntryChecker(geometry)", walker)

        for forbidden in ("Sv32Walker.scala", "Sv39Walker.scala", "Sv48Walker.scala"):
            self.assertFalse(
                (CORE / forbidden).exists(),
                f"{forbidden} would split one geometry-driven PTW ownership into an XLEN/mode fork",
            )

    def test_architecture_capability_rejection_stays_in_configuration(self):
        config = CONFIG.read_text(encoding="utf-8")

        self.assertIn("final case class IsaConfig(", config)
        self.assertIn("final case class CoreConfig(", config)
        self.assertIn("object AetherCoreCapabilities", config)
        self.assertIn('val virtualMemoryModes: Set[String] = Set("Sv32", "Sv39")', config)
        self.assertIn("isa.virtualMemoryModes.subsetOf(AetherCoreCapabilities.virtualMemoryModes)", config)
        self.assertIn("isa.pageTableGeometries.size <= 1", config)

    def test_physical_address_width_is_not_collapsed_into_xlen(self):
        lsu = LSU.read_text(encoding="utf-8")
        config = CONFIG.read_text(encoding="utf-8")

        self.assertIn("val paddrBits: Int", lsu)
        self.assertIn("geometry.architecturalPhysicalAddressBits", lsu)
        self.assertIn("private val rv32Sv32Platform: PlatformConfig = rv32Platform.copy(paddrBits = 34)", config)
        self.assertIn("private val rv64PmpPlatform: PlatformConfig = rv64Platform.copy(paddrBits = 56)", config)


if __name__ == "__main__":
    unittest.main()
