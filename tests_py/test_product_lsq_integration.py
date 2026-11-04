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
RESOLVER = BASE / "ProductDataResolver.scala"
TRANSLATED_TOP = BASE / "ProductSmallTranslatedCore.scala"
TEST = ROOT / "src/test/scala/aethercore/ProductPhysicalLsqSpec.scala"
CORETEST = ROOT / "src/test/scala/aethercore/ProductSmallMemoryCoreSpec.scala"
RESOLVERTEST = ROOT / "src/test/scala/aethercore/ProductDataResolverSpec.scala"

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
                         "val write = (isStore || isAtomic) && isHead",
                         "olderAtomic",
                         "assert(sameHead", "candidateIndex",
                         "when(io.kill.valid)", "io.completion <> completions.io.deq"):
            self.assertIn(fragment, s)
        self.assertNotIn("memoryDependencyPredictor", s)

    def test_atomic_uses_same_rob_resolver_lsq_transaction_owners(self):
        backend = BACKEND.read_text()
        lsq = LSQ.read_text()
        resolver = RESOLVER.read_text()
        for fragment in (
            "io.memoryReserve.bits.atomicOp := dispatchDecoded.memory.atomicOp",
            "io.issue.bits.atomicOp := selected.atomicOp",
            "iq(chosenIq).atomicOp := d.memory.atomicOp",
            "val atomicDispatch = dispatchDecoded.memory.kind === MemoryOperationKind.Atomic",
        ):
            self.assertIn(fragment, backend)
        for fragment in (
            "slots(chosenSlot).atomicOp := io.reserve.bits.atomicOp",
            "(reserveStore || reserveAtomic) && storeCount < S.U",
            "!olderAtomic && !localFault && safeLoad",
            "AetherMemOp.Atomic",
            "candidate.atomicOp, AtomicOp.None",
            "candidate.kind === MemoryOperationKind.Atomic",
        ):
            self.assertIn(fragment, lsq)
        for fragment in (
            "selected.atomicOp === AtomicOp.Lr",
            "postSlot.atomicOp === AtomicOp.Lr",
            "!io.pmaAttributes.supportsAtomic",
            "slots(freeIndex).atomicOp := io.memoryIssue.bits.atomicOp",
        ):
            self.assertIn(fragment, resolver)
        self.assertNotIn("new TinyBlockingLsu", backend + lsq + resolver)
        atomic_spec = ROOT / "src/test/scala/aethercore/ProductAtomicCoreSpec.scala"
        for fragment in (
            "class ProductAtomicCoreSpec", "AtomicOp.Swap", "AtomicOp.Lr",
            "AtomicOp.Sc", "StoreAddressMisaligned", "LoadAccessFault",
        ):
            self.assertIn(fragment, atomic_spec.read_text())

    def test_faults_keep_effective_virtual_address_across_translation_and_bus(self):
        s = LSQ.read_text()
        for fragment in (
            "val virtualAddress = UInt(64.W)",
            "slots(resolveSlot).virtualAddress := io.resolve.bits.virtualAddress",
            "slots(resolveSlot).fault.value := io.resolve.bits.virtualAddress",
            "completions.io.enq.bits.exception.value := returningSlot.virtualAddress",
        ):
            self.assertIn(fragment, s)
        self.assertNotIn("completions.io.enq.bits.exception.value := returningSlot.paddr", s)
        directed = TEST.read_text()
        for fragment in ("vaddr: Option[BigInt]", "Some(0x40004003L)",
                         "Some(0x40005000L)", "Some(0x40000000L)"):
            self.assertIn(fragment, directed)

    def test_backend_is_connected_to_memory_reservations_and_recovery(self):
        b = BACKEND.read_text()
        for fragment in ("val memoryReserve = Decoupled", "val memoryKill = Valid",
                         "val memoryHead = Valid", "val memoryAvailable = Input",
                         "io.memoryReserve.valid := io.dispatch.valid && canDispatch",
                         "io.memoryKill.valid := victim.memoryKind",
                         "io.issue.bits.storeData :=", "io.issue.bits.memoryKind :="):
            self.assertIn(fragment, b)
        top = TOP.read_text()
        for fragment in ("new ProductSmallOoOBackend(g, allowSystem = true, paddrBits = PA)",
                         "new ProductPhysicalLsq(g, PA)",
                         "lsq.io.reserve <> backend.io.memoryReserve",
                         "lsq.io.robHead := backend.io.memoryHead",
                         "lsq.io.kill := backend.io.memoryKill",
                         "io.resolverKill := backend.io.memoryKill",
                         "new RRArbiter(new ProductCompletion(64, I, P, G, PA), 4)",
                         "completion.io.in(2) <> lsq.io.completion",
                         "completion.io.in(3) <> system.io.completion",
                         "system.io.head <> backend.io.systemHead",
                         "backend.io.completion <> completion.io.out"):
            self.assertIn(fragment, top)
        self.assertIn("backend.io.memoryAvailable := io.memoryResourceAvailable", top)
        self.assertNotIn("backend.io.memoryAvailable := io.memoryExecute.ready", top)
        self.assertIn("must", top.lower())

    def test_physical_capacity_is_separate_from_rob_and_pmp_has_one_owner(self):
        lsq = LSQ.read_text()
        resolver = RESOLVER.read_text()
        for fragment in (
            "Seq.fill(T)(", "val token = new ProductRobId",
            "val resolveMatches = matchingSlot(io.resolve.bits.token)",
            "val responseMatches = matchingSlot(owner.token)",
            "transactions(chosenTxn).token := candidate.token",
            "val shiftedForward =", "def oldest(xs: Seq[AgeCandidate])",
        ):
            self.assertIn(fragment, lsq)
        self.assertNotIn("Seq.fill(N)(", lsq)
        for fragment in (
            "private val postSlot = slots(postIndex)",
            "private val postPaddr = Reg(UInt(PA.W))",
            "private def wrapAdd(base: UInt, increment: UInt)",
        ):
            self.assertIn(fragment, resolver)
        self.assertNotIn("private val post = Reg(new ProductPhysicalClassification", resolver)

    def test_product_uses_one_shared_sv39_translator_with_rob_token_ownership(self):
        s = RESOLVER.read_text()
        for fragment in (
            "class ProductDataResolver", "private val slots = RegInit",
            "new TranslationUnit(", "externalWalkGate = true",
            "translation.io.walkAllowed.get := selectedHead",
            "translation.io.kill := killActive",
            "slots(freeIndex).token := io.memoryIssue.bits.token",
            "PhysicalAddressNarrowing", "PmpRangeDecoder",
            "PmpAccessChecker", "io.translationFence",
            "result.virtualAddress :=", "io.resourceAvailable :=",
        ):
            self.assertIn(fragment, s)
        self.assertNotIn("new PageTableWalker", s)
        self.assertNotIn("new TranslationTlb", s)
        top = TRANSLATED_TOP.read_text()
        for fragment in (
            "new ProductSmallMemoryCore(config)", "new ProductDataResolver(config",
            "resolver.io.memoryIssue <> core.io.memoryExecute",
            "resolver.io.robHead := core.io.memoryHead",
            "resolver.io.kill := core.io.resolverKill",
            "core.io.memoryResolved <> resolver.io.resolved",
        ):
            self.assertIn(fragment, top)
        self.assertIn("ProductDataResolverSpec", RESOLVERTEST.read_text())
        self.assertIn("aethercore.ProductDataResolverSpec",
            (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text())

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
