package aethercore.core.product

import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, PageTableReadBusIO, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.{PhysicalAddressNarrowing, PmpAccessChecker, PmpConstants, PmpGeometry,
  PmpRangeDecoder, TranslationUnit}
import aethercore.core.semantic.MemoryOperationKind
import aethercore.memory.MemoryAttributes
import chisel3._
import chisel3.util._

/** Read-only architectural memory context. MachineCsrFile remains its sole owner. */
class ProductDataTranslationContext(val geometry: PageTableGeometry) extends Bundle {
  val effectivePrivilege = UInt(2.W)
  val satpEnabled = Bool()
  val rootPpn = UInt(geometry.ppnBits.W)
  val sum = Bool()
  val mxr = Bool()
}

/** A memory uOp keeps its ROB lifetime and virtual address until resolution. */
private class ProductTranslationSlot(val indexBits: Int, val generationBits: Int,
                                     val geometry: PageTableGeometry,
                                     val paddrBits: Int) extends Bundle {
  val valid = Bool()
  val translating = Bool()
  val token = new ProductRobId(indexBits, generationBits)
  val virtualAddress = UInt(64.W)
  val storeData = UInt(64.W)
  val kind = MemoryOperationKind()
  val size = MemSize()
  val atomicOp = AtomicOp()
  val context = new ProductDataTranslationContext(geometry)
  // PMP CSR values are an architectural snapshot, never sampled from a later
  // CSR context while an older request is waiting for a page walk.
  val pmpConfig = Vec(PmpConstants.MaxEntries, UInt(8.W))
  val pmpAddress = Vec(PmpConstants.MaxEntries,
    UInt(PmpGeometry(64, paddrBits).encodedAddressBits.W))
}

/** One translated result waiting for the combinational PMP/PMA classification. */
private class ProductPhysicalClassification(val indexBits: Int, val generationBits: Int,
                                            val geometry: PageTableGeometry,
                                            val paddrBits: Int) extends Bundle {
  val slot = new ProductTranslationSlot(indexBits, generationBits, geometry, paddrBits)
  val paddr = UInt(paddrBits.W)
}

/** Shared Sv39 data translation for the Small physical LQ/SQ.
  *
  * Every already-issued memory operation has a distinct input slot, up to
  * LoadQ+StoreQ capacity. A young TLB miss is only *probed*, never assigned
  * the sole walker: the exact ROB head always wins and is the only operation
  * allowed to initiate a page walk. This avoids a younger speculative miss
  * deadlocking the older memory instruction on which retirement depends.
  *
  * TranslationUnit, PageTableWalker and TranslationTlb remain the only
  * translation algorithms. The resolver owns ROB-token routing, kill/drain,
  * effective-privilege PMP checks and platform PMA classification only.
  * Physical memory transaction lifetime remains exclusively in the LSQ.
  */
class ProductDataResolver(val config: ProductCoreConfig, val tlbEntries: Int = 8) extends Module {
  private val g = config.geometry
  require(g.decodeWidth == 1 && g.dispatchWidth == 1 && g.issueWidth == 1 &&
    g.retireWidth == 1, "this resolver currently serves the Small one-wide CPU")
  private val geometry = PageTableGeometry.Sv39
  private val PA = config.platform.paddrBits
  private val ArchPA = geometry.architecturalPhysicalAddressBits
  private val T = g.loadQueueEntries + g.storeQueueEntries
  private val S = log2Ceil(T)
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16
  private val pmpAddrBits = PmpGeometry(64, PA).encodedAddressBits
  require(T > 1 && (T & (T - 1)) == 0, "translation slots use a power-of-two physical queue")
  require(PA > 0 && PA <= ArchPA, "the current Sv39 platform must fit architectural PA width")

