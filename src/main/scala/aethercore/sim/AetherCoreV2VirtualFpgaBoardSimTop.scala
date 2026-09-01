package aethercore.sim

import chisel3._
import chisel3.util._
import aethercore.common.{AtomicOp, CommitTrace, MemSize}
import aethercore.config.CoreProfiles
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherCoreV2FpgaSoC, AetherSoCBoardSpec}
import aethercore.soc.phy.{AetherFractionalTickGenerator, AetherUart8N1Phy}

/**
  * Simulation-only virtual FPGA board.
  *
  * Unlike the older AXI compatibility top, this wrapper instantiates the real
  * FPGA-facing SoC. External memory traffic crosses the production AXI4 pins
  * into a virtual DDR target, while console traffic must cross the physical
  * serialTx/serialRx pins and a peer 8N1 UART before the historical Linux
  * runner can observe or inject bytes.
  *
  * The virtual board uses one 20 MHz source clock. Vendor-neutral fractional
  * clock enables derive the same 10 MHz architectural timebase and 3.6864 MHz
  * ns16550 reference clock declared by AetherSoCBoardSpec.
  */
class AetherCoreV2VirtualFpgaBoardSimTop(
    val virtualClockFrequencyHz: Long = 20_000_000L,
    val powerOnResetCycles: Int = 16,
    val implementedPaddrBits: Int = 56
) extends Module {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  require(virtualClockFrequencyHz >= 10_000_000L)
  require(powerOnResetCycles >= 1)

  private val xlen = 64
  private val paddrBits = implementedPaddrBits
  private val busDataBits = 64
  private val busBytes = busDataBits / 8
  require(paddrBits >= 32 && paddrBits <= 56, s"virtual FPGA implemented PA must be 32..56, got $paddrBits")
  private val boardSpec =
    AetherSoCBoardSpec.qualifiedLinux(
      CoreProfiles.rv64imasuSv39PmpSoftware.platform.copy(paddrBits = paddrBits)
    )

  val io = IO(new Bundle {
    // Historical host RAM seams retained only outside the virtual DDR model.
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

    // The C++ runner sees bytes only after they cross the FPGA serial pins.
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())

    val serialTxPin = Output(Bool())
    val serialRxPin = Output(Bool())
    val uartClockTick = Output(Bool())
    val timebaseTick = Output(Bool())
    val boardResetActive = Output(Bool())
    val uartBaudDivisor = Output(UInt(16.W))

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

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  // Board-level power-on-reset stretcher. The outer simulator reset initializes
  // this counter, then the virtual board keeps the production SoC reset for a
  // deterministic additional interval.
  private val resetCountBits = math.max(1, log2Ceil(powerOnResetCycles + 1))
  private val resetCount = RegInit(powerOnResetCycles.U(resetCountBits.W))
  when(resetCount =/= 0.U) {
    resetCount := resetCount - 1.U
  }
  private val boardReset = reset.asBool || resetCount =/= 0.U
  io.boardResetActive := boardReset

  private val uartTickGenerator = Module(new AetherFractionalTickGenerator(
    sourceFrequencyHz = virtualClockFrequencyHz,
    targetFrequencyHz = boardSpec.uartClockFrequencyHz
  ))
  private val timebaseTickGenerator = Module(new AetherFractionalTickGenerator(
    sourceFrequencyHz = virtualClockFrequencyHz,
    targetFrequencyHz = boardSpec.timebaseFrequencyHz
  ))

  io.uartClockTick := uartTickGenerator.io.tick
  io.timebaseTick := timebaseTickGenerator.io.tick

  private val fpga = withReset(boardReset) {
    Module(new AetherCoreV2FpgaSoC(implementedPaddrBits = paddrBits))
  }
  private val virtualDdr = withReset(boardReset) {
    Module(new AetherSoCAxi4HostMemoryAdapter(
      addrBits = paddrBits,
      dataBits = busDataBits,
      idBits = 4,
      localTxnIdBits = 2
    ))
  }
  private val hostUart = withReset(boardReset) {
    Module(new AetherUart8N1Phy)
  }

  // ------------------------------------------------------------------------
  // Real FPGA AXI pins -> virtual DDR target -> historical host RAM.
  // ------------------------------------------------------------------------
  virtualDdr.io.axi.aw.valid := fpga.io.axi.aw.valid
  virtualDdr.io.axi.aw.bits := fpga.io.axi.aw.bits
  fpga.io.axi.aw.ready := virtualDdr.io.axi.aw.ready

  virtualDdr.io.axi.w.valid := fpga.io.axi.w.valid
  virtualDdr.io.axi.w.bits := fpga.io.axi.w.bits
  fpga.io.axi.w.ready := virtualDdr.io.axi.w.ready

  fpga.io.axi.b.valid := virtualDdr.io.axi.b.valid
  fpga.io.axi.b.bits := virtualDdr.io.axi.b.bits
  virtualDdr.io.axi.b.ready := fpga.io.axi.b.ready

  virtualDdr.io.axi.ar.valid := fpga.io.axi.ar.valid
  virtualDdr.io.axi.ar.bits := fpga.io.axi.ar.bits
  fpga.io.axi.ar.ready := virtualDdr.io.axi.ar.ready

  fpga.io.axi.r.valid := virtualDdr.io.axi.r.valid
  fpga.io.axi.r.bits := virtualDdr.io.axi.r.bits
  virtualDdr.io.axi.r.ready := fpga.io.axi.r.ready

  io.imemValid := virtualDdr.io.imemValid
  io.imemAddr := virtualDdr.io.imemAddr
  io.imemBytes := virtualDdr.io.imemBytes
  virtualDdr.io.imemInst := io.imemInst
  virtualDdr.io.imemFault := io.imemFault

  io.ptwValid := virtualDdr.io.ptwValid
  io.ptwAddr := virtualDdr.io.ptwAddr
  virtualDdr.io.ptwReady := io.ptwReady
  virtualDdr.io.ptwRdata := io.ptwRdata
  virtualDdr.io.ptwFault := io.ptwFault

  io.memValid := virtualDdr.io.memValid
  io.memWrite := virtualDdr.io.memWrite
  io.memAtomic := virtualDdr.io.memAtomic
  io.memOp := virtualDdr.io.memOp
  io.memAtomicOp := virtualDdr.io.memAtomicOp
  io.memAddr := virtualDdr.io.memAddr
  io.memWdata := virtualDdr.io.memWdata
  io.memWmask := virtualDdr.io.memWmask
  io.memSize := virtualDdr.io.memSize
  virtualDdr.io.memReady := io.memReady
  virtualDdr.io.memRdata := io.memRdata
  virtualDdr.io.memFault := io.memFault

  // ------------------------------------------------------------------------
  // Real FPGA serial pins <-> fixed-115200 virtual USB-UART peer.
  // ------------------------------------------------------------------------
  fpga.io.uartClockTick := uartTickGenerator.io.tick
  fpga.io.timebaseTick := timebaseTickGenerator.io.tick

  hostUart.io.uartClockTick := uartTickGenerator.io.tick
  hostUart.io.baudDivisor := boardSpec.uartDefaultDivisor.U
  hostUart.io.serialRx := fpga.io.serialTx

  hostUart.io.txValid := io.rxValid
  hostUart.io.txByte := io.rxByte
  io.rxReady := hostUart.io.txReady
  fpga.io.serialRx := hostUart.io.serialTx

  hostUart.io.rxReady := true.B
  io.uartValid := hostUart.io.rxValid
  io.uartByte := hostUart.io.rxByte

  io.serialTxPin := fpga.io.serialTx
  io.serialRxPin := hostUart.io.serialTx
  io.uartBaudDivisor := fpga.io.uartBaudDivisor

  io.supervisorExternalInterrupt := fpga.io.supervisorExternalInterrupt
  io.uartInterrupt := fpga.io.uartInterrupt
  io.uartRxInterrupt := fpga.io.uartRxInterrupt
  io.timerInterrupt := fpga.io.timerInterrupt

  io.exitValid := fpga.io.exitValid
  io.exitCode := fpga.io.exitCode
  io.mtime := fpga.io.mtime
  io.mtimecmp := fpga.io.mtimecmp

  io.dcacheHitCount := fpga.io.dcacheHitCount
  io.dcacheMissCount := fpga.io.dcacheMissCount
  io.dcacheBypassCount := fpga.io.dcacheBypassCount
  io.icacheHitCount := fpga.io.icacheHitCount
  io.icacheMissCount := fpga.io.icacheMissCount

  io.commit := fpga.io.commit
  io.halted := fpga.io.halted
}
