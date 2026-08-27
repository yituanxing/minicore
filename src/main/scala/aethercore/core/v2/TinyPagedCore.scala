package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{CommitTrace, InstructionBusIO, MachineExceptionCode, PageTableReadBusIO, PrivilegeMode, TrapInfo}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.{InstructionFetchAdapter, PmpChecker, PmpConstants, PtwArbiter, RvcParcelController}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * F7 instruction-side VM/PMP composition over the qualified F6 backend.
  *
  * MachineCsrFile remains inside TinyMemoryBackend as the sole mutable
  * privilege/CSR/PMP/SATP owner. This frontend consumes only read-only context,
  * shares the existing geometry-driven PTW arbiter, and converts fetch faults
  * into predecoded architectural exceptions before ROB allocation.
  *
  * Compressed profiles reuse the shared XLEN-aware parcel controller. Each
  * 16-bit parcel is translated, PMP-checked and fetched independently, so a
  * 32-bit instruction crossing a page/PMP boundary faults precisely at PC+2.
  * Profiles without C retain the frozen one-request 32-bit frontend path.
  *
  * F7 may additionally opt into clean-boundary asynchronous interrupts/WFI.
  * A-extension profiles use the same semantic memory seam and enable Atomic
  * AetherMem transactions; profiles without A retain the frozen fail-closed
  * behavior.
  */
