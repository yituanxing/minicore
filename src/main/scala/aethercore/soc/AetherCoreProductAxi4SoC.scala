package aethercore.soc

import aethercore.common.CommitTrace
import aethercore.core.product.{ProductCoreGeometry, ProductCoreProfiles, ProductRetirement}
import chisel3._
import chisel3.util._

/** Standard AXI4 memory boundary for the independent SmallOoO Product SoC.
  *
  * Keeping the complete CPU, cache, PTW, peripherals and BootROM above this
  * adapter means AXI is used solely for external RAM. The older V2 AXI path
  * is not reused as a CPU and remains qualified independently.
  */
class AetherCoreProductAxi4SoC(
    val implementedPaddrBits: Int = 56,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry
) extends Module {
  private val Xlen = 64
  private val DataBits = 64
  private val TxnIdBits = AetherCoreProductSoCProfiles.externalTransactionBits(productGeometry)
  private val config = AetherCoreProductSoCProfiles.smallOpenSbiEngineering(
    implementedPaddrBits, productGeometry)
  private val geometry = config.geometry

  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(implementedPaddrBits, DataBits, TxnIdBits)

    val rxValid = Input(Bool())
    val rxByte = Input(UInt(8.W))
    val rxReady = Output(Bool())
    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val uartBaudDivisor = Output(UInt(16.W))
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

    val commit = Output(new CommitTrace(Xlen, implementedPaddrBits, DataBits))
    val retire = Valid(new ProductRetirement(
      Xlen,
      log2Ceil(geometry.robEntries),
      log2Ceil(geometry.integerPhysicalRegisters),
      16,
      implementedPaddrBits
    ))
    val currentPrivilege = Output(UInt(2.W))
    val frontendPc = Output(UInt(Xlen.W))
    val halted = Output(Bool())
  })

  val soc = Module(new AetherCoreProductUnifiedMemorySoC(
    externalPhysicalSeams = true,
    implementedPaddrBits = implementedPaddrBits,
    productGeometry = productGeometry
  ))
  val bridge = Module(new AetherMemToAxi4Bridge(
    addrBits = implementedPaddrBits,
    dataBits = DataBits,
    txnIdBits = TxnIdBits
  ))
  require(soc.externalTxnIdBits == TxnIdBits,
    "Product AXI4 transaction ID width must match the unified-memory hub")

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
  io.retire := soc.io.retire
  io.currentPrivilege := soc.io.currentPrivilege
  io.frontendPc := soc.io.frontendPc
  io.halted := soc.io.halted
}
