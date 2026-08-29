package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, MachineExceptionCode, MemSize}
import aethercore.config.PageTableGeometry
import aethercore.core.{DataPathAdapter, PmpChecker, PmpConstants, PmpGeometry}
import aethercore.memory.{AetherMemOp, AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * Narrow F6 request contract for one architectural memory uOp.
  *
  * ROB lifetime, dependency wakeup and value-storage identities remain distinct
  * across the LSU seam. The physical-memory transaction ID is intentionally not
  * part of this request: AetherMem transaction identity is allocated by the LSU
  * only when a physical request is actually issued.
  */
class TinyMemoryRequest(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"tiny-memory request XLEN must be 32 or 64, got $xlen")

  val robToken = new RobToken(identityBits, generationBits)
  val producerTag = new ProducerTag(identityBits, generationBits)
  val valueRef = new ValueRef(identityBits, generationBits)

  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()

  val base = UInt(xlen.W)
  val offset = UInt(xlen.W)
  val storeData = UInt(xlen.W)
  val rawInst = UInt(32.W)
}

/**
  * Physical memory observation kept outside ExecutionResponse.
  *
  * In particular, paddrBits is independent of XLEN: Sv32 needs a 34-bit PA
  * even though its architectural integer datapath is only 32 bits wide.
  */
class TinyMemoryTrace(
    val xlen: Int,
    val paddrBits: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val robToken = new RobToken(identityBits, generationBits)
  val paddr = UInt(paddrBits.W)
  val write = Bool()
  val wdata = UInt(xlen.W)
  val wmask = UInt((xlen / 8).W)
}

/**
  * Correctness-first one-outstanding LSU.
  *
  * F6 defaults to ordinary load/store only. F7 may opt into A-extension
  * transactions without changing the frozen F6 contract. Atomic RMW operations
  * cross AetherMem as one Atomic request; the LSU never decomposes an AMO into
  * a non-atomic Read/Write pair. LR/SC additionally keep a conservative local
  * reservation, while the memory system remains the final reservation/atomicity
  * authority for an externally issued SC.
  *
  * A8 makes the terminal completion Decoupled. A ready consumer sees the same
  * flow-through completion timing as F6/F7; under backpressure the complete
  * response is captured and the LSU retains the active transaction lifetime
  * until completion.fire. A terminal response can therefore never disappear
  * merely because another producer won the completion port that cycle.
  */