class TinyPagedCore(
    val config: CoreConfig,
    val geometry: PageTableGeometry,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2,
    val enableAsyncInterrupts: Boolean = false,
    val withMachineExternalInterrupt: Boolean = false,
    val withSupervisorExternalInterrupt: Boolean = false
) extends Module {
  private val isa = config.isa
  private val Xlen = isa.xlen
  private val PhysicalBits = config.platform.paddrBits
  private val BusBits = config.platform.busDataBits

  require(geometry.xlen == Xlen)
  require(isa.pageTableGeometries.contains(geometry))
  require(isa.hasPagedVirtualMemory, "TinyPagedCore requires a paged-VM profile")
  require(BusBits == Xlen, "TinyPagedCore current slice retains the F6 busDataBits == XLEN contract")
  require(!withMachineExternalInterrupt || enableAsyncInterrupts)
  require(!withSupervisorExternalInterrupt || enableAsyncInterrupts)

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(PhysicalBits)
    val ptw = new PageTableReadBusIO(PhysicalBits, geometry.pteBits)

    val commit = Output(new CommitTrace(Xlen, PhysicalBits, BusBits))
    val currentPrivilege = Output(UInt(2.W))
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
    val frontendPc = Output(UInt(Xlen.W))
    val frontendPhysicalAddress = Output(UInt(PhysicalBits.W))
    val halted = Output(Bool())
    val interruptHold = Output(Bool())
    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None
    val timerInterrupt = if (enableAsyncInterrupts) Some(Input(Bool())) else None
    val machineExternalInterrupt =
      if (enableAsyncInterrupts && withMachineExternalInterrupt) Some(Input(Bool())) else None
    val supervisorExternalInterrupt =
      if (enableAsyncInterrupts && withSupervisorExternalInterrupt) Some(Input(Bool())) else None

    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PhysicalBits.W))
    val resolvedAttributes = Input(new MemoryAttributes)
    val memoryRequest = Decoupled(new AetherMemRequest(PhysicalBits, Xlen, txnIdBits))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, txnIdBits)))
    val lsuBusy = Output(Bool())
    val translationFence = Output(Bool())
  })

  val backend = Module(new TinyLoadQueueMemoryBackend(
    config,
    geometry,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    enableAsyncInterrupts = enableAsyncInterrupts,
    withMachineExternalInterrupt = withMachineExternalInterrupt,
    withSupervisorExternalInterrupt = withSupervisorExternalInterrupt,
    allowAtomics = isa.hasA
  ))
  val decode = Module(new TinySemanticDecode(isa))
  // The v2 frontend deliberately reuses the shared InstructionFetchAdapter and
  // TranslationUnit. Shared translation-path changes therefore require the same
  // exact-head Linux qualification as direct core/v2 RTL changes.
  val fetch = Module(new InstructionFetchAdapter(geometry, PhysicalBits, tlbEntries))
  val parcel = if (isa.hasC) Some(Module(new RvcParcelController(Xlen))) else None
  val instructionPmp = Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))
  val ptwArbiter = Module(new PtwArbiter(geometry, PhysicalBits))
  val ptwPmp = Module(new PmpChecker(Xlen, PmpConstants.MaxEntries, PhysicalBits))

  private val pc = RegInit(config.platform.resetVector.U(Xlen.W))
  private val serialized = RegInit(false.B)
  private val serializedPc = Reg(UInt(Xlen.W))
  io.frontendPc := pc

  private val asyncInterruptRedirect =
    if (enableAsyncInterrupts) backend.io.async.get.interruptRedirect.valid else false.B
  private val interruptHold =
    if (enableAsyncInterrupts) backend.io.async.get.interruptHold else false.B
  private val wfiWaiting =
    if (enableAsyncInterrupts) backend.io.async.get.wfiWaiting else false.B
  private val branchRedirect = backend.io.branchRedirect.valid
  private val privilegedRedirect = backend.io.privilegedRedirect.valid
  private val redirect = asyncInterruptRedirect || privilegedRedirect || branchRedirect
  private val redirectTarget = Mux(
    asyncInterruptRedirect,
    if (enableAsyncInterrupts) backend.io.async.get.interruptRedirect.bits else 0.U,
    Mux(
      privilegedRedirect,
      backend.io.privilegedRedirect.bits.target,
      backend.io.branchRedirect.bits.target
    )
  )
  private val frontendBlocked = serialized || interruptHold || wfiWaiting
  private val frontendKill = redirect || interruptHold || wfiWaiting
  io.halted := wfiWaiting
  io.interruptHold := interruptHold

  // A serializing architectural operation (CSR/SFENCE/xRET/fence/WFI today,
  // plus aq/rl atomics) closes the speculative fetch window. This avoids
  // carrying stale translated instruction bits across an architectural context
  // boundary without adding a replay mechanism.
  private val serializedRetires = serialized && backend.io.commit.valid &&
    backend.io.commit.pc === serializedPc

  if (isa.hasC) {
    val rvc = parcel.get
    rvc.io.instructionPc := pc
    // A translation fence is a frontend context boundary even though the
    // InstructionFetchAdapter receives it through its dedicated flush input.
    // Never retain the first half of a 32-bit instruction across that boundary.
    rvc.io.kill := frontendKill || backend.io.translationFence
  }

  fetch.io.requestValid := !redirect && !frontendBlocked
  // A newly qualified interrupt can arrive while an instruction translation is
  // in flight. Cancel that speculative fetch and restart from the same PC after
  // trap entry/return rather than carrying old-context instruction bits across
  // the architectural boundary.
  fetch.io.kill := frontendKill
  fetch.io.flush := backend.io.translationFence
  fetch.io.virtualAddress := (if (isa.hasC) parcel.get.io.parcelRequestAddress else pc)
  fetch.io.privilege := backend.io.currentPrivilege
  fetch.io.satpTranslationEnabled := backend.io.frontendSatpTranslationEnabled
  fetch.io.satpRootPpn := backend.io.frontendSatpRootPpn
  fetch.io.mxr := backend.io.frontendSupervisorMxr

  instructionPmp.io.privilege := backend.io.currentPrivilege
  instructionPmp.io.address := fetch.io.physicalAddress
  instructionPmp.io.bytes := (if (isa.hasC) 2.U else 4.U)
  instructionPmp.io.write := false.B
  instructionPmp.io.execute := true.B
  instructionPmp.io.config := backend.io.frontendPmpConfig
  instructionPmp.io.pmpAddress := backend.io.frontendPmpAddress

  private val instructionPmpFault = fetch.io.responseValid &&
    !fetch.io.pageFault && !fetch.io.accessFault && isa.hasPmp.B && !instructionPmp.io.allow

  io.frontendPhysicalAddress := fetch.io.physicalAddress
  io.imem.valid := fetch.io.responseValid &&
    !fetch.io.pageFault && !fetch.io.accessFault && !instructionPmpFault &&
    !redirect && !frontendBlocked
  io.imem.addr := fetch.io.physicalAddress
  io.imem.bytes := (if (isa.hasC) 2.U else 4.U)

  private val instructionBusFault = io.imem.valid && io.imem.fault
  private val parcelAccessFault = fetch.io.accessFault || instructionPmpFault || instructionBusFault

  if (isa.hasC) {
    val rvc = parcel.get
    rvc.io.parcelResponseValid := fetch.io.responseValid
    rvc.io.parcelBits := io.imem.inst(15, 0)
    rvc.io.parcelPageFault := fetch.io.pageFault
    rvc.io.parcelAccessFault := parcelAccessFault
    // For the first half of an ordinary 32-bit instruction this may consume a
    // parcel without allocating a ROB entry. Backpressure is still inherited
    // from the backend so a full ROB cannot open a new second-parcel lifetime.
    rvc.io.advance := backend.io.dispatch.ready
  }

  val fetchException = WireInit(0.U.asTypeOf(new TrapInfo(Xlen)))
  if (isa.hasC) {
    val rvc = parcel.get
    when(rvc.io.pageFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionPageFault.U
      fetchException.value := rvc.io.faultAddress
    }.elsewhen(rvc.io.accessFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionAccessFault.U
      fetchException.value := rvc.io.faultAddress
    }
  } else {
    when(fetch.io.pageFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionPageFault.U
      fetchException.value := pc
    }.elsewhen(fetch.io.accessFault || instructionPmpFault || instructionBusFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionAccessFault.U
      fetchException.value := pc
    }
  }

  decode.io.pc := pc
  decode.io.inst := (if (isa.hasC) parcel.get.io.instruction else io.imem.inst)
  decode.io.rawInst := (if (isa.hasC) parcel.get.io.rawInstruction else io.imem.inst)
  decode.io.instBytes := (if (isa.hasC) parcel.get.io.instructionBytes else 4.U)
  decode.io.fetchException := fetchException

  private val decoded = decode.io.dispatch.decoded
  private val sequentialNextPc = pc + decoded.instBytes
  // Final bounded static-control-flow experiment. Direct JAL/J remains always
  // taken. Conditional branches use the classic BTFNT rule: only a negative
  // (backward) PC-relative offset is predicted taken; forward branches retain
  // the legacy sequential/not-taken path. This adds no predictor table, BTB,
  // BHT, RAS or checkpoint state.
  private val predictableBranch =
    decode.io.dispatch.executionClass === ExecutionClass.Branch && !decoded.exception.valid
  private val predictedTarget = pc + decoded.immediate
  private val predictDirectJumpTaken =
    predictableBranch && decoded.controlFlow.kind === ControlFlowKind.DirectJump

  // BHT64 experiment: retain BTFNT as the cold/alias-reset fallback, then let a
  // tiny direct-mapped 2-bit counter table learn only Conditional direction.
  // With C enabled, bit 0 is always zero, so PC[6:1] gives 64 useful indices.
  // No BTB, RAS, global history, checkpoints, or speculative table updates are
  // introduced.
  private val BhtEntries = 64
  private val BhtIndexBits = log2Ceil(BhtEntries)
  private val bhtValid = RegInit(VecInit(Seq.fill(BhtEntries)(false.B)))
  private val bhtCounter = Reg(Vec(BhtEntries, UInt(2.W)))
  private val bhtIndex = pc(BhtIndexBits, 1)
  private val bhtColdTaken = decoded.immediate(Xlen - 1)
  private val bhtPredictTaken = Mux(
    bhtValid(bhtIndex),
    bhtCounter(bhtIndex)(1),
    bhtColdTaken
  )
  private val predictConditionalTaken =
    predictableBranch && decoded.controlFlow.kind === ControlFlowKind.Conditional &&
      bhtPredictTaken
  private val predictTaken = predictDirectJumpTaken || predictConditionalTaken

  private val train = backend.io.branchResolution
  private val trainConditional =
    train.valid && train.bits.kind === ControlFlowKind.Conditional
  private val trainIndex = train.bits.pc(BhtIndexBits, 1)
  when(trainConditional) {
    when(!bhtValid(trainIndex)) {
      bhtValid(trainIndex) := true.B
      // First real outcome establishes a weak state in its own direction.
      bhtCounter(trainIndex) := Mux(train.bits.taken, 2.U, 1.U)
    }.otherwise {
      when(train.bits.taken) {
        when(bhtCounter(trainIndex) =/= 3.U) {
          bhtCounter(trainIndex) := bhtCounter(trainIndex) + 1.U
        }
      }.otherwise {
        when(bhtCounter(trainIndex) =/= 0.U) {
          bhtCounter(trainIndex) := bhtCounter(trainIndex) - 1.U
        }
      }
    }
  }

  backend.io.dispatch.valid :=
    (if (isa.hasC) parcel.get.io.instructionValid else fetch.io.responseValid) &&
      !redirect && !frontendBlocked
  backend.io.dispatch.bits := decode.io.dispatch
  backend.io.dispatch.bits.predictionValid := predictTaken
  backend.io.dispatch.bits.predictedNextPc := predictedTarget
  fetch.io.responseReady :=
    (if (isa.hasC) parcel.get.io.parcelResponseReady else backend.io.dispatch.fire)

  if (enableAsyncInterrupts) {
    backend.io.async.get.boundaryPc := pc
    backend.io.async.get.timerPending := io.timerInterrupt.get
    if (withMachineExternalInterrupt) {
      backend.io.async.get.machineExternalPending.get := io.machineExternalInterrupt.get
    }
    if (withSupervisorExternalInterrupt) {
      backend.io.async.get.supervisorExternalPending.get := io.supervisorExternalInterrupt.get
    }
  }

  when(redirect) {
    pc := redirectTarget
    serialized := false.B
  }.otherwise {
    when(serializedRetires) {
      serialized := false.B
    }
    when(backend.io.dispatch.fire) {
      pc := Mux(predictTaken, predictedTarget, sequentialNextPc)
      when(decoded.ordering =/= OrderingClass.Normal) {
        serialized := true.B
        serializedPc := pc
      }
    }
  }

  // Data translation owns PTW PMP before exporting a PTE request from the
  // backend. Fetch translation has no mutable PMP owner of its own and joins
  // the shared PTW here. Data keeps deterministic priority in the arbiter.
  ptwArbiter.io.dataValid := backend.io.pteValid
  ptwArbiter.io.dataAddress := backend.io.pteAddress
  backend.io.pteReady := ptwArbiter.io.dataReady
  backend.io.pteData := ptwArbiter.io.dataRdata
  backend.io.pteFault := ptwArbiter.io.dataFault

  ptwArbiter.io.fetchValid := fetch.io.pteValid
  ptwArbiter.io.fetchAddress := fetch.io.pteAddress
  fetch.io.pteReady := ptwArbiter.io.fetchReady
  fetch.io.pteData := ptwArbiter.io.fetchRdata
  fetch.io.pteFault := ptwArbiter.io.fetchFault

  // Every implicit PTE read has exactly one PMP owner. Data requests reaching
  // this arbiter have already passed TinyMemoryBackend's local Supervisor-mode
  // PTW PMP guard. Only a selected fetch request is checked here. The arbiter
  // owns source selection and exports memoryIsFetch as routing metadata so this
  // parent does not duplicate the data-priority selection policy. A denied fetch
  // walk is consumed locally in the same cycle and never reaches external PTW.
  ptwPmp.io.privilege := PrivilegeMode.Supervisor.U
  ptwPmp.io.address := ptwArbiter.io.memoryAddress
  ptwPmp.io.bytes := geometry.pteBytes.U
  ptwPmp.io.write := false.B
  ptwPmp.io.execute := false.B
  ptwPmp.io.config := backend.io.frontendPmpConfig
  ptwPmp.io.pmpAddress := backend.io.frontendPmpAddress
  private val fetchPtwPmpFault =
    ptwArbiter.io.memoryIsFetch && isa.hasPmp.B && !ptwPmp.io.allow

  io.ptw.valid := ptwArbiter.io.memoryValid && !fetchPtwPmpFault
  io.ptw.addr := ptwArbiter.io.memoryAddress
  ptwArbiter.io.memoryReady := Mux(fetchPtwPmpFault, true.B, io.ptw.ready)
  ptwArbiter.io.memoryRdata := io.ptw.rdata
  ptwArbiter.io.memoryFault := fetchPtwPmpFault || (io.ptw.valid && io.ptw.fault)

  io.commit := backend.io.commit
  io.currentPrivilege := backend.io.currentPrivilege
  io.occupancy := backend.io.occupancy
  if (isa.hasTimeCounter) {
    backend.io.time.get := io.time.get
  }

  io.resolvedPhysicalValid := backend.io.resolvedPhysicalValid
  io.resolvedPhysicalAddress := backend.io.resolvedPhysicalAddress
  backend.io.resolvedAttributes := io.resolvedAttributes
  io.memoryRequest.valid := backend.io.memoryRequest.valid
  io.memoryRequest.bits := backend.io.memoryRequest.bits
  backend.io.memoryRequest.ready := io.memoryRequest.ready
  backend.io.memoryResponse.valid := io.memoryResponse.valid
  backend.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := backend.io.memoryResponse.ready
  io.lsuBusy := backend.io.lsuBusy
  io.translationFence := backend.io.translationFence

  assert(PopCount(Cat(asyncInterruptRedirect, privilegedRedirect, branchRedirect)) <= 1.U,
    "oldest-only F7 frontend received more than one architectural redirect in one cycle")
}
