package aethercore.soc

import chisel3._
import aethercore.common.CommitTrace
import aethercore.soc.phy.AetherUart8N1Phy

/**
  * Board-neutral FPGA-facing AetherSoC top with a real serial pin boundary.
  *
  * DDR/controller and clock-generation details remain board owned. The wrapper
  * consumes two architectural clock-enable pulses:
  *   - uartClockTick at AetherSoCBoardSpec.uartClockFrequencyHz
  *   - timebaseTick at AetherSoCBoardSpec.timebaseFrequencyHz
  *
  * The ns16550 DLL/DLM divisor is carried from the software-visible register
  * block into the 8N1 PHY, so Linux/OpenSBI baud programming controls the
  * physical serial line rather than only simulation metadata.
  */
class AetherCoreV2FpgaSoC(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits
) extends Module {
  private val Xlen = 64
  private val PaddrBits = implementedPaddrBits
  private val DataBits = 64
  private val TxnIdBits = 4

  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(PaddrBits, DataBits, TxnIdBits)

    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val uartBaudDivisor = Output(UInt(16.W))

    val supervisorExternalInterrupt = Output(Bool())
    val uartInterrupt = Output(Bool())
    val uartRxInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())

    val exitValid = Output(Bool())
    val exitCode = Output(UInt(Xlen.W))
    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))

    val dcacheHitCount = Output(UInt(64.W))
    val dcacheMissCount = Output(UInt(64.W))
    val dcacheBypassCount = Output(UInt(64.W))
    val icacheHitCount = Output(UInt(64.W))
    val icacheMissCount = Output(UInt(64.W))

    val commit = Output(new CommitTrace(Xlen, PaddrBits, DataBits))
    val halted = Output(Bool())
  })

  val soc = Module(new AetherCoreV2Axi4SoC(
    implementedPaddrBits = implementedPaddrBits
  ))
  val uartPhy = Module(new AetherUart8N1Phy)

  io.axi.aw.valid := soc.io.axi.aw.valid
  io.axi.aw.bits := soc.io.axi.aw.bits
  soc.io.axi.aw.ready := io.axi.aw.ready

  io.axi.w.valid := soc.io.axi.w.valid
  io.axi.w.bits := soc.io.axi.w.bits
  soc.io.axi.w.ready := io.axi.w.ready

  soc.io.axi.b.valid := io.axi.b.valid
  soc.io.axi.b.bits := io.axi.b.bits
  io.axi.b.ready := soc.io.axi.b.ready

  io.axi.ar.valid := soc.io.axi.ar.valid
  io.axi.ar.bits := soc.io.axi.ar.bits
  soc.io.axi.ar.ready := io.axi.ar.ready

  soc.io.axi.r.valid := io.axi.r.valid
  soc.io.axi.r.bits := io.axi.r.bits
  io.axi.r.ready := soc.io.axi.r.ready

  uartPhy.io.uartClockTick := io.uartClockTick
  uartPhy.io.baudDivisor := soc.io.uartBaudDivisor

  uartPhy.io.txValid := soc.io.uartValid
  uartPhy.io.txByte := soc.io.uartByte
  soc.io.uartTxReady := uartPhy.io.txReady

  soc.io.rxValid := uartPhy.io.rxValid
  soc.io.rxByte := uartPhy.io.rxByte
  uartPhy.io.rxReady := soc.io.rxReady

  uartPhy.io.serialRx := io.serialRx
  io.serialTx := uartPhy.io.serialTx
  io.uartBaudDivisor := soc.io.uartBaudDivisor

  soc.io.timebaseTick := io.timebaseTick

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