  val io = IO(new Bundle {
    val memoryIssue = Flipped(Decoupled(new ProductIssue(64, I, P, G)))
    // Independent of Decoupled.ready, so backend issue selection has no ready loop.
    val resourceAvailable = Output(Bool())
    val robHead = Flipped(Valid(new ProductRobId(I, G)))
    val kill = Flipped(Valid(new ProductRobId(I, G)))
    val translationFence = Input(Bool())
    val context = Input(new ProductDataTranslationContext(geometry))
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries, UInt(pmpAddrBits.W)))
    val pte = new PageTableReadBusIO(PA, geometry.pteBits)
    val pmaValid = Output(Bool())
    val pmaAddress = Output(UInt(PA.W))
    val pmaAttributes = Input(new MemoryAttributes)
    val resolved = Decoupled(new ProductResolvedMem(PA, I, G))
    val pending = Output(UInt(log2Ceil(T + 1).W))
    val walking = Output(Bool())
  })

  private def same(a: ProductRobId, b: ProductRobId): Bool =
    a.index === b.index && a.generation === b.generation

  private val slots = RegInit(VecInit(Seq.fill(T)(
    0.U.asTypeOf(new ProductTranslationSlot(I, G, geometry, PA)))))
  private val freeMask = VecInit(slots.map(s => !s.valid)).asUInt
  private val freeIndex = PriorityEncoder(freeMask)
  private val alias = slots.map(s => s.valid && same(s.token, io.memoryIssue.bits.token)).reduce(_ || _)
  private val killedIssue = io.kill.valid && same(io.kill.bits, io.memoryIssue.bits.token)

  // The two downstream single-entry holds are also lifetime leases. A future
  // ROB generation wrap may not make an old output look like a new operation.
  private val postValid = RegInit(false.B)
  private val post = Reg(new ProductPhysicalClassification(I, G, geometry, PA))
  private val postIndex = Reg(UInt(S.W))
  private val resultValid = RegInit(false.B)
  private val result = Reg(new ProductResolvedMem(PA, I, G))
  private val resultIndex = Reg(UInt(S.W))
  private val heldAlias = (postValid && same(post.slot.token, io.memoryIssue.bits.token)) ||
    (resultValid && same(result.token, io.memoryIssue.bits.token))
  io.resourceAvailable := freeMask.orR && !io.translationFence
  io.memoryIssue.ready := io.resourceAvailable && !alias && !heldAlias && !killedIssue
  // Slots remain leased through post-PMP/PMA and output backpressure. This
  // gives SFENCE and kill an exact owner to retry/invalidate without loss.
  io.pending := PopCount(slots.map(_.valid))

  when(io.memoryIssue.fire) {
    assert(io.memoryIssue.bits.memoryKind === MemoryOperationKind.Load ||
      io.memoryIssue.bits.memoryKind === MemoryOperationKind.Store ||
      io.memoryIssue.bits.memoryKind === MemoryOperationKind.Atomic,
      "only Load/Store/Atomic belong to ProductDataResolver")
    slots(freeIndex) := 0.U.asTypeOf(slots(freeIndex))
    slots(freeIndex).valid := true.B
    slots(freeIndex).token := io.memoryIssue.bits.token
    slots(freeIndex).virtualAddress :=
      io.memoryIssue.bits.lhs + io.memoryIssue.bits.immediate
    slots(freeIndex).storeData := io.memoryIssue.bits.storeData
    slots(freeIndex).kind := io.memoryIssue.bits.memoryKind
    slots(freeIndex).size := io.memoryIssue.bits.memorySize
    slots(freeIndex).atomicOp := io.memoryIssue.bits.atomicOp
    slots(freeIndex).context := io.context
    slots(freeIndex).pmpConfig := io.pmpConfig
    slots(freeIndex).pmpAddress := io.pmpAddress
  }

  // Exactly one shared TranslationUnit, with a latched owner for asynchronous
  // walk/bare responses. Same-cycle TLB hits use the current selected token.
  private val translation = Module(new TranslationUnit(
    geometry, tlbEntries, externalWalkGate = true, implementedPaddrBits = PA))
  private val activeValid = RegInit(false.B)
  private val activeIndex = Reg(UInt(S.W))
  private val roundRobin = RegInit(0.U(S.W))
  private val candidateMask = VecInit(slots.map(s => s.valid && !s.translating))
  private val exactHead = VecInit((0 until T).map(j =>
    candidateMask(j) && io.robHead.valid && same(slots(j).token, io.robHead.bits)))
  private val hasHead = exactHead.asUInt.orR
  private val rotated = Wire(Vec(T, Bool()))
  for (offset <- 0 until T) {
    rotated(offset) := candidateMask((roundRobin + offset.U)(S - 1, 0))
  }
  private val selectedIndex = Mux(hasHead, PriorityEncoder(exactHead.asUInt),
    (roundRobin + PriorityEncoder(rotated.asUInt))(S - 1, 0))
  private val selected = slots(selectedIndex)
  private val candidateValid = candidateMask.asUInt.orR && !activeValid &&
    !postValid && !resultValid && !io.translationFence
  private val selectedHead = hasHead && same(selected.token, io.robHead.bits)
  private val selectedKilled = io.kill.valid && same(selected.token, io.kill.bits)
  private val alignedMask = (1.U(4.W) << selected.size.asUInt) - 1.U
  private val misaligned = (selected.virtualAddress & alignedMask) =/= 0.U
  private val localFault = candidateValid && misaligned && !selectedKilled
  private val offerTranslation = candidateValid && !misaligned && !selectedKilled
  private val selectedRead = selected.kind === MemoryOperationKind.Load ||
    (selected.kind === MemoryOperationKind.Atomic && selected.atomicOp === AtomicOp.Lr)

  translation.io.requestValid := offerTranslation
  translation.io.virtualAddress := selected.virtualAddress
  translation.io.privilege := selected.context.effectivePrivilege
  translation.io.write := !selectedRead
  translation.io.execute := false.B
  translation.io.satpTranslationEnabled := selected.context.satpEnabled
  translation.io.satpRootPpn := selected.context.rootPpn
  translation.io.sum := selected.context.sum
  translation.io.mxr := selected.context.mxr
  translation.io.walkAllowed.get := selectedHead
  private val killActive = io.kill.valid && activeValid &&
    same(slots(activeIndex).token, io.kill.bits)
  translation.io.kill := killActive
  translation.io.flush := io.translationFence
  translation.io.responseReady := !resultValid && !postValid

  private val translationRequestFire =
    translation.io.requestValid && translation.io.requestReady
  private val translationResponseFire =
    translation.io.responseValid && translation.io.responseReady
  private val responseIndex = Mux(activeValid, activeIndex, selectedIndex)
  private val responseSlot = slots(responseIndex)
  private val responseRead = responseSlot.kind === MemoryOperationKind.Load ||
    (responseSlot.kind === MemoryOperationKind.Atomic && responseSlot.atomicOp === AtomicOp.Lr)
  private val responseKilled = io.kill.valid && same(responseSlot.token, io.kill.bits)
  private val (narrowDataAddress, dataOutOfRange) =
    PhysicalAddressNarrowing(translation.io.physicalAddress, PA)
  private val translatedFault = translation.io.pageFault ||
    translation.io.accessFault || dataOutOfRange

  // A young miss is a rejected lookup, not a leased walk. Rotate the probe
  // candidate so a non-head miss cannot starve the next possible TLB hit.
  when(candidateValid && !selectedHead && !selectedKilled) {
    roundRobin := selectedIndex + 1.U
  }
  when(translationRequestFire && !translationResponseFire) {
    activeValid := true.B
    activeIndex := selectedIndex
    slots(selectedIndex).translating := true.B
  }
  when(translationResponseFire) {
    activeValid := false.B
    slots(responseIndex).translating := true.B
    when(!responseKilled) {
      when(translatedFault) {
        resultValid := true.B
        resultIndex := responseIndex
        result := 0.U.asTypeOf(result)
        result.token := responseSlot.token
        result.virtualAddress := responseSlot.virtualAddress
        result.storeData := responseSlot.storeData
        result.fault.valid := true.B
        result.fault.cause := Mux(translation.io.pageFault,
          Mux(responseRead,
            MachineExceptionCode.LoadPageFault.U, MachineExceptionCode.StorePageFault.U),
          Mux(responseRead,
            MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U))
        result.fault.value := responseSlot.virtualAddress
      }.otherwise {
        postValid := true.B
        postIndex := responseIndex
        post.slot := responseSlot
        post.paddr := narrowDataAddress
      }
    }
  }

  // Check the real PA domain before any PTW external request. PMP PTE reads
  // are Supervisor reads regardless of the original data access privilege.
  private val pmpRanges = Module(new PmpRangeDecoder(64, PmpConstants.MaxEntries, PA))
  private val pmpOwner = Mux(postValid, post.slot,
    Mux(activeValid, slots(activeIndex), selected))
  pmpRanges.io.config := pmpOwner.pmpConfig
  pmpRanges.io.pmpAddress := pmpOwner.pmpAddress
  private val ptePmp = Module(new PmpAccessChecker(PmpConstants.MaxEntries, PA))
  private val dataPmp = Module(new PmpAccessChecker(PmpConstants.MaxEntries, PA))
  ptePmp.io.ranges := pmpRanges.io.ranges
  dataPmp.io.ranges := pmpRanges.io.ranges

  private val (narrowPteAddress, pteOutOfRange) =
    PhysicalAddressNarrowing(translation.io.pteAddress, PA)
  ptePmp.io.privilege := PrivilegeMode.Supervisor.U
  ptePmp.io.address := narrowPteAddress
  ptePmp.io.bytes := geometry.pteBytes.U
  ptePmp.io.write := false.B
  ptePmp.io.execute := false.B
  private val pteAccessFault = translation.io.pteValid &&
    (pteOutOfRange || (config.software.isa.hasPmp.B && !ptePmp.io.allow))
  io.pte.valid := translation.io.pteValid && !pteAccessFault
  io.pte.addr := narrowPteAddress
  translation.io.pteReady := Mux(pteAccessFault, true.B, io.pte.ready)
  translation.io.pteData := io.pte.rdata
  translation.io.pteFault := pteAccessFault || (io.pte.valid && io.pte.fault)

  dataPmp.io.privilege := post.slot.context.effectivePrivilege
  dataPmp.io.address := post.paddr
  dataPmp.io.bytes := (1.U(4.W) << post.slot.size.asUInt)
  private val postRead = post.slot.kind === MemoryOperationKind.Load ||
    (post.slot.kind === MemoryOperationKind.Atomic && post.slot.atomicOp === AtomicOp.Lr)
  dataPmp.io.write := !postRead
  dataPmp.io.execute := false.B
  io.pmaValid := postValid
  io.pmaAddress := post.paddr

  // A registered post-translation stage isolates TLB/PTW logic from the
  // platform PMA decoder and holds attributes through output backpressure.
  when(postValid && !resultValid && !io.translationFence) {
    resultIndex := postIndex
    result := 0.U.asTypeOf(result)
    result.token := post.slot.token
    result.virtualAddress := post.slot.virtualAddress
    result.paddr := post.paddr
    result.storeData := post.slot.storeData
    result.attributes := io.pmaAttributes
    val deniedPmp = config.software.isa.hasPmp.B && !dataPmp.io.allow
    val deniedAtomicPma = post.slot.kind === MemoryOperationKind.Atomic &&
      !io.pmaAttributes.supportsAtomic
    result.fault.valid := deniedPmp || deniedAtomicPma
    result.fault.cause := Mux(postRead,
      MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
    result.fault.value := post.slot.virtualAddress
    resultValid := true.B
    postValid := false.B
  }

  when(localFault) {
    slots(selectedIndex).translating := true.B
    resultIndex := selectedIndex
    result := 0.U.asTypeOf(result)
    result.token := selected.token
    result.virtualAddress := selected.virtualAddress
    result.storeData := selected.storeData
    result.fault.valid := true.B
    result.fault.cause := Mux(selectedRead,
      MachineExceptionCode.LoadAddressMisaligned.U,
      MachineExceptionCode.StoreAddressMisaligned.U)
    result.fault.value := selected.virtualAddress
    resultValid := true.B
  }

  io.resolved.valid := resultValid && !io.translationFence &&
    !(io.kill.valid && same(result.token, io.kill.bits))
  io.resolved.bits := result
  when(io.resolved.fire) {
    resultValid := false.B
    slots(resultIndex).valid := false.B
  }

  // A cancel is exact-token, not ROB-index-only. TranslationUnit kills only
  // the active walk; other independent pending memory lifetimes remain alive.
  when(io.kill.valid) {
    for (j <- 0 until T) {
      when(slots(j).valid && same(slots(j).token, io.kill.bits)) {
        slots(j).valid := false.B
      }
    }
    when(killActive) { activeValid := false.B }
    when(postValid && same(post.slot.token, io.kill.bits)) { postValid := false.B }
    when(resultValid && same(result.token, io.kill.bits)) { resultValid := false.B }
  }
  when(io.translationFence) {
    // The existing architectural lifetime is not discarded; a killed walk
    // must re-probe after the fence with the snapshotted request context.
    when(activeValid) {
      slots(activeIndex).translating := false.B
      activeValid := false.B
    }
    when(postValid) {
      slots(postIndex).translating := false.B
      postValid := false.B
    }
    when(resultValid) {
      slots(resultIndex).translating := false.B
      resultValid := false.B
    }
  }
  io.walking := activeValid
  assert(PopCount(slots.map(_.valid)) <= T.U)
}
