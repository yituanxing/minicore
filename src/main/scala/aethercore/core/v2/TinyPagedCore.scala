package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{CommitTrace, InstructionBusIO, MachineExceptionCode, PageTableReadBusIO, PrivilegeMode, TrapInfo}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.core.{InstructionFetchAdapter, PmpChecker, PmpConstants, PtwArbiter}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * F7 instruction-side VM/PMP composition over the qualified F6 backend.
  *
  * MachineCsrFile remains inside TinyMemoryBackend as the sole mutable
  * privilege/CSR/PMP/SATP owner. This frontend consumes only read-only context,
  * shares the existing geometry-driven PTW arbiter, and converts fetch faults
  * into predecoded architectural exceptions before ROB allocation.
  *
  * F7 may additionally opt into clean-boundary asynchronous interrupts/WFI.
  * Interrupt qualification immediately closes dispatch; architectural trap
  * entry happens only after the ROB drains, using this frontend's next PC as
  * mepc/sepc. The default remains disabled so the already-qualified paged slice
  * and frozen F6 behavior are unchanged.
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
  require(!isa.hasC, "TinyPagedCore current F7 slice accepts only canonical 32-bit instructions")
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

  val backend = Module(new TinyMemoryBackend(
    config,
    geometry,
    tlbEntries = tlbEntries,
    txnIdBits = txnIdBits,
    enableAsyncInterrupts = enableAsyncInterrupts,
    withMachineExternalInterrupt = withMachineExternalInterrupt,
    withSupervisorExternalInterrupt = withSupervisorExternalInterrupt
  ))
  val decode = Module(new TinySemanticDecode(isa))
  val fetch = Module(new InstructionFetchAdapter(geometry, PhysicalBits, tlbEntries))
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
  io.halted := wfiWaiting
  io.interruptHold := interruptHold

  // A serializing architectural operation (CSR/SFENCE/xRET/fence/WFI today, and
  // aq/rl atomics once F7 implements A) closes the speculative fetch window.
  // This avoids carrying stale translated instruction bits across a retirement
  // that changes SATP/PMP/privilege state, without adding a replay mechanism.
  private val serializedRetires = serialized && backend.io.commit.valid &&
    backend.io.commit.pc === serializedPc

  fetch.io.requestValid := !redirect && !frontendBlocked
  // A newly qualified interrupt can arrive while an instruction translation is
  // in flight. Cancel that speculative fetch and restart from the same PC after
  // trap entry/return rather than carrying old-context instruction bits across
  // the architectural boundary.
  fetch.io.kill := redirect || interruptHold || wfiWaiting
  fetch.io.flush := backend.io.translationFence
  fetch.io.virtualAddress := pc
  fetch.io.privilege := backend.io.currentPrivilege
  fetch.io.satpTranslationEnabled := backend.io.frontendSatpTranslationEnabled
  fetch.io.satpRootPpn := backend.io.frontendSatpRootPpn
  fetch.io.mxr := backend.io.frontendSupervisorMxr

  instructionPmp.io.privilege := backend.io.currentPrivilege
  instructionPmp.io.address := fetch.io.physicalAddress
  instructionPmp.io.bytes := 4.U
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
  io.imem.bytes := 4.U

  val fetchException = WireInit(0.U.asTypeOf(new TrapInfo(Xlen)))
  when(fetch.io.pageFault) {
    fetchException.valid := true.B
    fetchException.cause := MachineExceptionCode.InstructionPageFault.U
    fetchException.value := pc
  }.elsewhen(fetch.io.accessFault || instructionPmpFault || (io.imem.valid && io.imem.fault)) {
    fetchException.valid := true.B
    fetchException.cause := MachineExceptionCode.InstructionAccessFault.U
    fetchException.value := pc
  }

  decode.io.pc := pc
  decode.io.inst := io.imem.inst
  decode.io.rawInst := io.imem.inst
  decode.io.instBytes := 4.U
  decode.io.fetchException := fetchException

  backend.io.dispatch.valid := fetch.io.responseValid && !redirect && !frontendBlocked
  backend.io.dispatch.bits := decode.io.dispatch
  fetch.io.responseReady := backend.io.dispatch.fire

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
      pc := pc + 4.U
      when(decode.io.dispatch.decoded.ordering =/= OrderingClass.Normal) {
        serialized := true.B
        serializedPc := pc
      }
    }
  }

  // Data PTW requests already passed the F6 backend's local PTW-PMP check.
  // Fetch PTW requests join them here; older data translation keeps priority.
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

  // Implicit page-table accesses execute with Supervisor PMP permissions, as in
  // the qualified v1 composition. A denied walk is consumed locally and never
  // leaks an external PTW transaction.
  ptwPmp.io.privilege := PrivilegeMode.Supervisor.U
  ptwPmp.io.address := ptwArbiter.io.memoryAddress
  ptwPmp.io.bytes := geometry.pteBytes.U
  ptwPmp.io.write := false.B
  ptwPmp.io.execute := false.B
  ptwPmp.io.config := backend.io.frontendPmpConfig
  ptwPmp.io.pmpAddress := backend.io.frontendPmpAddress
  private val ptwPmpFault = ptwArbiter.io.memoryValid && isa.hasPmp.B && !ptwPmp.io.allow

  io.ptw.valid := ptwArbiter.io.memoryValid && !ptwPmpFault
  io.ptw.addr := ptwArbiter.io.memoryAddress
  ptwArbiter.io.memoryReady := Mux(ptwPmpFault, true.B, io.ptw.ready)
  ptwArbiter.io.memoryRdata := io.ptw.rdata
  ptwArbiter.io.memoryFault := ptwPmpFault || (io.ptw.valid && io.ptw.fault)

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
