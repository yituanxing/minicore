import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE_V2 = ROOT / "src/main/scala/aethercore/core/v2"
CORE = ROOT / "src/main/scala/aethercore/core"
SIM = ROOT / "src/main/scala/aethercore/sim"
TESTS = ROOT / "src/test/scala/aethercore"


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


class V2ArchitectureBoundarySourceContract(unittest.TestCase):
    def test_rv32_rv64_share_the_v2_core_implementation(self):
        # Profiles may differ by architectural geometry, but the v2 production
        # implementation must not grow RV32/RV64-named backend/core forks.
        class_decl = re.compile(r"\bclass\s+\w*(?:RV32|RV64)\w*", re.IGNORECASE)
        offenders = []
        for path in sorted(CORE_V2.glob("*.scala")):
            if class_decl.search(read(path)):
                offenders.append(path.name)
        self.assertEqual([], offenders)

        width_spec = read(TESTS / "DatapathWidthSpec.scala")
        self.assertIn("for (xlen <- Seq(32, 64))", width_spec)
        self.assertIn("simulate(new TinyRobCommitBackend(xlen))", width_spec)
        self.assertIn("simulate(new V2FoundationWidthSmoke(xlen))", width_spec)

    def test_vm_composition_remains_geometry_driven_and_shared(self):
        translation = read(CORE / "TranslationUnit.scala")
        fetch = read(CORE / "InstructionFetchAdapter.scala")
        data = read(CORE / "DataPathAdapter.scala")
        arbiter = read(CORE / "PtwArbiter.scala")
        paged = read(CORE_V2 / "TinyPagedCore.scala")

        self.assertIn("Module(new PageTableWalker(geometry))", translation)
        self.assertIn("Module(new TranslationTlb(geometry, tlbEntries))", translation)
        self.assertIn("Module(new TranslationUnit(geometry, tlbEntries))", fetch)
        self.assertIn("Module(new TranslationUnit(geometry, tlbEntries))", data)

        self.assertIn("val chooseData = io.dataValid", arbiter)
        self.assertIn("val chooseFetch = !chooseData && io.fetchValid", arbiter)

        self.assertIn(
            "Module(new InstructionFetchAdapter(geometry, PhysicalBits, tlbEntries))",
            paged,
        )
        self.assertIn("Module(new PtwArbiter(geometry, PhysicalBits))", paged)
        self.assertIn("ptwArbiter.io.dataValid := backend.io.pteValid", paged)
        self.assertIn("ptwArbiter.io.fetchValid := fetch.io.pteValid", paged)

        # The v2 composition must not instantiate a private Sv32/Sv39 walker or
        # TLB instead of the generic geometry-driven owners above.
        self.assertNotRegex(paged, r"Module\(new\s+Sv(?:32|39)\w*(?:Walker|Tlb|Translation)")

    def test_platform_mmio_knowledge_stays_outside_the_v2_core(self):
        paged = read(CORE_V2 / "TinyPagedCore.scala")
        backend = read(CORE_V2 / "TinyMemoryBackend.scala")
        core_source = paged + "\n" + backend

        for forbidden in (
            "uartAddress",
            "exitAddress",
            "mtimeAddress",
            "mtimecmpAddress",
            "plicBase",
            "MachinePlicMmio",
        ):
            self.assertNotIn(forbidden, core_source)

        self.assertIn("val resolvedAttributes = Input(new MemoryAttributes)", backend)

        shell = read(SIM / "AetherCoreV2OpenSbiRV64SimTop.scala")
        self.assertIn("Module(new TinyPagedCore(", shell)
        self.assertIn("private val ramBase", shell)
        self.assertIn("private val plicBase", shell)
        self.assertIn("val uartAddress = config.platform.uartAddress", shell)
        self.assertIn("val exitAddress = config.platform.exitAddress", shell)
        self.assertIn("val mtimeAddress = config.platform.mtimeAddress", shell)
        self.assertIn("core.io.resolvedAttributes.cacheable := resolvedRam", shell)

    def test_shared_ptw_is_the_only_i_d_arbitration_owner_in_v2_composition(self):
        paged = read(CORE_V2 / "TinyPagedCore.scala")
        self.assertEqual(1, paged.count("new PtwArbiter("))
        self.assertIn("ptwArbiter.io.dataAddress := backend.io.pteAddress", paged)
        self.assertIn("ptwArbiter.io.fetchAddress := fetch.io.pteAddress", paged)
        self.assertIn("io.ptw.addr := ptwArbiter.io.memoryAddress", paged)


if __name__ == "__main__":
    unittest.main()
