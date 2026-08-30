package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize, PrivilegeMode}
import aethercore.config.PageTableGeometry
import aethercore.core.{
  PageTableWalker,
  PhysicalAddressNarrowing,
  PmpChecker,
  PmpConstants,
  PmpGeometry,
  TranslationTlb
}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * One shared, non-blocking data-translation front end for the two ordinary
  * Load lifetimes.
  *
  * The previous LoadQ2 implementation reused two complete TinyBlockingLsu
  * children.  That was a useful correctness-first bring-up shape, but it also
  * duplicated the fully-associative Sv39 TLB and page walker.  This module
  * keeps a single TLB lookup port and one walker while allowing an unresolved
  * speculative miss to yield the lookup port to the other slot.  Only the exact
  * ROB head may start a page-table walk, preserving the frozen speculative-PTW
  * rule.
  */
class TinySharedLoadTranslator(
    val geometry: PageTableGeometry,
    val paddrBits: Int,
    val tlbEntries: Int,
    val ownerBits: Int
) extends Module {
  private val Xlen = geometry.xlen
  private val ArchitecturalPaddrBits = geometry.architecturalPhysicalAddressBits
  private val LevelBits = math.max(1, log2Ceil(geometry.levels))

  val io = IO(new Bundle {
    val lookupValid = Input(Bool())
    val lookupOwner = Input(UInt(ownerBits.W))
    val allowWalk = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val privilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())

    val responseValid = Output(Bool())
    val responseOwner = Output(UInt(ownerBits.W))
    val physicalAddress = Output(UInt(paddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())

    val walkingOwnerValid = Output(Bool())
    val walkingOwner = Output(UInt(ownerBits.W))

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(paddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())
  })

  val tlb = Module(new TranslationTlb(geometry, tlbEntries))
  val walker = Module(new PageTableWalker(geometry))

  val walkOwnerValid = RegInit(false.B)
  val walkOwner = RegInit(0.U(ownerBits.W))
  val walkVirtualAddress = Reg(UInt(Xlen.W))
  val walkRootPpn = Reg(UInt(geometry.ppnBits.W))
  val walkPrivilege = Reg(UInt(2.W))
  val walkSum = Reg(Bool())
  val walkMxr = Reg(Bool())

  io.walkingOwnerValid := walkOwnerValid
  io.walkingOwner := walkOwner

  val walkerResponding = walkOwnerValid && walker.io.responseValid
  val translationRequired =
    io.satpTranslationEnabled && io.privilege =/= PrivilegeMode.Machine.U
  val lookupActive = io.lookupValid && !io.flush && !walkerResponding

  tlb.io.lookupValid := lookupActive && translationRequired
  tlb.io.virtualAddress := io.virtualAddress
  tlb.io.rootPpn := io.satpRootPpn
  tlb.io.privilege := io.privilege
  tlb.io.write := false.B
  tlb.io.execute := false.B
  tlb.io.sum := io.sum
  tlb.io.mxr := io.mxr
  tlb.io.flush := io.flush

  val startWalk =
    lookupActive && translationRequired && !tlb.io.hit &&
      io.allowWalk && !walkOwnerValid && walker.io.requestReady

  walker.io.requestValid := startWalk
  walker.io.kill := io.flush
  walker.io.virtualAddress := io.virtualAddress
  walker.io.rootPpn := io.satpRootPpn
  walker.io.privilege := io.privilege
  walker.io.write := false.B
  walker.io.execute := false.B
  walker.io.sum := io.sum
  walker.io.mxr := io.mxr
  walker.io.pteReady := io.pteReady
  walker.io.pteData := io.pteData
  walker.io.pteFault := io.pteFault
  walker.io.responseReady := true.B

  io.pteValid := walker.io.pteValid && !io.flush
  io.pteAddress := walker.io.pteAddress.pad(paddrBits)

  val refill =
    walkOwnerValid && walker.io.responseValid &&
      !walker.io.pageFault && !walker.io.accessFault && !io.flush
  tlb.io.refillValid := refill
  tlb.io.refillVirtualAddress := walkVirtualAddress
  tlb.io.refillPhysicalAddress := walker.io.physicalAddress
  tlb.io.refillRootPpn := walkRootPpn
  tlb.io.refillPrivilege := walkPrivilege
  tlb.io.refillWrite := false.B
  tlb.io.refillExecute := false.B
  tlb.io.refillSum := walkSum
  tlb.io.refillMxr := walkMxr
  tlb.io.refillLeafLevel := walker.io.leafLevel
  tlb.io.refillGlobal := walker.io.global

  val (barePhysical, bareOutOfRange) =
    PhysicalAddressNarrowing(io.virtualAddress, ArchitecturalPaddrBits)

  val instantValid =
    lookupActive && (!translationRequired || tlb.io.hit)

  io.responseValid := walkerResponding || instantValid
  io.responseOwner := Mux(walkerResponding, walkOwner, io.lookupOwner)
  io.physicalAddress := Mux(
    walkerResponding,
    walker.io.physicalAddress.pad(paddrBits),
    Mux(translationRequired, tlb.io.physicalAddress.pad(paddrBits), barePhysical.pad(paddrBits))
  )
  io.pageFault := walkerResponding && walker.io.pageFault
  io.accessFault := Mux(
    walkerResponding,
    walker.io.accessFault,
    instantValid && !translationRequired && bareOutOfRange
  )

  when(startWalk) {
    walkOwnerValid := true.B
    walkOwner := io.lookupOwner
    walkVirtualAddress := io.virtualAddress
    walkRootPpn := io.satpRootPpn
    walkPrivilege := io.privilege
    walkSum := io.sum
    walkMxr := io.mxr
  }

  when(walkerResponding || io.flush) {
    walkOwnerValid := false.B
  }
}

