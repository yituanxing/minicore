package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.config.PageTableGeometry
import aethercore.core.{PmpChecker, PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * Two-slot ordinary-Load engine for the first genuinely non-blocking Path-C
  * memory experiment.
  *
  * Each slot deliberately reuses one already-qualified TinyBlockingLsu. That
  * keeps translation, PMP, fault construction, load extension and Decoupled
  * completion semantics unchanged while allowing two read lifetimes to overlap.
  * The wrapper owns only bounded slot allocation and arbitration:
  *
  *   - one new ordinary Load may enter per cycle;
  *   - at most two ordinary Loads may be active;
  *   - at most one physical request is launched per cycle, but two may remain
  *     outstanding and responses may return in either order;
  *   - external txnId 0/1 identifies the owning slot; each child's private
  *     transaction ID is translated at the wrapper boundary;
  *   - speculative PTW remains forbidden;
  *   - a pre-head physical read externalizes only after PMA proves it is
  *     idempotent, non-side-effecting and unordered.
  *
  * Store/SC/AMO ownership intentionally stays outside this unit. ROB age/order
  * and precise retirement also stay outside this unit.
  */
class TinyDualReplaySafeLoadUnit(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2
) extends Module {
  private val Xlen = geometry.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  private val PmpAddressBits = PmpGeometry(Xlen, PhysicalBits).encodedAddressBits
  private val Slots = 2

  require(txnIdBits >= 1, "dual Load unit needs at least one external transaction-id bit")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
    val completion = Decoupled(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
    val memoryTrace = Valid(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

    // Exact architectural head token. A slot not matching this token is
    // speculative and therefore may neither walk page tables nor touch unsafe
    // PMA regions.
    val head = Flipped(Valid(new RobToken(IdentityBits, GenerationBits)))

    val effectivePrivilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val supervisorSum = Input(Bool())
    val supervisorMxr = Input(Bool())
    val translationFlush = Input(Bool())

    val pmpEnabled = Input(Bool())
    val pmpConfig = Input(Vec(PmpConstants.MaxEntries, UInt(8.W)))
    val pmpAddress = Input(Vec(PmpConstants.MaxEntries, UInt(PmpAddressBits.W)))

    // One shared PTW seam. Only the exact-head slot may use it.
    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    // One shared PMA lookup seam. Slots are time-multiplexed through it before
    // physical launch; once launched they no longer consume this lookup port.
    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))

    // A token is bypassable only after its physical read has launched from a
    // PMA region proven replay-safe. The selector may cross such an older Load
    // to fill the second slot; an unresolved/unsafe older Load remains a barrier.
    val bypassable = Output(Vec(Slots, Valid(new RobToken(IdentityBits, GenerationBits))))
    val busy = Output(Bool())
    val full = Output(Bool())
  })

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private val slots = Seq.fill(Slots)(Module(new TinyBlockingLsu(
    geometry,
    paddrBits = PhysicalBits,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    allowAtomics = false
  )))

  for (slot <- slots) {
    slot.io.storePermit.valid := false.B
    slot.io.storePermit.bits := 0.U.asTypeOf(new RobToken(IdentityBits, GenerationBits))
    slot.io.effectivePrivilege := io.effectivePrivilege
    slot.io.satpTranslationEnabled := io.satpTranslationEnabled
    slot.io.satpRootPpn := io.satpRootPpn
    slot.io.supervisorSum := io.supervisorSum
    slot.io.supervisorMxr := io.supervisorMxr
    slot.io.translationFlush := io.translationFlush
    // Dual-Load physical launch is already single-lane at the wrapper. Keep
    // the child's qualified translation/lifetime machinery, but move PMP
    // ownership to one shared checker at that existing arbitration point.
    // The false constant lets synthesis remove each child's private checker.
    slot.io.pmpEnabled := false.B
    slot.io.pmpConfig := io.pmpConfig
    slot.io.pmpAddress := io.pmpAddress
    slot.io.resolvedAttributes := io.resolvedAttributes
  }

  // --------------------------------------------------------------------------
  // Slot allocation
  // --------------------------------------------------------------------------
  val free = VecInit(slots.map(s => !s.io.busy))
  val hasFree = free.asUInt.orR
  val allocIndex = PriorityEncoder(free)

  io.request.ready := hasFree && Mux1H(
    UIntToOH(allocIndex, Slots),
    slots.map(_.io.request.ready)
  )

  for ((slot, index) <- slots.zipWithIndex) {
    slot.io.request.valid := io.request.valid && hasFree && allocIndex === index.U
    slot.io.request.bits := io.request.bits
  }

  when(io.request.fire) {
    assert(io.request.bits.kind === MemoryOperationKind.Load,
      "dual Load unit accepts ordinary Loads only")
    assert(io.request.bits.atomicOp === AtomicOp.None,
      "dual Load unit must not accept LR/SC/AMO")
  }

  io.busy := slots.map(_.io.busy).reduce(_ || _)
  io.full := slots.map(_.io.busy).reduce(_ && _)

  // --------------------------------------------------------------------------
  // Exact-head ownership and speculative PTW gate
  // --------------------------------------------------------------------------
  val slotHead = Wire(Vec(Slots, Bool()))
  for ((slot, index) <- slots.zipWithIndex) {
    val status = slot.io.lifetimeStatus
    slotHead(index) := status.valid && io.head.valid && sameToken(status.robToken, io.head.bits)
  }

  val pteCandidates = VecInit(slots.zipWithIndex.map { case (slot, index) =>
    slot.io.pteValid && slotHead(index)
  })
  val pteSelectValid = pteCandidates.asUInt.orR
  val pteSelect = PriorityEncoder(pteCandidates)

  io.pteValid := pteSelectValid
  io.pteAddress := Mux1H(
    UIntToOH(pteSelect, Slots),
    slots.map(_.io.pteAddress)
  )

  for ((slot, index) <- slots.zipWithIndex) {
    val selected = pteSelectValid && pteSelect === index.U
    slot.io.pteReady := selected && io.pteReady
    slot.io.pteData := io.pteData
    slot.io.pteFault := selected && io.pteFault
    when(slot.io.pteValid && !slotHead(index)) {
      assert(!slot.io.pteReady, "speculative dual-Load slot must not externalize PTW traffic")
    }
  }

  // --------------------------------------------------------------------------
  // Shared PMA lookup + physical request launch
  // --------------------------------------------------------------------------
  // PMA address selection must be independent of both memoryRequest.ready and
  // resolvedAttributes. The simulation/platform PMA contract is combinational
  // address -> attributes, while a child's memoryRequest.valid may consume those
  // attributes. Feeding memoryRequest.valid back into address arbitration would
  // therefore create address -> attributes -> valid -> address.
  //
  // Keep an explicit wrapper-owned launched bit per slot. It is transaction
  // lifetime state, not a policy inference: a resolved slot requests PMA until
  // its external physical request handshakes, then relinquishes the shared PMA
  // seam until the completion releases the slot.
  val physicalLaunched = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val needsPma = VecInit(slots.zipWithIndex.map { case (slot, index) =>
    slot.io.resolvedPhysicalValid && !physicalLaunched(index)
  })
  val pmaSelectValid = needsPma.asUInt.orR
  val pmaSelect = PriorityEncoder(needsPma)

  io.resolvedPhysicalValid := pmaSelectValid
  io.resolvedPhysicalAddress := Mux1H(
    UIntToOH(pmaSelect, Slots),
    slots.map(_.io.resolvedPhysicalAddress)
  )

  val replaySafe =
    io.resolvedAttributes.idempotent &&
      !io.resolvedAttributes.sideEffecting &&
      !io.resolvedAttributes.ordered

  val selectedMemoryValid = Mux1H(
    UIntToOH(pmaSelect, Slots),
    slots.map(_.io.memoryRequest.valid)
  )
  val selectedMemoryBits = Mux1H(
    UIntToOH(pmaSelect, Slots),
    slots.map(_.io.memoryRequest.bits)
  )
  val selectedIsHead = Mux1H(UIntToOH(pmaSelect, Slots), slotHead)

  // One shared PMP checker is sufficient because this wrapper already launches
  // at most one physical request per cycle. A second resolved Load may retain
  // its translated lifetime while the selected slot consumes this combinational
  // permission lane; no external memory bandwidth is removed.
  val selectedAccessBytes = WireDefault((Xlen / 8).U(4.W))
  switch(selectedMemoryBits.size) {
    is(MemSize.Byte)  { selectedAccessBytes := 1.U }
    is(MemSize.Half)  { selectedAccessBytes := 2.U }
    is(MemSize.Word)  { selectedAccessBytes := 4.U }
    is(MemSize.DWord) { selectedAccessBytes := 8.U }
  }

  val sharedPmp = Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))
  sharedPmp.io.privilege := io.effectivePrivilege
  sharedPmp.io.address := io.resolvedPhysicalAddress
  sharedPmp.io.bytes := selectedAccessBytes
  sharedPmp.io.write := false.B
  sharedPmp.io.execute := false.B
  sharedPmp.io.config := io.pmpConfig
  sharedPmp.io.pmpAddress := io.pmpAddress

  val selectedPmpDenied =
    pmaSelectValid && selectedMemoryValid && io.pmpEnabled && !sharedPmp.io.allow
  val selectedPmpAllowed = !io.pmpEnabled || sharedPmp.io.allow
  val selectedPermit =
    pmaSelectValid && selectedMemoryValid && selectedPmpAllowed &&
      (selectedIsHead || replaySafe)

  io.memoryRequest.valid := selectedPermit
  io.memoryRequest.bits := selectedMemoryBits
  // External txnId is the fixed slot identity. Each child keeps its private
  // rolling txnId behind this wrapper.
  io.memoryRequest.bits.txnId := pmaSelect

  val childTxn = Reg(Vec(Slots, UInt(txnIdBits.W)))
  val localPmpFaultPending = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val bypassableValid = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val bypassableToken = Reg(Vec(Slots, new RobToken(IdentityBits, GenerationBits)))

  for ((slot, index) <- slots.zipWithIndex) {
    val selected = pmaSelectValid && pmaSelect === index.U
    val localPmpDeny = selected && selectedPmpDenied

    // A denied access still handshakes the child's private physical-request
    // lifetime locally. The wrapper then returns a synthetic fault response on
    // the following cycle, so the already-qualified child constructs the exact
    // same LoadAccessFault/completion shape without touching external memory.
    slot.io.memoryRequest.ready :=
      selected && Mux(localPmpDeny, true.B, selectedPermit && io.memoryRequest.ready)

    // A newly allocated child lifetime must never inherit launch/fault state
    // from an older token that previously occupied this fixed slot.
    when(slot.io.request.fire) {
      physicalLaunched(index) := false.B
      localPmpFaultPending(index) := false.B
    }
    when(slot.io.memoryRequest.fire) {
      physicalLaunched(index) := true.B
      childTxn(index) := slot.io.memoryRequest.bits.txnId
      when(localPmpDeny) {
        localPmpFaultPending(index) := true.B
        bypassableValid(index) := false.B
      }.otherwise {
        bypassableValid(index) := replaySafe
        bypassableToken(index) := slot.io.lifetimeStatus.robToken
      }
    }
    when(slot.io.completion.fire) {
      physicalLaunched(index) := false.B
      localPmpFaultPending(index) := false.B
      bypassableValid(index) := false.B
    }

    io.bypassable(index).valid := bypassableValid(index)
    io.bypassable(index).bits := bypassableToken(index)
  }

  when(io.memoryRequest.fire && !selectedIsHead) {
    assert(replaySafe, "pre-head dual-Load physical request must be replay-safe")
    assert(io.memoryRequest.bits.op === AetherMemOp.Read,
      "pre-head dual-Load unit may externalize reads only")
  }

  // --------------------------------------------------------------------------
  // Response demultiplexing by fixed external slot transaction ID
  // --------------------------------------------------------------------------
  val responseSlot = io.memoryResponse.bits.txnId(0)
  val responseOwnerValid = io.memoryResponse.bits.txnId < Slots.U

  for ((slot, index) <- slots.zipWithIndex) {
    val externalSelected =
      io.memoryResponse.valid && responseOwnerValid && responseSlot === index.U
    val localFault = localPmpFaultPending(index)

    slot.io.memoryResponse.valid := localFault || externalSelected
    slot.io.memoryResponse.bits := io.memoryResponse.bits
    slot.io.memoryResponse.bits.txnId := childTxn(index)
    when(localFault) {
      slot.io.memoryResponse.bits.rdata := 0.U
      slot.io.memoryResponse.bits.fault := true.B
      slot.io.memoryResponse.bits.last := true.B
    }

    when(localFault && slot.io.memoryResponse.fire) {
      localPmpFaultPending(index) := false.B
    }
  }

  io.memoryResponse.ready := responseOwnerValid && Mux(
    responseSlot === 0.U,
    slots(0).io.memoryResponse.ready && !localPmpFaultPending(0),
    slots(1).io.memoryResponse.ready && !localPmpFaultPending(1)
  )

  when(io.memoryResponse.fire) {
    assert(responseOwnerValid, "dual Load response txnId must identify slot 0 or 1")
  }

  // --------------------------------------------------------------------------
  // Completion and retirement-trace merge
  // --------------------------------------------------------------------------
  val completionArb = Module(new Arbiter(
    new ExecutionResponse(Xlen, IdentityBits, GenerationBits),
    Slots
  ))
  for ((slot, index) <- slots.zipWithIndex) {
    // Successful replay-safe Loads may complete before reaching the ROB head;
    // that is the latency-hiding contract qualified by #187/#189. Synchronous
    // exceptions are different: TinyRob's privileged recovery is accepted only
    // when the faulting completion names the exact architectural head. If a
    // pre-head fault escaped here, the ROB entry could become complete+faulted
    // before head ownership and later retire without the completion-time younger
    // squash. Keep the child LSU's existing held-completion state as the owner
    // until this exact token reaches head, then release the same exception.
    val exceptionMayEscape =
      !slot.io.completion.bits.exception.valid || slotHead(index)

    completionArb.io.in(index).valid :=
      slot.io.completion.valid && exceptionMayEscape
    completionArb.io.in(index).bits := slot.io.completion.bits
    slot.io.completion.ready :=
      completionArb.io.in(index).ready && exceptionMayEscape

    when(slot.io.completion.fire && slot.io.completion.bits.exception.valid) {
      assert(slotHead(index),
        "dual Load synchronous exception must complete only at exact ROB head")
    }
  }
  io.completion <> completionArb.io.out

  // The external physical response channel is one-wide, hence at most one load
  // trace can become valid in a cycle even though two reads may be outstanding.
  io.memoryTrace.valid := slots.map(_.io.memoryTrace.valid).reduce(_ || _)
  io.memoryTrace.bits := Mux(
    slots(0).io.memoryTrace.valid,
    slots(0).io.memoryTrace.bits,
    slots(1).io.memoryTrace.bits
  )
  assert(PopCount(VecInit(slots.map(_.io.memoryTrace.valid))) <= 1.U,
    "one-wide response link must produce at most one dual-Load trace per cycle")
}
