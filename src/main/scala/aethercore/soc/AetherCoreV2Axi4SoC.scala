package aethercore.soc

import chisel3._
import aethercore.common.CommitTrace

/**
  * FPGA-facing logical AetherSoC v0.
  *
  * The complete logical SoC remains above this boundary: CPU complex, I/D
  * caches, Sv39/PTW, PMA, platform fabric, UART, PLIC, MTIMER and BootROM.
  * Only requests that escaped the internal SoC address map as external memory
  * reach the semantic AetherMem master translated here into standard AXI4.
  *
  * Board-specific clock/reset generation, DDR-controller instances, pin
  * constraints and serial UART PHY remain outside this reusable logical SoC.
  */
object AetherCoreV2Axi4SoC {
  // MemoryHub client/source encoding remains 4-bit end-to-end, but the
  // qualified product can only emit normal-read IDs 0/1/2 (data), 4 (PTW),
  // and 8 (I-cache). The AXI bridge may therefore specialize metadata storage
  // without changing the architectural AXI ID width or response routing.
  val QualifiedNormalReadTxnIds: Seq[Int] = Seq(0, 1, 2, 4, 8)
}

class AetherCoreV2Axi4SoC(
    val implementedPaddrBits: Int = 56
) extends Module {
  private val Xlen = 64
  private val PaddrBits = implementedPaddrBits
  private val DataBits = 64
  private val TxnIdBits = 4

  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(PaddrBits, DataBits, TxnIdBits)

    // Byte-stream UART boundary. The later FPGA wrapper owns the physical
    // serial serializer/deserializer and baud-rate clocking.
    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val uartBaudDivisor = Output(UInt(16.W))
    // Physical-platform seams: a board wrapper owns serializer throughput and
    // the architectural 10 MHz timebase tick.
    val uartTxReady = Input(Bool())
    val timebaseTick = Input(Bool())

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

  val soc = Module(new AetherCoreV2UnifiedMemorySoC(
    externalPhysicalSeams = true,
    implementedPaddrBits = implementedPaddrBits
  ))
  val bridge = Module(new AetherMemToAxi4Bridge(
    addrBits = PaddrBits,
    dataBits = DataBits,
    txnIdBits = TxnIdBits,
    normalReadTxnIds = Some(AetherCoreV2Axi4SoC.QualifiedNormalReadTxnIds)
  ))
  require(
    soc.externalTxnIdBits == TxnIdBits,
    "AXI4 top transaction ID width must match the unified-memory SoC boundary"
  )

  bridge.io.request <> soc.io.memoryRequest
  soc.io.memoryResponse <> bridge.io.response

  io.axi.aw.valid := bridge.io.axi.aw.valid
  io.axi.aw.bits := bridge.io.axi.aw.bits
  bridge.io.axi.aw.ready := io.axi.aw.ready

  io.axi.w.valid := bridge.io.axi.w.valid
  io.axi.w.bits := bridge.io.axi.w.bits
  bridge.io.axi.w.ready := io.axi.w.ready

  bridge.io.axi.b.valid := io.axi.b.valid
  bridge.io.axi.b.bits := io.axi.b.bits
  io.axi.b.ready := bridge.io.axi.b.ready

  io.axi.ar.valid := bridge.io.axi.ar.valid
  io.axi.ar.bits := bridge.io.axi.ar.bits
  bridge.io.axi.ar.ready := io.axi.ar.ready

  bridge.io.axi.r.valid := io.axi.r.valid
  bridge.io.axi.r.bits := io.axi.r.bits
  io.axi.r.ready := bridge.io.axi.r.ready

  soc.io.rxValid := io.rxValid
  soc.io.rxByte := io.rxByte
  soc.io.uartTxReady.get := io.uartTxReady
  soc.io.timebaseTick.get := io.timebaseTick
  io.rxReady := soc.io.rxReady
  io.uartValid := soc.io.uartValid
  io.uartByte := soc.io.uartByte
  io.uartBaudDivisor := soc.io.uartBaudDivisor

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