/**
  * Area-oriented replacement for TinyDualReplaySafeLoadUnit.
  *
  * Two ordinary Loads remain independently outstanding on AetherMem, but the
  * shareable front-end machinery is no longer duplicated:
  *
  *   two request lifetimes -> one shared Sv39 TLB/PTW -> one shared PMP/PMA lane
  *
  * Each slot owns only request/translation/physical/completion lifetime state.
  * A speculative TLB miss never starts PTW traffic; it remains resident and the
  * shared lookup port may service the other slot on the following cycle.
  */
class TinySharedTranslationLoadUnit(
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
  private val BusBytes = Xlen / 8
  private val Slots = 2
  private val SlotBits = 1

  require(txnIdBits >= 1, "shared-translation LoadQ2 needs at least one external transaction-id bit")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
    val completion = Decoupled(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
    val memoryTrace = Valid(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

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

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))

    val bypassable = Output(Vec(Slots, Valid(new RobToken(IdentityBits, GenerationBits))))
    val busy = Output(Bool())
    val full = Output(Bool())
  })

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val slotBusy = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val slotRequest = Reg(Vec(Slots, new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
  val slotTranslated = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val slotPhysicalAddress = Reg(Vec(Slots, UInt(PhysicalBits.W)))
  val slotPhysicalIssued = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val slotCompletionValid = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val slotCompletionBits =
    Reg(Vec(Slots, new ExecutionResponse(Xlen, IdentityBits, GenerationBits)))
  val bypassableValid = RegInit(VecInit(Seq.fill(Slots)(false.B)))
  val bypassableToken =
    Reg(Vec(Slots, new RobToken(IdentityBits, GenerationBits)))

  val free = VecInit((0 until Slots).map(i => !slotBusy(i)))
  val hasFree = free.asUInt.orR
  val allocIndex = PriorityEncoder(free.asUInt)
  io.request.ready := hasFree
  val allocFire = io.request.fire

  val workingValid = Wire(Vec(Slots, Bool()))
  val workingRequest = Wire(Vec(Slots, new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
  for (i <- 0 until Slots) {
    val fresh = allocFire && allocIndex === i.U
    workingValid(i) := slotBusy(i) || fresh
    workingRequest(i) := Mux(slotBusy(i), slotRequest(i), io.request.bits)
  }

  when(allocFire) {
    assert(io.request.bits.kind === MemoryOperationKind.Load,
      "shared-translation LoadQ2 accepts ordinary Loads only")
    assert(io.request.bits.atomicOp === AtomicOp.None,
      "shared-translation LoadQ2 must not accept LR/SC/AMO")
  }

  for (i <- 0 until Slots) {
    when(allocFire && allocIndex === i.U) {
      slotBusy(i) := true.B
      slotRequest(i) := io.request.bits
      slotTranslated(i) := false.B
      slotPhysicalIssued(i) := false.B
      slotCompletionValid(i) := false.B
      bypassableValid(i) := false.B
    }
  }

  io.busy := slotBusy.asUInt.orR
  io.full := slotBusy.asUInt.andR

  val effectiveAddress = Wire(Vec(Slots, UInt(Xlen.W)))
  val localUnsupported = Wire(Vec(Slots, Bool()))
  val localMisaligned = Wire(Vec(Slots, Bool()))
  val localFault = Wire(Vec(Slots, Bool()))
  val workingHead = Wire(Vec(Slots, Bool()))

  for (i <- 0 until Slots) {
    val req = workingRequest(i)
    effectiveAddress(i) := req.base + req.offset

    val alignmentMask = WireDefault((BusBytes - 1).U(Xlen.W))
    switch(req.size) {
      is(MemSize.Byte)  { alignmentMask := 0.U }
      is(MemSize.Half)  { alignmentMask := 1.U }
      is(MemSize.Word)  { alignmentMask := 3.U }
      is(MemSize.DWord) { alignmentMask := 7.U }
    }

    val sizeSupported =
      if (Xlen == 32) req.size =/= MemSize.DWord else true.B
    localUnsupported(i) :=
      workingValid(i) &&
        (req.kind =/= MemoryOperationKind.Load ||
          req.atomicOp =/= AtomicOp.None || !sizeSupported)
    localMisaligned(i) :=
      workingValid(i) && !localUnsupported(i) &&
        ((effectiveAddress(i) & alignmentMask) =/= 0.U)
    localFault(i) := localUnsupported(i) || localMisaligned(i)

    workingHead(i) :=
      workingValid(i) && io.head.valid &&
        sameToken(req.robToken, io.head.bits)
  }

  // Local decode/alignment faults are owned immediately, but their exception
  // completion is held until the exact token reaches head.
  for (i <- 0 until Slots) {
    when(localFault(i) && !slotCompletionValid(i) && !slotPhysicalIssued(i)) {
      slotCompletionValid(i) := true.B
      slotCompletionBits(i) :=
        0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
      slotCompletionBits(i).robToken := workingRequest(i).robToken
      slotCompletionBits(i).producerTag := workingRequest(i).producerTag
      slotCompletionBits(i).valueRef := workingRequest(i).valueRef
      slotCompletionBits(i).exception.valid := true.B
      slotCompletionBits(i).exception.cause := Mux(
        localUnsupported(i),
        MachineExceptionCode.IllegalInstruction.U,
        MachineExceptionCode.LoadAddressMisaligned.U
      )
      slotCompletionBits(i).exception.value := Mux(
        localUnsupported(i),
        workingRequest(i).rawInst.pad(Xlen),
        effectiveAddress(i)
      )
    }
  }

  // --------------------------------------------------------------------------
  // One shared TLB/PTW for both Load lifetimes.
  // --------------------------------------------------------------------------
  val translator = Module(new TinySharedLoadTranslator(
    geometry,
    paddrBits = PhysicalBits,
    tlbEntries = tlbEntries,
    ownerBits = SlotBits
  ))

  val needsTranslation = Wire(Vec(Slots, Bool()))
  for (i <- 0 until Slots) {
    val walkingThisSlot =
      translator.io.walkingOwnerValid && translator.io.walkingOwner === i.U
    needsTranslation(i) :=
      workingValid(i) && !localFault(i) &&
        !slotTranslated(i) && !slotPhysicalIssued(i) &&
        !slotCompletionValid(i) && !walkingThisSlot
  }

  val translateCursor = RegInit(0.U(SlotBits.W))
  val headTranslation0 = needsTranslation(0) && workingHead(0)
  val headTranslation1 = needsTranslation(1) && workingHead(1)
  val anyTranslation = needsTranslation.asUInt.orR
  val cursorNeeds = Mux(translateCursor === 0.U, needsTranslation(0), needsTranslation(1))
  val translateSelect = Mux(
    headTranslation0,
    0.U,
    Mux(
      headTranslation1,
      1.U,
      Mux(cursorNeeds, translateCursor, ~translateCursor)
    )
  )(SlotBits - 1, 0)
  val translateSelectValid = anyTranslation

  val selectedTranslateRequest = Mux1H(
    UIntToOH(translateSelect, Slots),
    workingRequest
  )
  val selectedTranslateAddress = Mux1H(
    UIntToOH(translateSelect, Slots),
    effectiveAddress
  )
  val selectedTranslateIsHead = Mux1H(
    UIntToOH(translateSelect, Slots),
    workingHead
  )

  translator.io.lookupValid := translateSelectValid
  translator.io.lookupOwner := translateSelect
  translator.io.allowWalk := selectedTranslateIsHead
  translator.io.virtualAddress := selectedTranslateAddress
  translator.io.privilege := io.effectivePrivilege
  translator.io.satpTranslationEnabled := io.satpTranslationEnabled
  translator.io.satpRootPpn := io.satpRootPpn
  translator.io.sum := io.supervisorSum
  translator.io.mxr := io.supervisorMxr
  translator.io.flush := io.translationFlush

  io.pteValid := translator.io.pteValid
  io.pteAddress := translator.io.pteAddress
  translator.io.pteReady := io.pteReady
  translator.io.pteData := io.pteData
  translator.io.pteFault := io.pteFault

  when(translateSelectValid && !selectedTranslateIsHead) {
    translateCursor := ~translateSelect
  }

  val freshTranslation = Wire(Vec(Slots, Bool()))
  val freshTranslationSuccess = Wire(Vec(Slots, Bool()))
  for (i <- 0 until Slots) {
    freshTranslation(i) :=
      translator.io.responseValid && translator.io.responseOwner === i.U
    freshTranslationSuccess(i) :=
      freshTranslation(i) &&
        !translator.io.pageFault && !translator.io.accessFault

    when(freshTranslationSuccess(i)) {
      slotTranslated(i) := true.B
      slotPhysicalAddress(i) := translator.io.physicalAddress
    }

    when(freshTranslation(i) &&
         (translator.io.pageFault || translator.io.accessFault) &&
         !slotCompletionValid(i)) {
      slotCompletionValid(i) := true.B
      slotCompletionBits(i) :=
        0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
      slotCompletionBits(i).robToken := workingRequest(i).robToken
      slotCompletionBits(i).producerTag := workingRequest(i).producerTag
      slotCompletionBits(i).valueRef := workingRequest(i).valueRef
      slotCompletionBits(i).exception.valid := true.B
      slotCompletionBits(i).exception.cause := Mux(
        translator.io.pageFault,
        MachineExceptionCode.LoadPageFault.U,
        MachineExceptionCode.LoadAccessFault.U
      )
      slotCompletionBits(i).exception.value := effectiveAddress(i)
    }

    when(io.translationFlush && slotBusy(i) &&
         !slotPhysicalIssued(i) && !slotCompletionValid(i)) {
      slotTranslated(i) := false.B
    }
  }

  // --------------------------------------------------------------------------
  // One shared PMA + PMP + physical-launch lane.
  // --------------------------------------------------------------------------
  val resolved = Wire(Vec(Slots, Bool()))
  val resolvedAddress = Wire(Vec(Slots, UInt(PhysicalBits.W)))
  for (i <- 0 until Slots) {
    resolved(i) :=
      workingValid(i) && !localFault(i) &&
        !slotPhysicalIssued(i) && !slotCompletionValid(i) &&
        (slotTranslated(i) || freshTranslationSuccess(i))
    resolvedAddress(i) := Mux(
      slotTranslated(i),
      slotPhysicalAddress(i),
      translator.io.physicalAddress
    )
  }

  val headResolved0 = resolved(0) && workingHead(0)
  val headResolved1 = resolved(1) && workingHead(1)
  val pmaSelectValid = resolved.asUInt.orR
  val pmaSelect = Mux(
    headResolved0,
    0.U,
    Mux(headResolved1, 1.U, PriorityEncoder(resolved.asUInt))
  )(SlotBits - 1, 0)

  val selectedRequest = Mux1H(UIntToOH(pmaSelect, Slots), workingRequest)
  val selectedPhysicalAddress = Mux1H(UIntToOH(pmaSelect, Slots), resolvedAddress)
  val selectedIsHead = Mux1H(UIntToOH(pmaSelect, Slots), workingHead)

  io.resolvedPhysicalValid := pmaSelectValid
  io.resolvedPhysicalAddress := selectedPhysicalAddress

  val replaySafe =
    io.resolvedAttributes.idempotent &&
      !io.resolvedAttributes.sideEffecting &&
      !io.resolvedAttributes.ordered

  val selectedAccessBytes = WireDefault(BusBytes.U(4.W))
  switch(selectedRequest.size) {
    is(MemSize.Byte)  { selectedAccessBytes := 1.U }
    is(MemSize.Half)  { selectedAccessBytes := 2.U }
    is(MemSize.Word)  { selectedAccessBytes := 4.U }
    is(MemSize.DWord) { selectedAccessBytes := 8.U }
  }

  val sharedPmp =
    Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))
  sharedPmp.io.privilege := io.effectivePrivilege
  sharedPmp.io.address := selectedPhysicalAddress
  sharedPmp.io.bytes := selectedAccessBytes
  sharedPmp.io.write := false.B
  sharedPmp.io.execute := false.B
  sharedPmp.io.config := io.pmpConfig
  sharedPmp.io.pmpAddress := io.pmpAddress

  val selectedPmpDenied =
    pmaSelectValid && io.pmpEnabled && !sharedPmp.io.allow
  val selectedPermit =
    pmaSelectValid && !selectedPmpDenied &&
      (selectedIsHead || replaySafe)

  io.memoryRequest.valid := selectedPermit
  io.memoryRequest.bits :=
    0.U.asTypeOf(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
  io.memoryRequest.bits.txnId := pmaSelect
  io.memoryRequest.bits.op := AetherMemOp.Read
  io.memoryRequest.bits.paddr := selectedPhysicalAddress
  io.memoryRequest.bits.size := selectedRequest.size
  io.memoryRequest.bits.wdata := 0.U
  io.memoryRequest.bits.wmask := 0.U
  io.memoryRequest.bits.atomicOp := AtomicOp.None
  io.memoryRequest.bits.attributes := io.resolvedAttributes

  for (i <- 0 until Slots) {
    when(selectedPmpDenied && pmaSelect === i.U && !slotCompletionValid(i)) {
      slotCompletionValid(i) := true.B
      slotCompletionBits(i) :=
        0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
      slotCompletionBits(i).robToken := workingRequest(i).robToken
      slotCompletionBits(i).producerTag := workingRequest(i).producerTag
      slotCompletionBits(i).valueRef := workingRequest(i).valueRef
      slotCompletionBits(i).exception.valid := true.B
      slotCompletionBits(i).exception.cause :=
        MachineExceptionCode.LoadAccessFault.U
      slotCompletionBits(i).exception.value := effectiveAddress(i)
      bypassableValid(i) := false.B
    }

    when(io.memoryRequest.fire && pmaSelect === i.U) {
      slotPhysicalIssued(i) := true.B
      slotPhysicalAddress(i) := selectedPhysicalAddress
      bypassableValid(i) := replaySafe
      bypassableToken(i) := workingRequest(i).robToken
    }

    io.bypassable(i).valid := bypassableValid(i)
    io.bypassable(i).bits := bypassableToken(i)
  }

  when(io.memoryRequest.fire && !selectedIsHead) {
    assert(replaySafe,
      "pre-head shared-translation Load physical request must be replay-safe")
    assert(io.memoryRequest.bits.op === AetherMemOp.Read,
      "pre-head shared-translation Load unit may externalize reads only")
  }

  // --------------------------------------------------------------------------
  // Physical response capture.  A terminal response is converted into a
  // slot-owned completion register so completion arbitration can backpressure
  // without backpressuring an already-returned memory transaction.
  // --------------------------------------------------------------------------
  def extendedLoad(req: TinyMemoryRequest, data: UInt): UInt = {
    val result = WireDefault(data)
    switch(req.size) {
      is(MemSize.Byte) {
        val byte = data(7, 0)
        result := Mux(req.unsigned, byte.pad(Xlen), Cat(Fill(Xlen - 8, byte(7)), byte))
      }
      is(MemSize.Half) {
        val half = data(15, 0)
        result := Mux(req.unsigned, half.pad(Xlen), Cat(Fill(Xlen - 16, half(15)), half))
      }
      is(MemSize.Word) {
        val word = data(31, 0)
        if (Xlen == 32) result := word
        else result := Mux(req.unsigned, word.pad(Xlen), Cat(Fill(Xlen - 32, word(31)), word))
      }
      is(MemSize.DWord) {
        result := data
      }
    }
    result
  }

  val responseOwnerValid = io.memoryResponse.bits.txnId < Slots.U
  val responseSlot = io.memoryResponse.bits.txnId(0)
  io.memoryResponse.ready :=
    responseOwnerValid &&
      Mux(
        responseSlot === 0.U,
        slotBusy(0) && slotPhysicalIssued(0) && !slotCompletionValid(0),
        slotBusy(1) && slotPhysicalIssued(1) && !slotCompletionValid(1)
      )

  io.memoryTrace.valid := false.B
  io.memoryTrace.bits :=
    0.U.asTypeOf(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

  for (i <- 0 until Slots) {
    val responseFire =
      io.memoryResponse.fire && responseOwnerValid && responseSlot === i.U
    when(responseFire) {
      val failed =
        io.memoryResponse.bits.fault || !io.memoryResponse.bits.last
      slotCompletionValid(i) := true.B
      slotCompletionBits(i) :=
        0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
      slotCompletionBits(i).robToken := slotRequest(i).robToken
      slotCompletionBits(i).producerTag := slotRequest(i).producerTag
      slotCompletionBits(i).valueRef := slotRequest(i).valueRef
      slotCompletionBits(i).hasValue := !failed
      slotCompletionBits(i).value :=
        extendedLoad(slotRequest(i), io.memoryResponse.bits.rdata)
      slotCompletionBits(i).exception.valid := failed
      slotCompletionBits(i).exception.cause :=
        MachineExceptionCode.LoadAccessFault.U
      slotCompletionBits(i).exception.value :=
        slotRequest(i).base + slotRequest(i).offset

      when(!failed) {
        io.memoryTrace.valid := true.B
        io.memoryTrace.bits.robToken := slotRequest(i).robToken
        io.memoryTrace.bits.paddr := slotPhysicalAddress(i)
        io.memoryTrace.bits.write := false.B
        io.memoryTrace.bits.wdata := 0.U
        io.memoryTrace.bits.wmask := 0.U
      }
    }
  }

  when(io.memoryResponse.fire) {
    assert(responseOwnerValid,
      "shared-translation Load response txnId must identify slot 0 or 1")
  }

  // --------------------------------------------------------------------------
  // Completion release.  Normal successful Loads may complete pre-head.
  // Synchronous faults stay resident until their exact token owns ROB head.
  // --------------------------------------------------------------------------
  val completionArb =
    Module(new Arbiter(new ExecutionResponse(Xlen, IdentityBits, GenerationBits), Slots))
  for (i <- 0 until Slots) {
    val registeredHead =
      slotBusy(i) && io.head.valid &&
        sameToken(slotRequest(i).robToken, io.head.bits)
    val exceptionMayEscape =
      !slotCompletionBits(i).exception.valid || registeredHead

    completionArb.io.in(i).valid :=
      slotCompletionValid(i) && exceptionMayEscape
    completionArb.io.in(i).bits := slotCompletionBits(i)

    when(completionArb.io.in(i).fire) {
      slotBusy(i) := false.B
      slotTranslated(i) := false.B
      slotPhysicalIssued(i) := false.B
      slotCompletionValid(i) := false.B
      bypassableValid(i) := false.B
    }

    when(completionArb.io.in(i).fire &&
         completionArb.io.in(i).bits.exception.valid) {
      assert(registeredHead,
        "shared-translation Load synchronous exception must complete only at exact ROB head")
    }
  }
  io.completion <> completionArb.io.out
}
