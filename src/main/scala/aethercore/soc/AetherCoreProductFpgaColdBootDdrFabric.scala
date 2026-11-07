package aethercore.soc

import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}
import chisel3._

/** Product FPGA cold-start boundary with the actual DDR AXI ownership mux.
  *
  * A board loader provides its own single-beat AXI master, imageLoaded,
  * loaderIdle and imageLoadFailed. This shell arbitrates its AXI traffic with
  * Product's isolated AXI master and releases the CPU only when both the
  * loader's idle hint and the independently tracked AXI response ledger are
  * drained. Only the one physical DDR AXI master leaves this shell.
  *
  * PHY training, PLL, image source (flash/SD/JTAG/host), reset-domain crossing
  * and SD-card pads are explicitly board-owned and are not simulated here.
  * The physical system clock must match boardDevices.spiBusClockFrequencyHz
  * when SPI-SD is enabled, including the matching device-tree declaration.
  */
class AetherCoreProductFpgaColdBootDdrFabric(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.existingRamOnly
) extends Module {
  private val enableSpiSd = boardDevices.spiSdEnabled
  private val config = AetherCoreProductSoCProfiles.selectEngineeringTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val txnBits =
    AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)

  val io = IO(new Bundle {
    val loaderAxi = Flipped(new Axi4MasterIO(implementedPaddrBits, 64, txnBits))
    val ddrAxi = new Axi4MasterIO(implementedPaddrBits, 64, txnBits)

    val uartClockTick = Input(Bool())
    val timebaseTick = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val spiSclk = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMosi = if (enableSpiSd) Some(Output(Bool())) else None
    val spiMiso = if (enableSpiSd) Some(Input(Bool())) else None
    val spiCsN = if (enableSpiSd) Some(Output(Bool())) else None

    val clockLocked = Input(Bool())
    val ddrCalibrated = Input(Bool())
    val imageLoaded = Input(Bool())
    val loaderIdle = Input(Bool())
    val imageLoadFailed = Input(Bool())

    val loaderOwnsDdr = Output(Bool())
    val cpuOwnsDdr = Output(Bool())
    val loaderDrained = Output(Bool())
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
  })

  private val cold = Module(new AetherCoreProductFpgaColdBootPins(
    implementedPaddrBits = implementedPaddrBits,
    productConfigOverride = Some(config),
    boardDevices = boardDevices))
  private val addressMap = AetherSoCAddressMap.qualifiedLinux(config.platform)
  private val ownership = Module(new AetherProductDdrOwnershipMux(
    addrBits = implementedPaddrBits, dataBits = 64, txnIdBits = txnBits,
    allowedRamBase = addressMap.ramBase, allowedRamBytes = addressMap.ramBytes))

  cold.io.clockLocked := io.clockLocked
  cold.io.ddrCalibrated := io.ddrCalibrated
  cold.io.imageLoaded := io.imageLoaded
  cold.io.imageLoadFailed := io.imageLoadFailed || ownership.io.loaderAddressFault
  // A loader cannot turn its externally declared idle into a handoff while
  // any DDR AW/W/B/AR/R response remains accepted-but-not-retired.
  cold.io.loaderIdle := io.loaderIdle && ownership.io.loaderDrained
  ownership.io.loaderOwnsDdr := cold.io.loaderOwnsDdr
  ownership.io.cpuOwnsDdr := cold.io.cpuOwnsDdr

  io.loaderOwnsDdr := cold.io.loaderOwnsDdr
  io.cpuOwnsDdr := cold.io.cpuOwnsDdr
  io.loaderDrained := ownership.io.loaderDrained
  io.cpuReleased := cold.io.cpuReleased
  io.bootFault := cold.io.bootFault

  ownership.io.loader.aw.valid := io.loaderAxi.aw.valid
  ownership.io.loader.aw.bits := io.loaderAxi.aw.bits
  io.loaderAxi.aw.ready := ownership.io.loader.aw.ready
  ownership.io.loader.w.valid := io.loaderAxi.w.valid
  ownership.io.loader.w.bits := io.loaderAxi.w.bits
  io.loaderAxi.w.ready := ownership.io.loader.w.ready
  io.loaderAxi.b.valid := ownership.io.loader.b.valid
  io.loaderAxi.b.bits := ownership.io.loader.b.bits
  ownership.io.loader.b.ready := io.loaderAxi.b.ready
  ownership.io.loader.ar.valid := io.loaderAxi.ar.valid
  ownership.io.loader.ar.bits := io.loaderAxi.ar.bits
  io.loaderAxi.ar.ready := ownership.io.loader.ar.ready
  io.loaderAxi.r.valid := ownership.io.loader.r.valid
  io.loaderAxi.r.bits := ownership.io.loader.r.bits
  ownership.io.loader.r.ready := io.loaderAxi.r.ready

  ownership.io.cpu.aw.valid := cold.io.axi.aw.valid
  ownership.io.cpu.aw.bits := cold.io.axi.aw.bits
  cold.io.axi.aw.ready := ownership.io.cpu.aw.ready
  ownership.io.cpu.w.valid := cold.io.axi.w.valid
  ownership.io.cpu.w.bits := cold.io.axi.w.bits
  cold.io.axi.w.ready := ownership.io.cpu.w.ready
  cold.io.axi.b.valid := ownership.io.cpu.b.valid
  cold.io.axi.b.bits := ownership.io.cpu.b.bits
  ownership.io.cpu.b.ready := cold.io.axi.b.ready
  ownership.io.cpu.ar.valid := cold.io.axi.ar.valid
  ownership.io.cpu.ar.bits := cold.io.axi.ar.bits
  cold.io.axi.ar.ready := ownership.io.cpu.ar.ready
  cold.io.axi.r.valid := ownership.io.cpu.r.valid
  cold.io.axi.r.bits := ownership.io.cpu.r.bits
  ownership.io.cpu.r.ready := cold.io.axi.r.ready

  io.ddrAxi.aw.valid := ownership.io.ddr.aw.valid
  io.ddrAxi.aw.bits := ownership.io.ddr.aw.bits
  ownership.io.ddr.aw.ready := io.ddrAxi.aw.ready
  io.ddrAxi.w.valid := ownership.io.ddr.w.valid
  io.ddrAxi.w.bits := ownership.io.ddr.w.bits
  ownership.io.ddr.w.ready := io.ddrAxi.w.ready
  ownership.io.ddr.b.valid := io.ddrAxi.b.valid
  ownership.io.ddr.b.bits := io.ddrAxi.b.bits
  io.ddrAxi.b.ready := ownership.io.ddr.b.ready
  io.ddrAxi.ar.valid := ownership.io.ddr.ar.valid
  io.ddrAxi.ar.bits := ownership.io.ddr.ar.bits
  ownership.io.ddr.ar.ready := io.ddrAxi.ar.ready
  ownership.io.ddr.r.valid := io.ddrAxi.r.valid
  ownership.io.ddr.r.bits := io.ddrAxi.r.bits
  io.ddrAxi.r.ready := ownership.io.ddr.r.ready

  cold.io.uartClockTick := io.uartClockTick
  cold.io.timebaseTick := io.timebaseTick
  cold.io.serialRx := io.serialRx
  io.serialTx := cold.io.serialTx
  if (enableSpiSd) {
    cold.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := cold.io.spiSclk.get
    io.spiMosi.get := cold.io.spiMosi.get
    io.spiCsN.get := cold.io.spiCsN.get
  }
}
