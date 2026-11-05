package aethercore.soc

import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}
import chisel3._

/** Physical FPGA boundary for the same Product CPU and SoC used by the
  * verification shell. The board exposes only AXI, the serial pins and the
  * externally generated UART/timebase ticks. It never changes the processor,
  * its interrupts, or its memory ordering.
  *
  * Keeping architectural retire/commit traces inside this wrapper lets FPGA
  * synthesis remove unobserved probe fanout and counters. The instrumented
  * AetherCoreProductFpgaSoC remains available, unchanged, for differential
  * simulation and exact architectural qualification.
  */
class AetherCoreProductFpgaPins(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.existingRamOnly
) extends Module {
  private val enableSpiSd = boardDevices.spiSdEnabled
  val config: ProductCoreConfig = productConfigOverride.getOrElse(
    AetherCoreProductSoCProfiles.smallOpenSbiEngineering(
      implementedPaddrBits, productGeometry))
  AetherCoreProductSoCProfiles.requireBoardCompatible(config, implementedPaddrBits)

  private val txnBits = AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)
  val io = IO(new Bundle {
    val axi = new Axi4MasterIO(implementedPaddrBits, 64, txnBits)
    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMosi = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMiso = if (enableSpiSd) Some(Input(Bool())) else None
    val spiCsN = if (enableSpiSd) Some(Output(Bool())) else None
  })

  private val machine = Module(new AetherCoreProductFpgaSoC(
    implementedPaddrBits = implementedPaddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices))

  io.axi.aw.valid := machine.io.axi.aw.valid
  io.axi.aw.bits := machine.io.axi.aw.bits
  machine.io.axi.aw.ready := io.axi.aw.ready
  io.axi.w.valid := machine.io.axi.w.valid
  io.axi.w.bits := machine.io.axi.w.bits
  machine.io.axi.w.ready := io.axi.w.ready
  machine.io.axi.b.valid := io.axi.b.valid
  machine.io.axi.b.bits := io.axi.b.bits
  io.axi.b.ready := machine.io.axi.b.ready
  io.axi.ar.valid := machine.io.axi.ar.valid
  io.axi.ar.bits := machine.io.axi.ar.bits
  machine.io.axi.ar.ready := io.axi.ar.ready
  machine.io.axi.r.valid := io.axi.r.valid
  machine.io.axi.r.bits := io.axi.r.bits
  io.axi.r.ready := machine.io.axi.r.ready

  machine.io.uartClockTick := io.uartClockTick
  machine.io.timebaseTick := io.timebaseTick
  machine.io.serialRx := io.serialRx
  io.serialTx := machine.io.serialTx
  if (enableSpiSd) {
    machine.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := machine.io.spiSclk.get
    io.spiMosi.get := machine.io.spiMosi.get
    io.spiCsN.get := machine.io.spiCsN.get
  }
}
