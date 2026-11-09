package aethercore.soc

import aethercore.common.CommitTrace
import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles, ProductRetirement}
import aethercore.soc.phy.AetherUart8N1Phy
import chisel3._
import chisel3.util._

/** Board-neutral FPGA shell for the real SmallOoO Product CPU.
  *
  * All memory mastering exits through the same AXI4 contract as the V2 SoC.
  * Serial RX/TX are genuine 8N1 pins; UART and architectural timebase ticks
  * remain the board-clock generator's responsibility. FPGA pin constraints,
  * DDR controller, reset synchronization and measured Fmax are still separate
  * board qualifications and are not claimed by RTL elaboration alone.
  */
class AetherCoreProductFpgaSoC(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    // Legacy geometry selection is used only when no exact CPU target is given.
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.existingRamOnly,
    val cacheProfile: ProductSoCCacheProfile = ProductSoCCacheProfiles.productionP0
) extends Module {
  private val enableSpiSd = boardDevices.spiSdEnabled
  val config: ProductCoreConfig = AetherCoreProductSoCProfiles.selectEngineeringTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val geometry = config.geometry
  private val Xlen = 64
  private val DataBits = 64
  private val TxnIdBits = AetherCoreProductSoCProfiles.externalTransactionBits(geometry)

  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(implementedPaddrBits, DataBits, TxnIdBits)
    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMosi = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMiso = if (enableSpiSd) Some(Input(Bool())) else None
    val spiCsN = if (enableSpiSd) Some(Output(Bool())) else None
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

    val commit = Output(new CommitTrace(Xlen, implementedPaddrBits, DataBits,
      withFloating = config.software.isa.extensions.contains('F')))
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

  val soc = Module(new AetherCoreProductAxi4SoC(
    implementedPaddrBits = implementedPaddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices,
    cacheProfile = cacheProfile))
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
  if (enableSpiSd) {
    soc.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := soc.io.spiSclk.get
    io.spiMosi.get := soc.io.spiMosi.get
    io.spiCsN.get := soc.io.spiCsN.get
  }
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
