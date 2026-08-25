package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.{CommitTrace, InstructionBusIO, MachineExceptionCode, PageTableReadBusIO, TrapInfo}
import aethercore.config.{CoreConfig, PageTableGeometry}
import aethercore.memory.{AetherMemRequest, AetherMemResponse, MemoryAttributes}

/**
  * F7 first real instruction-flow composition.
  *
  * This shell deliberately owns only the bare physical PC/fetch sequence. The
  * architectural Decoder is translated once by TinySemanticDecode into the
  * stable DecodedInstruction boundary; TinyDispatchClassify then adds the
  * backend-only execution/value classification before ROB dispatch. Branch
  * recovery and precise trap/xRET retirement redirect the same PC owner.
  *
  * Instruction translation/PMP, compressed parcel assembly and asynchronous
  * interrupt/WFI ownership are later F7 slices. Keeping them out of this first
  * shell makes the initial milestone prove one thing: real machine-code fetch
  * now drives the v2 ROB/dependency/execute/LSU/Commit path end to end.
  */
class TinyBareCore(
    val config: CoreConfig,
    val geometry: PageTableGeometry,
    val tlbEntries: Int = 8,
    val txnIdBits: Int = 2
) extends Module {
  private val isa = config.isa
  private val Xlen = isa.xlen
  private val PhysicalBits = config.platform.paddrBits
  private val BusBits = config.platform.busDataBits

  require(geometry.xlen == Xlen)
  require(isa.pageTableGeometries.contains(geometry))
  require(!isa.hasC, "TinyBareCore first slice accepts only canonical 32-bit instructions")
  require(BusBits == Xlen, "TinyBareCore first slice retains the F6 busDataBits == XLEN contract")

  val io = IO(new Bundle {
    val imem = new InstructionBusIO(PhysicalBits)

    val commit = Output(new CommitTrace(Xlen, PhysicalBits, BusBits))
    val currentPrivilege = Output(UInt(2.W))
    val occupancy = Output(UInt(log2Ceil(TinyRobGeometry.Entries + 1).W))
    val frontendPc = Output(UInt(Xlen.W))
    val time = if (isa.hasTimeCounter) Some(Input(UInt(64.W))) else None

    // F6 data-side translation and physical-memory seams remain unchanged.
    val ptw = new PageTableReadBusIO(PhysicalBits, geometry.pteBits)
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
    txnIdBits = txnIdBits
  ))
  val decode = Module(new TinySemanticDecode(isa))
  val classify = Module(new TinyDispatchClassify(Xlen))
  classify.io.decoded := decode.io.decoded

  private val pc = RegInit(config.platform.resetVector.U(Xlen.W))
  io.frontendPc := pc

  private val branchRedirect = backend.io.branchRedirect.valid
  private val privilegedRedirect = backend.io.privilegedRedirect.valid
  private val redirect = privilegedRedirect || branchRedirect
  private val redirectTarget = Mux(
    privilegedRedirect,
    backend.io.privilegedRedirect.bits.target,
    backend.io.branchRedirect.bits.target
  )

  // This first slice is explicitly a *physical* fetch path. Do not silently
  // truncate an architectural PC that lies outside the platform PA domain.
  private val pcFitsPhysical = if (Xlen > PhysicalBits) {
    !pc(Xlen - 1, PhysicalBits).orR
  } else {
    true.B
  }
  private val physicalPc = if (PhysicalBits >= Xlen) pc.pad(PhysicalBits) else pc(PhysicalBits - 1, 0)

  io.imem.valid := !redirect && pcFitsPhysical
  io.imem.addr := physicalPc
  io.imem.bytes := 4.U

  val fetchException = WireInit(0.U.asTypeOf(new TrapInfo(Xlen)))
  when(!pcFitsPhysical || (io.imem.valid && io.imem.fault)) {
    fetchException.valid := true.B
    fetchException.cause := MachineExceptionCode.InstructionAccessFault.U
    fetchException.value := pc
  }

  decode.io.pc := pc
  decode.io.inst := io.imem.inst
  decode.io.rawInst := io.imem.inst
  decode.io.instBytes := 4.U
  decode.io.fetchException := fetchException

  // Redirect wins over dispatch so a stale fall-through instruction cannot be
  // allocated in the same cycle that the backend invalidates younger work.
  backend.io.dispatch.valid := !redirect
  backend.io.dispatch.bits := classify.io.dispatch

  when(redirect) {
    pc := redirectTarget
  }.elsewhen(backend.io.dispatch.fire) {
    pc := pc + 4.U
  }

  io.commit := backend.io.commit
  io.currentPrivilege := backend.io.currentPrivilege
  io.occupancy := backend.io.occupancy
  if (isa.hasTimeCounter) {
    backend.io.time.get := io.time.get
  }

  io.ptw.valid := backend.io.pteValid
  io.ptw.addr := backend.io.pteAddress
  backend.io.pteReady := io.ptw.ready
  backend.io.pteData := io.ptw.rdata
  backend.io.pteFault := io.ptw.fault

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

  assert(!(branchRedirect && privilegedRedirect),
    "oldest-only F7 bare frontend cannot accept branch and privileged redirects simultaneously")
}
