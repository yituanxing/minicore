package aethercore.soc

import chisel3._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.memory.{AetherMemOp, MemoryAttributes}

/**
  * Qualified RV64 OpenSBI/Linux compatibility top.
  *
  * CPU-internal state belongs to AetherCoreV2Complex. Data-side PMA, address
  * decode, peripherals and external-RAM routing belong to AetherSoCPlatformFabric.
  * This wrapper now retains only the historical instruction/PTW compatibility
  * seams plus host-observability wiring used by the existing Linux oracle.
  */
class AetherCoreV2LinuxSoC(
    val enableInstructionBackpressure: Boolean = false,
    val exposeExternalMemoryAttributes: Boolean = false,
    val externalPhysicalSeams: Boolean = false
) extends Module {
  private val config = CoreProfiles.rv64imasuSv39PmpSoftware.copy(
    name = "rv64imasu-sv39-pmp-opensbi-v2",
    isa = CoreProfiles.rv64imasuSv39PmpSoftware.isa.copy(
      zExtensions = CoreProfiles.rv64imasuSv39PmpSoftware.isa.zExtensions + "Zifencei",
      machineProvidedSupervisorTimer = true,
      timeCounter = true
    ),
    platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform.copy(
      resetVector = AetherSoCAddressMap.QualifiedBootRomBase
    )
  )
  private val geometry = PageTableGeometry.Sv39
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes
  private val txnIdBits = 2
  private val board = AetherSoCBoardSpec.qualifiedLinux(config.platform)
  private val addressMap = board.addressMap

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(paddrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())
    val imemReady =
      if (enableInstructionBackpressure) Some(Input(Bool())) else None

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAtomic = Output(Bool())
    val memOp = Output(AetherMemOp())
    val memAtomicOp = Output(AtomicOp())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(busDataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(busDataBits.W))
    val memFault = Input(Bool())
    val memAttributes =
      if (exposeExternalMemoryAttributes) Some(Output(new MemoryAttributes)) else None

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(paddrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(geometry.pteBits.W))
    val ptwFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartTxReady =
      if (externalPhysicalSeams) Some(Input(Bool())) else None
    val timebaseTick =
      if (externalPhysicalSeams) Some(Input(Bool())) else None
    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val instructionFence = Output(Bool())

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  // Keep this public val name during the compatibility migration: existing
  // attribution tooling observes core-internal read-only state through it.
  val core = Module(new AetherCoreV2Complex(
    config,
    geometry,
    txnIdBits = txnIdBits,
    dcacheEntries = 64,
    enableInstructionBackpressure = enableInstructionBackpressure
  ))

  // Instruction and PTW transport remain direct read-only host-memory ports
  // for now. The unified-memory SoC wrapper already adapts these to AetherMem.
  io.imemValid := core.io.imem.valid
  io.imemAddr := core.io.imem.addr
  io.imemBytes := core.io.imem.bytes
  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault
  if (enableInstructionBackpressure) {
    core.io.imemReady.get := io.imemReady.get
  }

  io.ptwValid := core.io.ptw.valid
  io.ptwAddr := core.io.ptw.addr
  core.io.ptw.ready := io.ptwReady
  core.io.ptw.rdata := io.ptwRdata
  core.io.ptw.fault := io.ptwFault

  val fabric = Module(new AetherSoCPlatformFabric(
    paddrBits = paddrBits,
    dataBits = busDataBits,
    txnIdBits = txnIdBits,
    addressMap = addressMap,
    plicSourceCount = board.plicSourceCount,
    uartPlicSourceId = board.uartPlicSourceId
  ))

  // CPU-complex semantic memory and PMA seam.
  fabric.io.resolvedPhysicalAddress := core.io.resolvedPhysicalAddress
  core.io.resolvedAttributes := fabric.io.resolvedAttributes
  fabric.io.request <> core.io.memoryRequest
  core.io.memoryResponse <> fabric.io.response

  core.io.supervisorExternalInterrupt := fabric.io.supervisorExternalInterrupt
  core.io.time := fabric.io.time
  core.io.timerInterrupt := fabric.io.timerInterrupt

  // Historical external-memory compatibility seam. AetherCoreV2UnifiedMemorySoC
  // converts this terminal-response interface back into a Decoupled AetherMem
  // client for the synthesizable unified-memory boundary.
  io.memValid := fabric.io.memValid
  io.memWrite := fabric.io.memWrite
  io.memAtomic := fabric.io.memAtomic
  io.memOp := fabric.io.memOp
  io.memAtomicOp := fabric.io.memAtomicOp
  io.memAddr := fabric.io.memAddr
  io.memWdata := fabric.io.memWdata
  io.memWmask := fabric.io.memWmask
  io.memSize := fabric.io.memSize
  fabric.io.memReady := io.memReady
  fabric.io.memRdata := io.memRdata
  fabric.io.memFault := io.memFault
  if (exposeExternalMemoryAttributes) {
    io.memAttributes.get := fabric.io.memAttributes
  }

  // Board-facing UART byte stream.
  fabric.io.rxValid := io.rxValid
  fabric.io.rxByte := io.rxByte
  fabric.io.uartTxReady :=
    (if (externalPhysicalSeams) io.uartTxReady.get else true.B)
  fabric.io.timebaseTick :=
    (if (externalPhysicalSeams) io.timebaseTick.get else true.B)
  io.rxReady := fabric.io.rxReady
  io.uartValid := fabric.io.uartValid
  io.uartByte := fabric.io.uartByte

  io.supervisorExternalInterrupt := fabric.io.supervisorExternalInterrupt
  io.uartInterrupt := fabric.io.uartInterrupt
  io.uartRxInterrupt := fabric.io.uartRxInterrupt
  io.timerInterrupt := fabric.io.timerInterrupt

  io.exitValid := fabric.io.exitValid
  io.exitCode := fabric.io.exitCode
  io.mtime := fabric.io.mtime
  io.mtimecmp := fabric.io.mtimecmp

  io.dcacheHitCount := core.io.dcacheHitCount
  io.dcacheMissCount := core.io.dcacheMissCount
  io.dcacheBypassCount := core.io.dcacheBypassCount
  io.instructionFence := core.io.instructionFence
  io.commit := core.io.commit
  io.halted := core.io.halted
}
