package aethercore.soc

import _root_.circt.stage.ChiselStage
import aethercore.core.product.{ProductCoreConfig, ProductCoreGeometry, ProductCoreProfiles}

/** Keep board/profile selection identical across the public runMain entrypoints.
  * The entrypoint object names and arguments are part of the external CI and
  * FPGA-emission contract; only their repeated implementation is shared.
  */
private object ProductSoCElaboration {
  def firtoolOptions: Array[String] =
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")

  def geometry(args: Array[String], missingMessage: String,
               unknownContext: String): ProductCoreGeometry = {
    require(args.nonEmpty, missingMessage)
    args.head match {
      case "small" => ProductCoreProfiles.smallGeometry
      case "expanded" => ProductCoreProfiles.expandedSingleLaneGeometry
      case other => throw new IllegalArgumentException(s"unknown $unknownContext geometry: $other")
    }
  }

  def rv64gcTarget(geometry: ProductCoreGeometry): ProductCoreConfig =
    AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(32, geometry)
}

/** Independent emission entrypoints; never replace the qualified V2 SoC top.
  *
  * The 32-bit PA FPGA shell must be synthesized with the actual board DDR/clock
  * constraints before any frequency or Linux-boot qualification is claimed.
  */
object ElaborateProductUnifiedMemorySoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductUnifiedMemorySoC(),
    args,
    ProductSoCElaboration.firtoolOptions
  )
}

object ElaborateProductAxi4SoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductAxi4SoC(),
    args,
    ProductSoCElaboration.firtoolOptions
  )
}

object ElaborateProductFpgaSoC extends App {
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(),
    args,
    ProductSoCElaboration.firtoolOptions
  )
}

/** Exact same FPGA top/PA32 interface for reproducible Small-vs-Expanded PPA.
  * Selection changes physical storage depth, never decode/issue/retire width.
  */
object ElaborateProductFpgaSoCGeometry extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "Product FPGA PPA needs small or expanded capacity", "Product FPGA PPA")
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(implementedPaddrBits = 32, productGeometry = geometry),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** Opt-in RV64GC/LP64D FPGA emission through the same selected CPU target.
  *
  * The established IMAC engineering entrypoint above remains unchanged. This
  * emits a candidate full SoC netlist for qualification, not a DDR/Fmax or
  * Debian boot pass.
  */
object ElaborateProductFpgaSoCRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC FPGA emission needs small or expanded capacity", "RV64GC FPGA")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaSoC(implementedPaddrBits = 32,
      productConfigOverride = Some(target)),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** The physical pin-only shell is deliberately separate from the fully
  * instrumented FPGA verification top. Both instantiate the identical CPU,
  * SoC, target configuration and board-neutral PA32 AXI interface.
  */
object ElaborateProductFpgaPinsGeometry extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "Product FPGA pin PPA needs small or expanded capacity", "Product FPGA pin")
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaPins(implementedPaddrBits = 32,
      productGeometry = geometry),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

object ElaborateProductFpgaPinsRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC FPGA pin emission needs small or expanded capacity", "RV64GC FPGA pin")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaPins(implementedPaddrBits = 32,
      productConfigOverride = Some(target)),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** Board-neutral cold-start top. The board must connect real PLL/DDR ready,
  * external image loader and exclusive DDR AXI mux to its handshake pins.
  * This emission never claims to instantiate those board-specific elements.
  */
object ElaborateProductFpgaColdBootPinsRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC cold-boot FPGA pins need small or expanded capacity", "RV64GC cold-boot")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaColdBootPins(implementedPaddrBits = 32,
      productConfigOverride = Some(target)),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** Opt-in Product RV64GC FPGA with a real SPI/MMC PIO pin and MMIO path.
  * The same SPI-SD target must be selected when emitting the corresponding
  * device tree (`EmitAetherSoCDts --product --spi-sd`). The physical board must still
  * provide DDR, PLL, firmware loader, 3.3V SD wiring and timing constraints.
  */
object ElaborateProductFpgaColdBootSpiSdPinsRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC SPI-SD FPGA pins need small or expanded capacity", "RV64GC SPI-SD")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaColdBootPins(implementedPaddrBits = 32,
      productConfigOverride = Some(target),
      boardDevices = ProductSoCBoardProfiles.persistentSpiSd),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** Product RV64GC board-neutral G5-SD1 integration shell.
  *
  * Unlike ColdBootPins this includes the five-channel DDR owner mux and an
  * independent accepted-transaction drain ledger. The external board still
  * supplies the image loader AXI master, DDR PHY, clock/reset crossings and
  * physical SD pads. Linux rootfs is expected to reside on external SD; only
  * OpenSBI, the kernel and DTB need initial DDR image loading.
  */
object ElaborateProductFpgaColdBootDdrMuxSpiSd1PinsRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC G5-SD1 DDR mux FPGA pins need small or expanded capacity", "RV64GC G5-SD1")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaColdBootDdrFabric(implementedPaddrBits = 32,
      productConfigOverride = Some(target),
      boardDevices = ProductSoCBoardProfiles.persistentSpiSd),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** G5-SD1 Product FPGA image-stream cold boot boundary.
  *
  * The board must provide the firmware and standalone DTB as two decoupled
  * byte streams. A built-in AXI write engine owns external calibrated DDR
  * until both segments have received their final successful B responses.
  * The board still owns SD/flash transport, DDR controller/PHY and clocks.
  */
object ElaborateProductFpgaColdBootDdrStreamSpiSd1PinsRv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC G5-SD1 DDR-stream FPGA pins need small or expanded capacity", "RV64GC G5-SD1 stream")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaColdBootDdrStreamFabric(implementedPaddrBits = 32,
      productConfigOverride = Some(target),
      boardDevices = ProductSoCBoardProfiles.persistentSpiSd),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** G5-SD1 native DDR-controller aperture: real FW/DTB stream in, zero-based
  * DDR AXI out, with synthesized UART/timebase clock-enables and SPI-SD pads.
  * The physical board must provide its DDR IP, clocks, IO constraints and
  * external image source; this emission never counts as a Linux boot test.
  */
object ElaborateProductFpgaDdrLocalWindowSpiSd1Rv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC local-DDR FPGA emission needs small or expanded capacity", "RV64GC local-DDR")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaDdrLocalWindowBoard(implementedPaddrBits = 32,
      productConfigOverride = Some(target),
      boardDevices = ProductSoCBoardProfiles.persistentSpiSd),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}

/** G5-SD1 board-neutral UART firmware upload -> DDR -> native Debian on SD.
  * This is the first Product FPGA emission with a physical byte source in
  * the same selected SmallOoO SoC; vendor DDR/PLL/pinout is still required.
  */
object ElaborateProductFpgaUartDdrSpiSd1Rv64gc extends App {
  val geometry = ProductSoCElaboration.geometry(args,
    "RV64GC UART-to-DDR FPGA emission needs small or expanded capacity", "RV64GC UART-to-DDR")
  val target = ProductSoCElaboration.rv64gcTarget(geometry)
  ChiselStage.emitSystemVerilogFile(
    new AetherCoreProductFpgaUartDdrBoard(implementedPaddrBits = 32,
      productConfigOverride = Some(target),
      boardDevices = ProductSoCBoardProfiles.persistentSpiSd),
    args.tail,
    ProductSoCElaboration.firtoolOptions
  )
}
