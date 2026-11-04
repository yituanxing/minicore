package aethercore.core.product

import aethercore.common.{CommitTrace, InstructionBusIO, MachineExceptionCode, PageTableReadBusIO, PrivilegeMode, TrapInfo}
import aethercore.config.PageTableGeometry
import aethercore.core.{FloatingCsrAddress, InstructionFetchAdapter, MachineCsrBit, MachineCsrFile,
  PmpAccessChecker, PmpConstants, PmpRangeDecoder, PtwArbiter, RvcParcelController}
import aethercore.core.semantic._
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}
import chisel3._
import chisel3.util._

/** A real fetch/privilege closure around the Small Product OoO backend.
  *
  * The architectural CSR, SATP, privilege and PMP state has exactly one owner:
  * MachineCsrFile. Shared instruction translation, RVC parcel assembly and
  * data-priority PTW arbitration are reused directly from the qualified Tiny
  * core. Product's own ROB is responsible for precisely retired System effects
  * and for branch/exception recovery; no Tiny ROB/LSU is instantiated here.
  *
  * This is a CPU integration boundary, not a Linux boot claim: it still needs
  * platform memory, a hardware elaboration/runtime gate, externally qualified
  * retirement tracing, F/D numerical differentials and FPGA/LP64D software
  * qualification before any Debian-on-board claim.
  */
