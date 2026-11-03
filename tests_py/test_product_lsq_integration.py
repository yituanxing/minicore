"""Product memory ownership and full physical-path Chisel contract checks.

The static checks do NOT claim Sv39/ISA/Linux qualification.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "src/main/scala/aethercore/core/product"
LSQ = BASE / "ProductPhysicalLsq.scala"
BACKEND = BASE / "ProductSmallOoOBackend.scala"
TOP = BASE / "ProductSmallMemoryCore.scala"
TEST = ROOT / "src/test/scala/aethercore/ProductPhysicalLsqSpec.scala"
CORETEST = ROOT / "src/test/scala/aethercore/ProductSmallMemoryCoreSpec.scala"

class ProductLsqOwnershipContract(unittest.TestCase):
    def test_reserve_before_issue_and_independent_transaction_identity(self):
        s = LSQ.read_text()
        for fragment in (
            "class ProductMemReservation", "class ProductResolvedMem",
            "class ProductPhysicalLsq", "private val slots = RegInit",
            "private val transactions = RegInit",
            "val reserve = Flipped(Decoupled", "val resolve = Flipped(Decoupled",
            "val kill = Flipped(Valid", "val robHead = Flipped(Valid",
            "val memoryRequest = Decoupled(new AetherMemRequest",
            "val memoryResponse = Flipped(Decoupled(new AetherMemResponse",
            "transactions(chosenTxn).active := true.B",
            "transactions(responseIndex).active := false.B",
            "io.drainedKilledResponse := true.B",
            "io.droppedStaleResolution := io.resolve.fire && !resolutionLive",
        ):
            self.assertIn(fragment, s)
        self.assertNotIn("import aethercore.core.v2", s)
        self.assertNotIn("new Tiny", s)

    def test_mem_order_correctness_not_opt_in_predictor(self):
        s = LSQ.read_text()
        for fragment in ("unknownOlder", "youngest", "olderStore",
                         "safeLoad", "normalRam", "intersects", "forwardable",
                         "val write = isStore && isHead",
                         "assert(sameHead", "candidateIndex",
                         "when(io.kill.valid)", "io.completion <> completions.io.deq"):
            self.assertIn(fragment, s)
        self.assertNotIn("memoryDependencyPredictor", s)

    def test_backend_is_connected_to_memory_reservations_and_recovery(self):
        b = BACKEND.read_text()
        for fragment in ("val memoryReserve = Decoupled", "val memoryKill = Valid",
                         "val memoryHead = Valid", "val memoryAvailable = Input",
                         "io.memoryReserve.valid := io.dispatch.valid && canDispatch",
                         "io.memoryKill.valid := victim.decoded.memory.kind",
                         "io.issue.bits.storeData :=", "io.issue.bits.memoryKind :="):
            self.assertIn(fragment, b)
        top = TOP.read_text()
        for fragment in ("new ProductSmallOoOBackend(g)", "new ProductPhysicalLsq(g, PA)",
                         "lsq.io.reserve <> backend.io.memoryReserve",
                         "lsq.io.robHead := backend.io.memoryHead",
                         "lsq.io.kill := backend.io.memoryKill",
                         "io.resolverKill := backend.io.memoryKill",
                         "new RRArbiter(new ProductCompletion(64, I, P, G), 3)",
                         "completion.io.in(2) <> lsq.io.completion",
                         "backend.io.completion <> completion.io.out"):
            self.assertIn(fragment, top)
        self.assertIn("must", top.lower())

    def test_real_chisel_directed_lsq_and_integrated_execution_tests(self):
        a, b = TEST.read_text(), CORETEST.read_text()
        for fragment in ("unknown Store", "physical transaction", "MMIO Load",
                         "partial Store forwarding", "unaligned", "capacities"):
            self.assertIn(fragment, a)
        for fragment in ("simulate(new PhysicalProductHarness)",
                         "decoded LW", "ADDI -> SW", "physical resolver"):
            self.assertIn(fragment, b)
        gate = (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text()
        for name in ("aethercore.ProductPhysicalLsqSpec",
                     "aethercore.ProductSmallMemoryCoreSpec",
                     "aethercore.ProductSmallIntegerCoreSpec"):
            self.assertIn(name, gate)

    def test_no_false_linux_or_isa_upgrade(self):
        conf = (BASE / "ProductCoreConfig.scala").read_text()
        self.assertIn("rtlImplemented: Boolean = false", conf)
        self.assertIn("neither an MMU", LSQ.read_text())
        self.assertIn("Cannot", TOP.read_text().replace("cannot", "Cannot"))

if __name__ == "__main__":
    unittest.main()