class TinyBlockingLsu(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2,
    val allowAtomics: Boolean = false
) extends Module {
  private val Xlen = geometry.xlen
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  private val BusBytes = Xlen / 8
  private val PmpAddressBits = PmpGeometry(Xlen, PhysicalBits).encodedAddressBits

  require(Xlen == 32 || Xlen == 64)
  require(PhysicalBits >= geometry.architecturalPhysicalAddressBits)
  require(txnIdBits > 0)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits)))
    val completion = Decoupled(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
    val memoryTrace = Valid(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))

    // Live exact-head permission. Ordinary stores plus SC/AMO writers must hold
    // this full RobToken before any externally visible write-like transaction.
    val storePermit = Flipped(Valid(new RobToken(IdentityBits, GenerationBits)))
    // Architectural boundaries may conservatively invalidate an LR reservation.
    // Keep this port absent in the frozen F6/default elaboration.
    val reservationClear = if (allowAtomics) Some(Input(Bool())) else None

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

    // PMA/attribute policy remains outside the LSU. The LSU exposes the
    // resolved physical address and consumes the resolved attributes.
    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)

    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))

    // P8.4-M1 observation only. No issue/bypass policy consumes this seam yet.
    val lifetimeStatus = Output(
      new TinyMemoryLifetimeStatus(Xlen, PhysicalBits, IdentityBits, GenerationBits)
    )
    val busy = Output(Bool())
  })

  def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  val active = Reg(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits))
  val busy = RegInit(false.B)
  val physicalIssued = RegInit(false.B)
  val activeTxn = RegInit(0.U(txnIdBits.W))
  val nextTxn = RegInit(0.U(txnIdBits.W))
  val completionHeldValid = RegInit(false.B)
  val completionHeldBits = Reg(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))

  // The local reservation is intentionally conservative. A matching SC still
  // crosses AetherMem as Atomic.Sc so external agents/multi-hart memory remain
  // able to reject the store conditionally.
  val reservationValid = if (allowAtomics) Some(RegInit(false.B)) else None
  val reservationAddress = if (allowAtomics) Some(Reg(UInt(PhysicalBits.W))) else None
  val reservationSize = if (allowAtomics) Some(Reg(MemSize())) else None

  io.request.ready := !busy
  io.busy := busy

  when(io.request.fire) {
    active := io.request.bits
    busy := true.B
    physicalIssued := false.B
  }

  // P8 LSU intake flow-through: while idle, the request accepted on this cycle
  // may drive the existing translation/PMP/PMA path immediately instead of
  // waiting one cycle for `active` to become visible. `active` still captures
  // the complete architectural lifetime for misses, physical response matching,
  // held completions and traces; this does not add another outstanding request.
  val workingRequest = Wire(new TinyMemoryRequest(Xlen, IdentityBits, GenerationBits))
  workingRequest := active
  when(!busy) {
    workingRequest := io.request.bits
  }
  val workingValid = busy || io.request.fire

  val effectiveAddress = workingRequest.base + workingRequest.offset
  val isLoad = workingRequest.kind === MemoryOperationKind.Load
  val isStore = workingRequest.kind === MemoryOperationKind.Store
  val isAtomic = workingRequest.kind === MemoryOperationKind.Atomic
  val atomicLr = isAtomic && workingRequest.atomicOp === AtomicOp.Lr
  val atomicSc = isAtomic && workingRequest.atomicOp === AtomicOp.Sc
  val atomicRmw = isAtomic && workingRequest.atomicOp =/= AtomicOp.None && !atomicLr && !atomicSc
  val atomicWriter = atomicSc || atomicRmw
  val accessIsLoad = isLoad || atomicLr
  val accessNeedsWritePermission = isStore || atomicWriter

  val ordinaryKind = isLoad || isStore
  val atomicKindSupported = allowAtomics.B && isAtomic && workingRequest.atomicOp =/= AtomicOp.None
  val supportedKind = ordinaryKind || atomicKindSupported
  val ordinarySizeSupported = if (Xlen == 32) workingRequest.size =/= MemSize.DWord else true.B
  val atomicSizeSupported = workingRequest.size === MemSize.Word ||
    (if (Xlen == 64) workingRequest.size === MemSize.DWord else false.B)
  val sizeSupported = Mux(isAtomic, atomicSizeSupported, ordinarySizeSupported)
  val ordinaryCarriesAtomicTag = ordinaryKind && workingRequest.atomicOp =/= AtomicOp.None
  val unsupported = workingValid && (!supportedKind || !sizeSupported || ordinaryCarriesAtomicTag)

  val accessBytes = WireDefault(BusBytes.U(4.W))
  val alignmentMask = WireDefault((BusBytes - 1).U(Xlen.W))
  val storeMask = WireDefault(((BigInt(1) << BusBytes) - 1).U(BusBytes.W))
  switch(workingRequest.size) {
    is(MemSize.Byte) {
      accessBytes := 1.U
      alignmentMask := 0.U
      storeMask := 1.U
    }
    is(MemSize.Half) {
      accessBytes := 2.U
      alignmentMask := 1.U
      storeMask := 3.U
    }
    is(MemSize.Word) {
      accessBytes := 4.U
      alignmentMask := 3.U
      storeMask := ((BigInt(1) << math.min(4, BusBytes)) - 1).U
    }
    is(MemSize.DWord) {
      accessBytes := 8.U
      alignmentMask := 7.U
      storeMask := ((BigInt(1) << BusBytes) - 1).U
    }
  }

  val misaligned = workingValid && supportedKind && sizeSupported &&
    ((effectiveAddress & alignmentMask) =/= 0.U)
  val localFault = unsupported || misaligned

  val adapter = Module(new DataPathAdapter(geometry, PhysicalBits, tlbEntries))
  adapter.io.requestValid := workingValid && !localFault && !completionHeldValid
  adapter.io.flush := io.translationFlush
  adapter.io.virtualAddress := effectiveAddress
  adapter.io.privilege := io.effectivePrivilege
  adapter.io.translateWrite := accessNeedsWritePermission
  adapter.io.write := accessNeedsWritePermission
  adapter.io.wdata := workingRequest.storeData
  adapter.io.wmask := storeMask
  adapter.io.size := workingRequest.size
  adapter.io.satpTranslationEnabled := io.satpTranslationEnabled
  adapter.io.satpRootPpn := io.satpRootPpn
  adapter.io.sum := io.supervisorSum
  adapter.io.mxr := io.supervisorMxr

  io.pteValid := adapter.io.pteValid
  io.pteAddress := adapter.io.pteAddress
  adapter.io.pteReady := io.pteReady
  adapter.io.pteData := io.pteData
  adapter.io.pteFault := io.pteFault

  val pmp = Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))
  pmp.io.privilege := io.effectivePrivilege
  pmp.io.address := adapter.io.dataAddress
  pmp.io.bytes := accessBytes
  pmp.io.write := accessNeedsWritePermission
  pmp.io.execute := false.B
  pmp.io.config := io.pmpConfig
  pmp.io.pmpAddress := io.pmpAddress

  val pmpDenied = adapter.io.dataValid && io.pmpEnabled && !pmp.io.allow
  val atomicPmaDenied = adapter.io.dataValid && isAtomic && !io.resolvedAttributes.supportsAtomic
  val permitMatches = io.storePermit.valid && sameRobToken(io.storePermit.bits, workingRequest.robToken)
  val writeMayExternalize = !accessNeedsWritePermission || permitMatches
  val localReservationMatches = if (allowAtomics) {
    reservationValid.get && reservationSize.get === workingRequest.size &&
      reservationAddress.get === adapter.io.dataAddress
  } else false.B
  val localScFailure = adapter.io.dataValid && atomicSc && !localReservationMatches

  io.resolvedPhysicalValid := adapter.io.dataValid
  io.resolvedPhysicalAddress := adapter.io.dataAddress

  io.memoryRequest.valid := adapter.io.dataValid && !pmpDenied && !atomicPmaDenied &&
    !localScFailure && !physicalIssued && writeMayExternalize && !completionHeldValid
  io.memoryRequest.bits.txnId := nextTxn
  io.memoryRequest.bits.op := Mux(
    isAtomic,
    AetherMemOp.Atomic,
    Mux(isStore, AetherMemOp.Write, AetherMemOp.Read)
  )
  io.memoryRequest.bits.paddr := adapter.io.dataAddress
  io.memoryRequest.bits.size := workingRequest.size
  io.memoryRequest.bits.wdata := workingRequest.storeData
  io.memoryRequest.bits.wmask := Mux(accessNeedsWritePermission, storeMask, 0.U)
  io.memoryRequest.bits.atomicOp := Mux(isAtomic, workingRequest.atomicOp, AtomicOp.None)
  io.memoryRequest.bits.attributes := io.resolvedAttributes

  when(io.memoryRequest.fire) {
    physicalIssued := true.B
    activeTxn := nextTxn
    nextTxn := nextTxn + 1.U
  }

  // A one-outstanding LSU may discard a stale response with a different
  // transaction ID, but only the exact active ID can complete the adapter.
  // Once a terminal response has been captured, stop consuming physical
  // responses until the architectural completion transport accepts it.
  io.memoryResponse.ready := physicalIssued && !completionHeldValid
  val matchingResponse = io.memoryResponse.fire && io.memoryResponse.bits.txnId === activeTxn
  adapter.io.dataReady := pmpDenied || atomicPmaDenied || localScFailure || matchingResponse
  adapter.io.dataRdata := io.memoryResponse.bits.rdata
  // Multi-beat physical responses remain outside the blocking slice. PMA/PMP
  // denials are local access faults; a local SC reservation miss is a normal
  // architectural SC failure, not a fault.
  adapter.io.dataFault := pmpDenied || atomicPmaDenied ||
    (matchingResponse && (io.memoryResponse.bits.fault || !io.memoryResponse.bits.last))

  def extendedLoad(data: UInt): UInt = {
    val result = WireDefault(data)
    switch(active.size) {
      is(MemSize.Byte) {
        val byte = data(7, 0)
        result := Mux(active.unsigned, byte.pad(Xlen), Cat(Fill(Xlen - 8, byte(7)), byte))
      }
      is(MemSize.Half) {
        val half = data(15, 0)
        result := Mux(active.unsigned, half.pad(Xlen), Cat(Fill(Xlen - 16, half(15)), half))
      }
      is(MemSize.Word) {
        val word = data(31, 0)
        if (Xlen == 32) result := word
        else result := Mux(active.unsigned, word.pad(Xlen), Cat(Fill(Xlen - 32, word(31)), word))
      }
      is(MemSize.DWord) {
        result := data
      }
    }
    result
  }

  def extendedAtomicOld(data: UInt): UInt = {
    if (Xlen == 32) {
      data
    } else {
      Mux(
        active.size === MemSize.Word,
        Cat(Fill(32, data(31)), data(31, 0)),
        data
      )
    }
  }

  // Compute the architectural write value only for trace/debug visibility.
  // The actual indivisible RMW is performed by AetherMemOp.Atomic downstream.
  val atomicOld = io.memoryResponse.bits.rdata
  val atomicSignedOperand = if (Xlen == 64) {
    Mux(
      active.size === MemSize.Word,
      Cat(Fill(32, active.storeData(31)), active.storeData(31, 0)),
      active.storeData
    )
  } else active.storeData
  val atomicUnsignedOperand = if (Xlen == 64) {
    Mux(
      active.size === MemSize.Word,
      Cat(0.U(32.W), active.storeData(31, 0)),
      active.storeData
    )
  } else active.storeData
  val atomicSignedOld = if (Xlen == 64) {
    Mux(active.size === MemSize.Word, Cat(Fill(32, atomicOld(31)), atomicOld(31, 0)), atomicOld)
  } else atomicOld
  val atomicUnsignedOld = if (Xlen == 64) {
    Mux(active.size === MemSize.Word, Cat(0.U(32.W), atomicOld(31, 0)), atomicOld)
  } else atomicOld

  val atomicWriteData = WireDefault(active.storeData)
  switch(active.atomicOp) {
    is(AtomicOp.Swap) { atomicWriteData := active.storeData }
    is(AtomicOp.Add)  { atomicWriteData := atomicOld + active.storeData }
    is(AtomicOp.Xor)  { atomicWriteData := atomicOld ^ active.storeData }
    is(AtomicOp.And)  { atomicWriteData := atomicOld & active.storeData }
    is(AtomicOp.Or)   { atomicWriteData := atomicOld | active.storeData }
    is(AtomicOp.Min)  {
      atomicWriteData := Mux(
        atomicSignedOld.asSInt < atomicSignedOperand.asSInt,
        atomicSignedOld,
        atomicSignedOperand
      )
    }
    is(AtomicOp.Max)  {
      atomicWriteData := Mux(
        atomicSignedOld.asSInt > atomicSignedOperand.asSInt,
        atomicSignedOld,
        atomicSignedOperand
      )
    }
    is(AtomicOp.Minu) {
      atomicWriteData := Mux(atomicUnsignedOld < atomicUnsignedOperand, atomicUnsignedOld, atomicUnsignedOperand)
    }
    is(AtomicOp.Maxu) {
      atomicWriteData := Mux(atomicUnsignedOld > atomicUnsignedOperand, atomicUnsignedOld, atomicUnsignedOperand)
    }
  }

  private val freshCompletion = Wire(Valid(
    new ExecutionResponse(Xlen, IdentityBits, GenerationBits)
  ))
  freshCompletion.valid := false.B
  freshCompletion.bits := 0.U.asTypeOf(new ExecutionResponse(Xlen, IdentityBits, GenerationBits))
  freshCompletion.bits.robToken := active.robToken
  freshCompletion.bits.producerTag := active.producerTag
  freshCompletion.bits.valueRef := active.valueRef

  // Preserve the pre-P8 local-fault/completion timing: intake may start address
  // processing immediately, but architectural completion still belongs to the
  // registered active lifetime from the following cycle onward.
  val adapterDone = busy && !localFault && adapter.io.requestComplete && !completionHeldValid
  when(busy && localFault && !completionHeldValid) {
    freshCompletion.valid := true.B
    freshCompletion.bits.exception.valid := true.B
    when(unsupported) {
      freshCompletion.bits.exception.cause := MachineExceptionCode.IllegalInstruction.U
      freshCompletion.bits.exception.value := active.rawInst.pad(Xlen)
    }.otherwise {
      freshCompletion.bits.exception.cause := Mux(
        accessIsLoad,
        MachineExceptionCode.LoadAddressMisaligned.U,
        MachineExceptionCode.StoreAddressMisaligned.U
      )
      freshCompletion.bits.exception.value := effectiveAddress
    }
  }.elsewhen(adapterDone) {
    val fault = adapter.io.pageFault || adapter.io.accessFault
    freshCompletion.valid := true.B
    freshCompletion.bits.hasValue := (isLoad || isAtomic) && !fault
    freshCompletion.bits.value := Mux(
      atomicSc,
      Mux(localScFailure, 1.U, adapter.io.readData),
      Mux(isAtomic, extendedAtomicOld(adapter.io.readData), extendedLoad(adapter.io.readData))
    )
    freshCompletion.bits.exception.valid := fault
    freshCompletion.bits.exception.cause := Mux(
      adapter.io.pageFault,
      Mux(accessIsLoad, MachineExceptionCode.LoadPageFault.U, MachineExceptionCode.StorePageFault.U),
      Mux(accessIsLoad, MachineExceptionCode.LoadAccessFault.U, MachineExceptionCode.StoreAccessFault.U)
    )
    freshCompletion.bits.exception.value := effectiveAddress
  }

  io.completion.valid := completionHeldValid || freshCompletion.valid
  io.completion.bits := Mux(completionHeldValid, completionHeldBits, freshCompletion.bits)

  // P8.4-M1 exports facts about the current LSU lifetime without changing any
  // scheduling or visibility decision. workingValid preserves the existing
  // intake flow-through: a request accepted while idle is observable this cycle.
  io.lifetimeStatus := 0.U.asTypeOf(
    new TinyMemoryLifetimeStatus(Xlen, PhysicalBits, IdentityBits, GenerationBits)
  )
  io.lifetimeStatus.drained := !workingValid
  when(workingValid) {
    io.lifetimeStatus.valid := true.B
    io.lifetimeStatus.robToken := workingRequest.robToken
    io.lifetimeStatus.kind := workingRequest.kind
    io.lifetimeStatus.atomicOp := workingRequest.atomicOp
    io.lifetimeStatus.size := workingRequest.size
    io.lifetimeStatus.effectiveAddress := effectiveAddress
    io.lifetimeStatus.writeLike := accessNeedsWritePermission
    io.lifetimeStatus.physicalAddressValid := adapter.io.dataValid
    io.lifetimeStatus.physicalAddress := adapter.io.dataAddress
    io.lifetimeStatus.attributesValid := adapter.io.dataValid
    io.lifetimeStatus.attributes := io.resolvedAttributes
    io.lifetimeStatus.writePermitMatched := accessNeedsWritePermission && permitMatches
    io.lifetimeStatus.physicalRequestIssued := physicalIssued || io.memoryRequest.fire
    io.lifetimeStatus.completionPending := io.completion.valid
  }

  // Backpressure converts the flow-through response into an owned held response.
  // Bits are captured exactly once, then remain stable until completion.fire.
  when(freshCompletion.valid && !io.completion.ready) {
    completionHeldValid := true.B
    completionHeldBits := freshCompletion.bits
  }

  val physicalSuccess = matchingResponse && io.memoryResponse.bits.last && !io.memoryResponse.bits.fault
  val scSucceeded = atomicSc && physicalSuccess && io.memoryResponse.bits.rdata === 0.U
  val traceValid = physicalSuccess && (!atomicSc || scSucceeded)
  val traceWrite = isStore || atomicRmw || scSucceeded

  io.memoryTrace.valid := traceValid
  io.memoryTrace.bits := 0.U.asTypeOf(new TinyMemoryTrace(Xlen, PhysicalBits, IdentityBits, GenerationBits))
  io.memoryTrace.bits.robToken := active.robToken
  io.memoryTrace.bits.paddr := adapter.io.dataAddress
  io.memoryTrace.bits.write := traceWrite
  io.memoryTrace.bits.wdata := Mux(
    atomicRmw,
    atomicWriteData,
    Mux(isStore || scSucceeded, active.storeData, 0.U)
  )
  io.memoryTrace.bits.wmask := Mux(traceWrite, storeMask, 0.U)

  if (allowAtomics) {
    val completedWithoutFault = adapterDone && !adapter.io.pageFault && !adapter.io.accessFault
    when(completedWithoutFault && atomicLr && physicalSuccess) {
      reservationValid.get := true.B
      reservationAddress.get := adapter.io.dataAddress
      reservationSize.get := active.size
    }.elsewhen(completedWithoutFault && (atomicSc || atomicRmw || isStore)) {
      reservationValid.get := false.B
    }
    when(io.reservationClear.get) {
      reservationValid.get := false.B
    }
  }

  // The memory uOp lifetime is released only when its completion is actually
  // accepted. This is the key A8 difference from F6/F7 Valid-only transport.
  when(io.completion.fire) {
    busy := false.B
    physicalIssued := false.B
    completionHeldValid := false.B
  }
}