class ProductSmallPagedCore(
    val config: ProductCoreConfig,
    val tlbEntries: Int = 8,
    val enableInstructionBackpressure: Boolean = false,
    val withMachineExternalInterrupt: Boolean = false,
    val withSupervisorExternalInterrupt: Boolean = true
) extends Module {
  private val isa = config.software.isa
  private val g = config.geometry
  private val geometry = PageTableGeometry.Sv39
  private val Xlen = 64
  private val PA = config.platform.paddrBits
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16
  private val T = g.loadQueueEntries + g.storeQueueEntries
  private val Tx = log2Ceil(T)

  require(isa.xlen == Xlen && isa.hasSv39 && isa.hasPagedVirtualMemory,
    "ProductSmallPagedCore requires the real RV64 Sv39 hardware profile")
  require(isa.hasS && isa.hasU,
    "ProductSmallPagedCore requires machine, supervisor and user privilege")
  // Both LP64 engineering and RV64GC/LP64D targets use the SAME privileged
  // frontend and ROB. Requested F/D enables the existing single-owner FCSR,
  // FS legality, precise floating retirement and FP-aware RVC decode; it
  // does not imply that FPGA timing or Debian execution is qualified.
  require(!withSupervisorExternalInterrupt || isa.hasS,
    "supervisor external interrupt requires implemented S-mode")

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(PA)
    val imemReady = if (enableInstructionBackpressure) Some(Input(Bool())) else None
    val ptw = new PageTableReadBusIO(PA, geometry.pteBits)

    val resolvedPhysicalValid = Output(Bool())
    val resolvedPhysicalAddress = Output(UInt(PA.W))
    val resolvedAttributes = Input(new MemoryAttributes)
    val memoryRequest = Decoupled(new AetherMemRequest(PA, Xlen, Tx))
    val memoryResponse = Flipped(Decoupled(new AetherMemResponse(Xlen, Tx)))

    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None
    val timerInterrupt = Input(Bool())
    val machineExternalInterrupt =
      if (withMachineExternalInterrupt) Some(Input(Bool())) else None
    val supervisorExternalInterrupt =
      if (withSupervisorExternalInterrupt) Some(Input(Bool())) else None

    val retire = Valid(new ProductRetirement(Xlen, I, P, G, PA))
    // CommitTrace.valid means one instruction retired. An asynchronous
    // interrupt is a separate clean-boundary event and may have valid=false.
    val commit = Output(new CommitTrace(Xlen, PA, Xlen,
      withFloating = isa.extensions.contains('F')))
    val currentPrivilege = Output(UInt(2.W))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val recovering = Output(Bool())
    val frontendPc = Output(UInt(Xlen.W))
    val frontendPhysicalAddress = Output(UInt(PA.W))
    val resolverPending = Output(UInt(log2Ceil(T + 1).W))
    val outstandingTransactions = Output(UInt(log2Ceil(T + 1).W))
    val halted = Output(Bool())
    val interruptHold = Output(Bool())
    val interruptTaken = Output(Bool())
    val translationFence = Output(Bool())
    val instructionFence = Output(Bool())
  })

  private val core = Module(new ProductSmallTranslatedCore(config, tlbEntries))
  private val csrFile = Module(new MachineCsrFile(
    isa, PA, withMachineExternalInterrupt, withSupervisorExternalInterrupt))
  private val decode = Module(new SemanticDecode(isa))
  private val fetch = Module(new InstructionFetchAdapter(geometry, PA, tlbEntries))
  private val parcel = if (isa.hasC) Some(Module(new RvcParcelController(
    Xlen, withDouble = isa.extensions.contains('D')))) else None
  private val frontendPmp = Module(new PmpRangeDecoder(Xlen, PmpConstants.MaxEntries, PA))
  private val instructionPmp = Module(new PmpAccessChecker(PmpConstants.MaxEntries, PA))
  private val ptePmp = Module(new PmpAccessChecker(PmpConstants.MaxEntries, PA))
  private val ptwArbiter = Module(new PtwArbiter(geometry, PA))

  // System completion computes but never writes an architectural CSR. Only
  // retirement applies the pending effect, including trap enter and xRET.
  csrFile.io.readAddr := core.io.systemCsrReadAddr
  core.io.systemCsrReadData := csrFile.io.readData
  core.io.systemCsrReadImplemented := csrFile.io.readImplemented
  core.io.systemCsrReadWritable := csrFile.io.readWritable
  core.io.systemCurrentPrivilege := csrFile.io.currentPrivilege
  if (isa.extensions.contains('F')) {
    core.io.fpDynamicRoundingMode.get := csrFile.io.fpRoundingMode
  }

  core.io.context.effectivePrivilege := csrFile.io.effectiveDataPrivilege
  core.io.context.satpEnabled := csrFile.io.satpTranslationEnabled
  core.io.context.rootPpn := csrFile.io.satpRootPpn
  core.io.context.sum := csrFile.io.supervisorSum
  core.io.context.mxr := csrFile.io.supervisorMxr
  core.io.pmpConfig := csrFile.io.pmpConfig
  core.io.pmpAddress := csrFile.io.pmpAddress

  core.io.retire.ready := true.B
  io.retire.valid := core.io.retire.valid
  io.retire.bits := core.io.retire.bits
  private val retiring = core.io.retire.valid
  private val retireFault = retiring && core.io.retire.bits.exception.valid
  private val pendingEffect = core.io.retire.bits.privileged
  private val returnAtRetire = retiring && !retireFault && pendingEffect.trapReturn
  private val sfenceAtRetire = retiring && !retireFault && pendingEffect.sfenceVma
  private val fenceIAtRetire = retiring && !retireFault && pendingEffect.fenceI
  private val wfiAtRetire = retiring && !retireFault && pendingEffect.wfi

  csrFile.io.writeEnable := retiring && !retireFault && pendingEffect.csrWriteValid
  csrFile.io.writeAddr := pendingEffect.csrAddress
  csrFile.io.writeData := pendingEffect.csrWriteData
  // FP architectural state belongs to the CSR owner and changes only at
  // precise ROB retirement. A younger FU completion can never publish
  // fflags/FS Dirty ahead of an older fault or branch recovery.
  if (isa.extensions.contains('F')) {
    csrFile.io.fpCommit.get.valid := retiring && !retireFault &&
      (core.io.retire.bits.fpDirty || core.io.retire.bits.fflags.orR)
    csrFile.io.fpCommit.get.bits.fflags := core.io.retire.bits.fflags
    csrFile.io.fpCommit.get.bits.dirty := core.io.retire.bits.fpDirty
  }
  core.io.translationFence := sfenceAtRetire

  csrFile.io.timerInterrupt := io.timerInterrupt
  if (isa.hasTimeCounter) csrFile.io.time.get := io.time.get
  if (withMachineExternalInterrupt)
    csrFile.io.externalInterrupt.get := io.machineExternalInterrupt.get
  if (withSupervisorExternalInterrupt)
    csrFile.io.supervisorExternalInterruptPending.get := io.supervisorExternalInterrupt.get

  private val rawMachineExternal =
    if (withMachineExternalInterrupt) io.machineExternalInterrupt.get else false.B
  private val rawSupervisorExternal =
    if (withSupervisorExternalInterrupt) io.supervisorExternalInterrupt.get else false.B
  private val rawSupervisorTimer =
    if (isa.hasSupervisorTimerInterrupt) csrFile.io.supervisorTimerPending.get else false.B
  private val rawWake = io.timerInterrupt || rawMachineExternal ||
    rawSupervisorExternal || rawSupervisorTimer
  private val machineExternalQualified =
    if (withMachineExternalInterrupt) csrFile.io.machineExternalInterrupt.get else false.B
  private val machineTimerQualified = csrFile.io.machineTimerInterrupt
  private val supervisorExternalQualified =
    if (withSupervisorExternalInterrupt) csrFile.io.supervisorExternalInterrupt.get else false.B
  private val supervisorTimerQualified =
    if (isa.hasSupervisorTimerInterrupt) csrFile.io.supervisorTimerInterrupt.get else false.B
  private val qualifiedInterrupt = machineExternalQualified || machineTimerQualified ||
    supervisorExternalQualified || supervisorTimerQualified
  private val interruptFlag = BigInt(1) << (Xlen - 1)
  private val interruptCause = Mux(machineExternalQualified,
    (interruptFlag | BigInt(MachineCsrBit.MachineExternalInterrupt)).U(Xlen.W),
    Mux(machineTimerQualified,
      (interruptFlag | BigInt(MachineCsrBit.MachineTimerInterrupt)).U(Xlen.W),
      Mux(supervisorExternalQualified,
        (interruptFlag | BigInt(MachineCsrBit.SupervisorExternalInterrupt)).U(Xlen.W),
        (interruptFlag | BigInt(MachineCsrBit.SupervisorTimerInterrupt)).U(Xlen.W))))

  private val pc = RegInit(config.platform.resetVector.U(Xlen.W))
  private val serialized = RegInit(false.B)
  private val serializedPc = Reg(UInt(Xlen.W))
  private val wfiWaiting = RegInit(false.B)
  io.frontendPc := pc
  io.currentPrivilege := csrFile.io.currentPrivilege
  io.occupancy := core.io.occupancy
  io.recovering := core.io.recovering
  io.resolverPending := core.io.resolverPending
  io.outstandingTransactions := core.io.outstandingTransactions

  // No asynchronous trap may interrupt an already accepted instruction or a
  // killed external transaction. The frontend PC is the precise next PC only
  // after the ROB/recovery/LSQ/resolver have all reached a clean boundary.
  private val branchRedirect = core.io.redirect.valid
  private val privilegedRedirect = retireFault || returnAtRetire
  private val cleanBoundary = core.io.occupancy === 0.U && !core.io.recovering &&
    core.io.loadCount === 0.U && core.io.storeCount === 0.U &&
    core.io.outstandingTransactions === 0.U && core.io.resolverPending === 0.U
  private val interruptTake = qualifiedInterrupt && cleanBoundary &&
    !privilegedRedirect && !branchRedirect

  // Memory evidence travels with the completing ROB lifetime (not a bus
  // response pulse). Only exact-head, nonfaulting retirement exposes it.
  io.commit := 0.U.asTypeOf(io.commit)
  io.commit.valid := retiring
  io.commit.pc := core.io.retire.bits.pc
  io.commit.inst := core.io.retire.bits.inst
  io.commit.rawInst := core.io.retire.bits.rawInst
  io.commit.instBytes := core.io.retire.bits.instBytes
  io.commit.rd := core.io.retire.bits.rd
  io.commit.rdWrite := retiring && core.io.retire.bits.wroteRegister
  io.commit.rdData := core.io.retire.bits.value
  if (isa.extensions.contains('F')) {
    val fpCsrWrite = pendingEffect.csrWriteValid &&
      (pendingEffect.csrAddress === FloatingCsrAddress.Fflags.U ||
        pendingEffect.csrAddress === FloatingCsrAddress.Frm.U ||
        pendingEffect.csrAddress === FloatingCsrAddress.Fcsr.U)
    ProductFloatingRetirementTrace.connect(io.commit.floating.get,
      core.io.retire.bits, retiring, fpCsrWrite)
  }
  io.commit.memValid := retiring && core.io.retire.bits.memoryTrace.valid && !retireFault
  io.commit.memWrite := io.commit.memValid && core.io.retire.bits.memoryTrace.write
  io.commit.memAddr := core.io.retire.bits.memoryTrace.paddr
  io.commit.memWdata := core.io.retire.bits.memoryTrace.wdata
  io.commit.memWmask := core.io.retire.bits.memoryTrace.wmask
  io.commit.exception := retireFault
  io.commit.exceptionCause := core.io.retire.bits.exception.cause
  io.commit.exceptionValue := core.io.retire.bits.exception.value
  io.commit.interrupt := interruptTake
  io.commit.interruptCause := Mux(interruptTake, interruptCause, 0.U)
  io.commit.interruptPc := Mux(interruptTake, pc, 0.U)
  private val redirect = interruptTake || privilegedRedirect || branchRedirect
  private val redirectTarget = Mux(interruptTake, csrFile.io.trapVector,
    Mux(privilegedRedirect,
      Mux(retireFault, csrFile.io.trapVector, csrFile.io.returnPc),
      core.io.redirect.bits.target))

  csrFile.io.trapEnter := retireFault || interruptTake
  csrFile.io.trapPc := Mux(interruptTake, pc, core.io.retire.bits.pc)
  csrFile.io.trapCause := Mux(interruptTake, interruptCause,
    core.io.retire.bits.exception.cause)
  csrFile.io.trapValue := Mux(interruptTake, 0.U, core.io.retire.bits.exception.value)
  csrFile.io.trapReturn := returnAtRetire
  csrFile.io.trapReturnSupervisor := returnAtRetire && pendingEffect.trapReturnSupervisor

  when(wfiAtRetire && !rawWake) { wfiWaiting := true.B }
  when(wfiWaiting && rawWake) { wfiWaiting := false.B }
  when(interruptTake) { wfiWaiting := false.B }

  private val frontendBlocked = serialized || qualifiedInterrupt || wfiWaiting
  private val frontendKill = redirect || qualifiedInterrupt || wfiWaiting
  io.halted := wfiWaiting
  io.interruptHold := qualifiedInterrupt
  io.interruptTaken := interruptTake
  io.translationFence := sfenceAtRetire
  io.instructionFence := fenceIAtRetire

  fetch.io.requestValid := !frontendBlocked && !redirect
  fetch.io.kill := frontendKill || fenceIAtRetire
  fetch.io.flush := sfenceAtRetire
  fetch.io.virtualAddress := (if (isa.hasC) parcel.get.io.parcelRequestAddress else pc)
  fetch.io.privilege := csrFile.io.currentPrivilege
  fetch.io.satpTranslationEnabled := csrFile.io.satpTranslationEnabled
  fetch.io.satpRootPpn := csrFile.io.satpRootPpn
  fetch.io.mxr := csrFile.io.supervisorMxr

  frontendPmp.io.config := csrFile.io.pmpConfig
  frontendPmp.io.pmpAddress := csrFile.io.pmpAddress
  instructionPmp.io.ranges := frontendPmp.io.ranges
  instructionPmp.io.privilege := csrFile.io.currentPrivilege
  instructionPmp.io.address := fetch.io.physicalAddress
  instructionPmp.io.bytes := (if (isa.hasC) 2.U else 4.U)
  instructionPmp.io.write := false.B
  instructionPmp.io.execute := true.B
  private val instructionPmpFault = fetch.io.responseValid &&
    !fetch.io.pageFault && !fetch.io.accessFault &&
    isa.hasPmp.B && !instructionPmp.io.allow

  io.frontendPhysicalAddress := fetch.io.physicalAddress
  io.imem.valid := fetch.io.responseValid && !fetch.io.pageFault &&
    !fetch.io.accessFault && !instructionPmpFault && !frontendBlocked && !redirect
  io.imem.addr := fetch.io.physicalAddress
  io.imem.bytes := (if (isa.hasC) 2.U else 4.U)
  private val instructionReady =
    if (enableInstructionBackpressure) io.imemReady.get else true.B
  private val instructionCanAdvance = !io.imem.valid || instructionReady
  private val instructionBusFault = io.imem.valid && instructionReady && io.imem.fault
  private val parcelAccessFault = fetch.io.accessFault || instructionPmpFault || instructionBusFault

  if (isa.hasC) {
    val rvc = parcel.get
    rvc.io.instructionPc := pc
    rvc.io.kill := frontendKill || sfenceAtRetire || fenceIAtRetire
    rvc.io.parcelResponseValid := fetch.io.responseValid && instructionCanAdvance
    rvc.io.parcelBits := io.imem.inst(15, 0)
    rvc.io.parcelPageFault := fetch.io.pageFault
    rvc.io.parcelAccessFault := parcelAccessFault
    rvc.io.advance := core.io.dispatch.ready
  }

  private val fetchException = WireInit(0.U.asTypeOf(new TrapInfo(Xlen)))
  if (isa.hasC) {
    when(parcel.get.io.pageFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionPageFault.U
      fetchException.value := parcel.get.io.faultAddress
    }.elsewhen(parcel.get.io.accessFault) {
      fetchException.valid := true.B
      fetchException.cause := MachineExceptionCode.InstructionAccessFault.U
      fetchException.value := parcel.get.io.faultAddress
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

  // Static direct-jump/BTFNT prediction keeps a useful fetch window without
  // introducing a second speculative checkpoint owner. JALR is conservatively
  // predicted sequentially and repaired by the Product ROB branch completion.
  // The CSR file, not the FPU or speculative RAT, owns the current FS state.
  // SYSTEM instructions serialize dispatch, so this precise legality check
  // covers FP arithmetic *and* FLW/FLD/FSW/FSD before entering the ROB.
  private val decoded = WireInit(decode.io.decoded)
  if (isa.extensions.contains('F')) {
    when(decode.io.decoded.floating.kind =/= FloatingOperationKind.None &&
      !csrFile.io.fpStateEnabled && !decode.io.decoded.exception.valid) {
      decoded.exception.valid := true.B
      decoded.exception.cause := MachineExceptionCode.IllegalInstruction.U(Xlen.W)
      decoded.exception.value := Cat(0.U(32.W), decode.io.decoded.rawInst)
      // An exception-only ROB entry must not reserve an FPR/XPR physical
      // destination or LSQ ownership.  Otherwise a full free list could
      // prevent the instruction from reaching its *precise* illegal trap.
      decoded.floating := 0.U.asTypeOf(new DecodedFloatingOperation)
      decoded.memory := 0.U.asTypeOf(new DecodedMemoryOperation)
      decoded.usesRs1 := false.B
      decoded.usesRs2 := false.B
      decoded.writesRd := false.B
      decoded.ordering := OrderingClass.Normal
    }
  }
  private val sequentialNextPc = pc + decoded.instBytes
  private val predictableBranch = !decoded.exception.valid
  private val predictDirectJump = predictableBranch &&
    decoded.controlFlow.kind === ControlFlowKind.DirectJump
  private val predictBackwardConditional = predictableBranch &&
    decoded.controlFlow.kind === ControlFlowKind.Conditional &&
    decoded.immediate(Xlen - 1)
  private val predictedNextPc = Mux(predictDirectJump || predictBackwardConditional,
    pc + decoded.immediate, sequentialNextPc)

  core.io.dispatch.valid :=
    (if (isa.hasC) parcel.get.io.instructionValid
     else fetch.io.responseValid && instructionCanAdvance) &&
      !redirect && !frontendBlocked
  core.io.dispatch.bits.decoded := decoded
  core.io.dispatch.bits.predictedNextPc := predictedNextPc
  fetch.io.responseReady :=
    (if (isa.hasC) parcel.get.io.parcelResponseReady else core.io.dispatch.fire) &&
      instructionCanAdvance

  private val serializedRetires = serialized && retiring &&
    core.io.retire.bits.pc === serializedPc
  when(redirect) {
    pc := redirectTarget
    serialized := false.B
  }.otherwise {
    when(serializedRetires) { serialized := false.B }
    when(core.io.dispatch.fire) {
      pc := predictedNextPc
      when(decoded.system.kind =/= SystemOperationKind.None ||
           decoded.ordering =/= OrderingClass.Normal) {
        serialized := true.B
        serializedPc := pc
      }
    }
  }

  // The data resolver guards its own PTE memory access with Supervisor PMP.
  // This arbiter reuses the same deterministic data-priority policy as Tiny;
  // only fetched PTEs still require a local Supervisor PMP check here.
  ptwArbiter.io.dataValid := core.io.pte.valid
  ptwArbiter.io.dataAddress := core.io.pte.addr
  core.io.pte.ready := ptwArbiter.io.dataReady
  core.io.pte.rdata := ptwArbiter.io.dataRdata
  core.io.pte.fault := ptwArbiter.io.dataFault

  ptwArbiter.io.fetchValid := fetch.io.pteValid
  ptwArbiter.io.fetchAddress := fetch.io.pteAddress
  fetch.io.pteReady := ptwArbiter.io.fetchReady
  fetch.io.pteData := ptwArbiter.io.fetchRdata
  fetch.io.pteFault := ptwArbiter.io.fetchFault

  ptePmp.io.privilege := PrivilegeMode.Supervisor.U
  ptePmp.io.address := ptwArbiter.io.memoryAddress
  ptePmp.io.bytes := geometry.pteBytes.U
  ptePmp.io.write := false.B
  ptePmp.io.execute := false.B
  ptePmp.io.ranges := frontendPmp.io.ranges
  private val fetchPteDenied = ptwArbiter.io.memoryIsFetch &&
    isa.hasPmp.B && !ptePmp.io.allow

  io.ptw.valid := ptwArbiter.io.memoryValid && !fetchPteDenied
  io.ptw.addr := ptwArbiter.io.memoryAddress
  ptwArbiter.io.memoryReady := Mux(fetchPteDenied, true.B, io.ptw.ready)
  ptwArbiter.io.memoryRdata := io.ptw.rdata
  ptwArbiter.io.memoryFault := fetchPteDenied || (io.ptw.valid && io.ptw.fault)

  io.resolvedPhysicalValid := core.io.pmaValid
  io.resolvedPhysicalAddress := core.io.pmaAddress
  core.io.pmaAttributes := io.resolvedAttributes
  io.memoryRequest.valid := core.io.memoryRequest.valid
  io.memoryRequest.bits := core.io.memoryRequest.bits
  core.io.memoryRequest.ready := io.memoryRequest.ready
  core.io.memoryResponse.valid := io.memoryResponse.valid
  core.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := core.io.memoryResponse.ready

  assert(PopCount(Cat(interruptTake, privilegedRedirect, branchRedirect)) <= 1.U,
    "Product frontend must not receive multiple architectural redirects")
  when(interruptTake) {
    assert(cleanBoundary,
      "asynchronous trap requires a fully drained ROB/LSQ/translator")
  }
}
