package aethercore.sim

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.memory.AetherMemOp
import aethercore.soc.AetherCoreV2Axi4SoC

/**
  * Simulation-only Linux compatibility shell for the FPGA-facing AXI4 SoC.
  *
  * The external C++ runner still sees the historical host-memory ABI, but every
  * CPU memory transaction has already traversed:
  *
  *   I/PTW/D -> adapters -> MemoryHub -> internal BootROM decode
  *           -> external AetherMem -> AXI4 bridge.
  *
  * This top therefore dynamically qualifies the production AXI path without
  * introducing a second CPU/platform implementation.
  */
class AetherCoreV2Axi4CompatSimTop extends Module {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val xlen = 64
  private val paddrBits = 56
  private val busDataBits = 64
  private val busBytes = busDataBits / 8

  val io = IO(new Bundle {
    val imemValid = Output(Bool())
    val imemAddr = Output(UInt(paddrBits.W))
    val imemBytes = Output(UInt(3.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

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

    val ptwValid = Output(Bool())
    val ptwAddr = Output(UInt(paddrBits.W))
    val ptwReady = Input(Bool())
    val ptwRdata = Input(UInt(64.W))
    val ptwFault = Input(Bool())

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())

    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())

    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val icacheHitCount = Output(UInt(64.W))
    val icacheMissCount = Output(UInt(64.W))

    // Simulation-only observability for same-source AXI read concurrency.
    // Qualified compact IDs reserve 0/1/2 for Data, 3 for PTW and 4 for
    // I-cache. These counters must never feed production request/response control.
    val axiDataReadRequestCount = Output(UInt(64.W))
    val axiDataReadResponseCount = Output(UInt(64.W))
    val axiDataReadOverlapIssueCount = Output(UInt(64.W))
    val axiDataReadTwoOutstandingCycles = Output(UInt(64.W))
    val axiDataReadMaxOutstanding = Output(UInt(3.W))

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  val soc = Module(new AetherCoreV2Axi4SoC)
  val hostMemory = Module(new AetherSoCAxi4HostMemoryAdapter(
    addrBits = paddrBits,
    dataBits = busDataBits,
    idBits = 3,
    localTxnIdBits = 2,
    compactQualifiedTxnIds = true
  ))

  hostMemory.io.axi.aw.valid := soc.io.axi.aw.valid
  hostMemory.io.axi.aw.bits := soc.io.axi.aw.bits
  soc.io.axi.aw.ready := hostMemory.io.axi.aw.ready

  hostMemory.io.axi.w.valid := soc.io.axi.w.valid
  hostMemory.io.axi.w.bits := soc.io.axi.w.bits
  soc.io.axi.w.ready := hostMemory.io.axi.w.ready

  soc.io.axi.b.valid := hostMemory.io.axi.b.valid
  soc.io.axi.b.bits := hostMemory.io.axi.b.bits
  hostMemory.io.axi.b.ready := soc.io.axi.b.ready

  hostMemory.io.axi.ar.valid := soc.io.axi.ar.valid
  hostMemory.io.axi.ar.bits := soc.io.axi.ar.bits
  soc.io.axi.ar.ready := hostMemory.io.axi.ar.ready

  soc.io.axi.r.valid := hostMemory.io.axi.r.valid
  soc.io.axi.r.bits := hostMemory.io.axi.r.bits
  hostMemory.io.axi.r.ready := soc.io.axi.r.ready

  // Observe the production AXI boundary after every upstream cache/fabric/hub
  // policy decision. Source 0 is the Data client and its low two ID bits retain
  // the CPU-complex transaction identity. This is observation only.
  private val dataArFire =
    soc.io.axi.ar.fire && soc.io.axi.ar.bits.id <= 2.U
  private val dataRFire =
    soc.io.axi.r.fire && soc.io.axi.r.bits.last &&
      soc.io.axi.r.bits.id <= 2.U

  private val dataReadOutstanding = RegInit(0.U(3.W))
  private val dataReadRequestCount = RegInit(0.U(64.W))
  private val dataReadResponseCount = RegInit(0.U(64.W))
  private val dataReadOverlapIssueCount = RegInit(0.U(64.W))
  private val dataReadTwoOutstandingCycles = RegInit(0.U(64.W))
  private val dataReadMaxOutstanding = RegInit(0.U(3.W))

  private val dataReadOutstandingNext = WireDefault(dataReadOutstanding)
  switch(Cat(dataArFire, dataRFire)) {
    is("b10".U) { dataReadOutstandingNext := dataReadOutstanding + 1.U }
    is("b01".U) { dataReadOutstandingNext := dataReadOutstanding - 1.U }
  }

  when(dataArFire) {
    dataReadRequestCount := dataReadRequestCount + 1.U
    when(dataReadOutstanding =/= 0.U) {
      dataReadOverlapIssueCount := dataReadOverlapIssueCount + 1.U
    }
  }
  when(dataRFire) {
    dataReadResponseCount := dataReadResponseCount + 1.U
  }
  when(dataReadOutstanding >= 2.U) {
    dataReadTwoOutstandingCycles := dataReadTwoOutstandingCycles + 1.U
  }
  when(dataReadOutstandingNext > dataReadMaxOutstanding) {
    dataReadMaxOutstanding := dataReadOutstandingNext
  }
  dataReadOutstanding := dataReadOutstandingNext

  assert(dataReadOutstanding <= 4.U,
    "simulation Data AXI read occupancy must fit the four local transaction IDs")

  io.axiDataReadRequestCount := dataReadRequestCount
  io.axiDataReadResponseCount := dataReadResponseCount
  io.axiDataReadOverlapIssueCount := dataReadOverlapIssueCount
  io.axiDataReadTwoOutstandingCycles := dataReadTwoOutstandingCycles
  io.axiDataReadMaxOutstanding := dataReadMaxOutstanding

  io.imemValid := hostMemory.io.imemValid
  io.imemAddr := hostMemory.io.imemAddr
  io.imemBytes := hostMemory.io.imemBytes
  hostMemory.io.imemInst := io.imemInst
  hostMemory.io.imemFault := io.imemFault

  io.ptwValid := hostMemory.io.ptwValid
  io.ptwAddr := hostMemory.io.ptwAddr
  hostMemory.io.ptwReady := io.ptwReady
  hostMemory.io.ptwRdata := io.ptwRdata
  hostMemory.io.ptwFault := io.ptwFault

  io.memValid := hostMemory.io.memValid
  io.memWrite := hostMemory.io.memWrite
  io.memAtomic := hostMemory.io.memAtomic
  io.memOp := hostMemory.io.memOp
  io.memAtomicOp := hostMemory.io.memAtomicOp
  io.memAddr := hostMemory.io.memAddr
  io.memWdata := hostMemory.io.memWdata
  io.memWmask := hostMemory.io.memWmask
  io.memSize := hostMemory.io.memSize
  hostMemory.io.memReady := io.memReady
  hostMemory.io.memRdata := io.memRdata
  hostMemory.io.memFault := io.memFault

  soc.io.rxValid := io.rxValid
  soc.io.rxByte := io.rxByte
  // Preserve the qualified simulator semantics: one simulator cycle is one
  // architectural timebase tick and the host UART sink is always ready.
  soc.io.uartTxReady := true.B
  soc.io.timebaseTick := true.B
  io.rxReady := soc.io.rxReady
  io.uartValid := soc.io.uartValid
  io.uartByte := soc.io.uartByte

  io.supervisorExternalInterrupt := soc.io.supervisorExternalInterrupt
  io.uartInterrupt := soc.io.uartInterrupt
  io.uartRxInterrupt := soc.io.uartRxInterrupt
  io.timerInterrupt := soc.io.timerInterrupt

  io.exitValid := soc.io.exitValid
  io.exitCode := soc.io.exitCode
  io.mtime := soc.io.mtime
  io.mtimecmp := soc.io.mtimecmp

  io.dcacheHitCount := soc.io.dcacheHitCount
  io.dcacheMissCount := soc.io.dcacheMissCount
  io.dcacheBypassCount := soc.io.dcacheBypassCount
  io.icacheHitCount := soc.io.icacheHitCount
  io.icacheMissCount := soc.io.icacheMissCount
  io.commit := soc.io.commit
  io.halted := soc.io.halted
}
