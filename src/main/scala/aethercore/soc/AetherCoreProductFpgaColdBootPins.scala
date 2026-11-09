package aethercore.soc

import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}
import chisel3._

/** Product pin boundary with a real cold-start ownership gate.
  *
  * This composes the *same* CPU/AXI/UART physical boundary qualified by the
  * ordinary FPGA elaboration path; it does not introduce another CPU. A real
  * board provides its PLL, DDR PHY, external DDR image loader, and an AXI mux
  * selected by loaderOwnsDdr/cpuOwnsDdr. This module exports only the CPU's
  * AXI master; no loader bus or firmware bytes are generated internally.
  *
  * The external loader must own the DDR while loaderOwnsDdr is asserted and
  * hold imageLoaded after its final write has completed, with loaderIdle
  * covering all outstanding AXI responses. In the isolated handoff cycle
  * neither owner is granted; only then is the CPU reset released. Inputs
  * must be synchronized to this module's clock by the board integration.
  */
class AetherCoreProductFpgaColdBootPins(
    val implementedPaddrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val productGeometry: ProductCoreGeometry = ProductCoreProfiles.smallGeometry,
    val productConfigOverride: Option[ProductCoreConfig] = None,
    val boardDevices: ProductSoCBoardConfig = ProductSoCBoardProfiles.existingRamOnly,
    val cacheProfile: ProductSoCCacheProfile = ProductSoCCacheProfiles.productionP0
) extends Module {
  private val enableSpiSd = boardDevices.spiSdEnabled
  private val config = AetherCoreProductSoCProfiles.selectEngineeringTarget(
    implementedPaddrBits, productGeometry, productConfigOverride)
  private val txnBits =
    AetherCoreProductSoCProfiles.externalTransactionBits(config.geometry)

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

    val clockLocked = Input(Bool())
    val ddrCalibrated = Input(Bool())
    val imageLoaded = Input(Bool())
    val loaderIdle = Input(Bool())
    val imageLoadFailed = Input(Bool())

    val loaderOwnsDdr = Output(Bool())
    val cpuOwnsDdr = Output(Bool())
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
  })

  private val handoff = Module(new AetherProductColdBootControl)
  handoff.io.clockLocked := io.clockLocked
  handoff.io.ddrCalibrated := io.ddrCalibrated
  handoff.io.imageLoaded := io.imageLoaded
  handoff.io.loaderIdle := io.loaderIdle
  handoff.io.imageLoadFailed := io.imageLoadFailed

  io.loaderOwnsDdr := handoff.io.loaderOwnsDdr
  io.cpuOwnsDdr := handoff.io.cpuOwnsDdr
  io.cpuReleased := handoff.io.cpuReleased
  io.bootFault := handoff.io.bootFault

  // Holding reset also resets all caches, AXI transaction lifetimes and UART
  // state. cpuReleased deasserts combinationally if DDR/PLL readiness is lost,
  // ahead of the sticky-fault state's next clock edge.
  private val machine = withReset(reset.asBool || !handoff.io.cpuReleased) {
    Module(new AetherCoreProductFpgaPins(
      implementedPaddrBits = implementedPaddrBits,
      productConfigOverride = Some(config),
      boardDevices = boardDevices,
      cacheProfile = cacheProfile))
  }

  private val cpuRun = handoff.io.cpuReleased
  private val isolation = Module(new AetherProductAxiColdBootIsolation(
    addrBits = implementedPaddrBits,
    dataBits = 64,
    txnIdBits = txnBits))
  isolation.io.released := cpuRun

  isolation.io.cpu.aw.valid := machine.io.axi.aw.valid
  isolation.io.cpu.aw.bits := machine.io.axi.aw.bits
  machine.io.axi.aw.ready := isolation.io.cpu.aw.ready
  isolation.io.cpu.w.valid := machine.io.axi.w.valid
  isolation.io.cpu.w.bits := machine.io.axi.w.bits
  machine.io.axi.w.ready := isolation.io.cpu.w.ready
  machine.io.axi.b.valid := isolation.io.cpu.b.valid
  machine.io.axi.b.bits := isolation.io.cpu.b.bits
  isolation.io.cpu.b.ready := machine.io.axi.b.ready
  isolation.io.cpu.ar.valid := machine.io.axi.ar.valid
  isolation.io.cpu.ar.bits := machine.io.axi.ar.bits
  machine.io.axi.ar.ready := isolation.io.cpu.ar.ready
  machine.io.axi.r.valid := isolation.io.cpu.r.valid
  machine.io.axi.r.bits := isolation.io.cpu.r.bits
  isolation.io.cpu.r.ready := machine.io.axi.r.ready

  // No AXI request/response handshake may cross this boundary while the
  // loader owns DDR, during the isolated turnaround, or after a boot fault.
  io.axi.aw.valid := isolation.io.ddr.aw.valid
  io.axi.aw.bits := isolation.io.ddr.aw.bits
  isolation.io.ddr.aw.ready := io.axi.aw.ready
  io.axi.w.valid := isolation.io.ddr.w.valid
  io.axi.w.bits := isolation.io.ddr.w.bits
  isolation.io.ddr.w.ready := io.axi.w.ready
  isolation.io.ddr.b.valid := io.axi.b.valid
  isolation.io.ddr.b.bits := io.axi.b.bits
  io.axi.b.ready := isolation.io.ddr.b.ready
  io.axi.ar.valid := isolation.io.ddr.ar.valid
  io.axi.ar.bits := isolation.io.ddr.ar.bits
  isolation.io.ddr.ar.ready := io.axi.ar.ready
  isolation.io.ddr.r.valid := io.axi.r.valid
  isolation.io.ddr.r.bits := io.axi.r.bits
  io.axi.r.ready := isolation.io.ddr.r.ready

  machine.io.uartClockTick := io.uartClockTick
  machine.io.timebaseTick := io.timebaseTick
  machine.io.serialRx := io.serialRx
  io.serialTx := Mux(cpuRun, machine.io.serialTx, true.B)
  if (enableSpiSd) {
    machine.io.spiMiso.get := io.spiMiso.get
    io.spiSclk.get := Mux(cpuRun, machine.io.spiSclk.get, false.B)
    io.spiMosi.get := Mux(cpuRun, machine.io.spiMosi.get, true.B)
    io.spiCsN.get := Mux(cpuRun, machine.io.spiCsN.get, true.B)
  }
}
