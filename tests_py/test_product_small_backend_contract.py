"""Source-level guards for the first *hardware* G1 SmallOoO backend slice.

The genuine run-time correctness oracle is ProductSmallOoOBackendSpec (Chisel);
this suite only prevents misleading ownership or feature claims.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "src/main/scala/aethercore/core/product/ProductSmallOoOBackend.scala"
SPEC = ROOT / "src/test/scala/aethercore/ProductSmallOoOBackendSpec.scala"
CONFIG = ROOT / "src/main/scala/aethercore/core/product/ProductCoreConfig.scala"

class ProductSmallBackendContracts(unittest.TestCase):
    def test_real_chisel_rob_iq_and_physreg_owners(self):
        source = CORE.read_text()
        for text in ("extends Module", "class ProductSmallOoOBackend",
                     "private val rob = RegInit", "private val iq = RegInit",
                     "private val speculativeRat", "private val committedRat",
                     "private val free = RegInit", "private val prf = Reg",
                     "class ProductRobId", "class ProductPhysicalTag",
                     "val completion = Flipped(Decoupled", "val issue = Decoupled",
                     "val retire = Decoupled", "val redirect = Valid",
                     "when(recovering)", "val victimIndex",
                     "speculativeRat(victim.rd) := victim.oldPhysical",
                     "private val systemPayload = RegInit",
                     "systemPayload.token.generation === victim.tokenGeneration",
                     "io.systemHead.bits.decoded.system := systemPayload.system",
                     "val readAddr1 = Mux(readSystem, systemPayload.rs1Physical, selected.src1)",
                     "val readAddr2 = Mux(readSystem, systemPayload.rs2Physical, selected.src2)"):
            self.assertIn(text, source)
        rob_slot = source.split("private class ProductRobSlot", 1)[1].split(
            "private class ProductSystemPayload", 1)[0]
        self.assertNotIn("new DecodedInstruction", rob_slot)
        self.assertNotIn("import aethercore.core.v2", source)
        self.assertNotIn("new Tiny", source)

    def test_killed_and_reused_responses_must_be_checked(self):
        source = CORE.read_text()
        for text in ("tokenGeneration === c.token.generation",
                     "completionPhysicalGeneration === c.destination.generation",
                     "completionEntry.destinationFile === c.destinationFile",
                     "io.completion.ready := !recovering",
                     "io.staleCompletion := io.completion.fire && !sameLifetime",
                     "free(victim.newPhysical.index) := true.B",
                     "generations(victimIndex) := generations(victimIndex) + 1.U"):
            self.assertIn(text, source)

    def test_capability_boundaries_refuse_unowned_privilege_and_memory(self):
        source = CORE.read_text()
        self.assertIn("dispatchDecoded.memory.kind === MemoryOperationKind.Load", source)
        self.assertIn("allowSystem: Boolean = false", source)
        self.assertIn("allowSystem.B && dispatchDecoded.memory.kind", source)
        self.assertIn("!serializedLive", source)
        self.assertIn("io.memoryReserve.valid := io.dispatch.valid && canDispatch", source)
        self.assertIn("(!memoryDispatch || exceptionOnly || io.memoryReserve.ready)", source)
        self.assertIn("not a complete CPU", source)
        self.assertIn("rtlImplemented: Boolean = false", CONFIG.read_text())
        standalone = (ROOT / "src/main/scala/aethercore/core/product/ProductIntegerBranchUnit.scala").read_text()
        self.assertIn("new ProductSmallOoOBackend(g, paddrBits = PA)", standalone)
        self.assertIn("backend.io.systemHead.ready := false.B", standalone)

    def test_simulation_actually_exercises_age_recovery_and_precise_traps(self):
        test = SPEC.read_text()
        for text in ("simulate(new SmallOoOTestHarness)", "younger independent",
                     "RAW dependencies", "non-head mispredicted branch",
                     "illegal oldest instruction", "refuse memory/system"):
            self.assertIn(text, test)
        f7 = (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text()
        self.assertIn("aethercore.ProductSmallOoOBackendSpec", f7)

if __name__ == "__main__":
    unittest.main()
