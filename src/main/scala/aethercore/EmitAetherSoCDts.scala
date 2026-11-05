package aethercore

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import aethercore.config.CoreProfiles
import aethercore.soc.{AetherCoreProductSoCProfiles, AetherSoCBoardSpec, AetherSoCDts, ProductSoCBoardProfiles}

/** Pure CLI board-profile selection, also exercised without running the emitter.
  * An SD peripheral cannot be attached to the older V2 board's 16-source PLIC.
  */
private[aethercore] object AetherSoCDtsBoardSelection {
  def select(
      productBoard: Boolean,
      spiSd: Boolean,
      spiClockHz: Option[Long] = None,
      spiMaxHz: Option[Long] = None
  ): AetherSoCBoardSpec = {
    require(!spiSd || productBoard,
      "--spi-sd requires --product (Product SPI-SD uses the 52-source PLIC)")
    require(spiSd || (spiClockHz.isEmpty && spiMaxHz.isEmpty),
      "--spi-clock-hz and --spi-max-hz require --spi-sd")

    val selectedDevices = if (spiSd) ProductSoCBoardProfiles.persistentSpiSd
      else ProductSoCBoardProfiles.existingRamOnly
    val boardDevices = selectedDevices.copy(
      spiBusClockFrequencyHz = spiClockHz.getOrElse(selectedDevices.spiBusClockFrequencyHz),
      spiMaxFrequencyHz = spiMaxHz.getOrElse(selectedDevices.spiMaxFrequencyHz)
    )
    val platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
    val originalBoard = AetherSoCBoardSpec.qualifiedLinux(platform)
    originalBoard.copy(
      // V2's frozen legacy board has only 16 sources; the Product RTL has 52.
      plicSourceCount = if (productBoard)
        AetherCoreProductSoCProfiles.OpenSbiPlicSourceCount
      else originalBoard.plicSourceCount,
      spiBusClockFrequencyHz = boardDevices.spiBusClockFrequencyHz,
      spiMaxFrequencyHz = boardDevices.spiMaxFrequencyHz
    )
  }
}

/**
  * Emit the qualified RV64 AetherSoC device tree from the same Scala board
  * specification used to construct RTL.
  *
  * Usage:
  *   EmitAetherSoCDts --output path/to/aethersoc.dts
  *                    [--isa rv64ima_zicsr_zifencei]
  *                    [--mmu sv39]
  *                    [--bootargs "..."]
  *                    [--product] (Product SmallOoO PLIC topology)
  *                    [--spi-sd] (requires --product)
  *                    [--spi-clock-hz 50000000] (requires --spi-sd)
  *                    [--spi-max-hz 12500000] (requires --spi-sd)
  */
object EmitAetherSoCDts extends App {
  private def option(name: String): Option[String] = {
    val index = args.indexOf(name)
    if (index < 0) None
    else {
      require(index + 1 < args.length, s"missing value for $name")
      Some(args(index + 1))
    }
  }

  private val output = option("--output").getOrElse(
    throw new IllegalArgumentException("--output is required")
  )
  private val isa = option("--isa").getOrElse("rv64ima_zicsr_zifencei")
  private val mmu = option("--mmu").getOrElse("sv39")
  private val bootargs = option("--bootargs")
  private val productBoard = args.contains("--product")
  private val spiSd = args.contains("--spi-sd")
  private val spiClockHz = option("--spi-clock-hz").map(_.toLong)
  private val spiMaxHz = option("--spi-max-hz").map(_.toLong)

  private val board = AetherSoCDtsBoardSelection.select(
    productBoard, spiSd, spiClockHz, spiMaxHz)
  private val text = AetherSoCDts.render(board, isa, mmu, bootargs,
    enableSpiSd = spiSd)

  private val path = Paths.get(output)
  Option(path.getParent).foreach(parent => Files.createDirectories(parent))
  Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  println(s"AETHERSOC_DTS_RESULT: status=PASS")
  println(s"output=${path.toAbsolutePath}")
  println(s"bytes=${text.getBytes(StandardCharsets.UTF_8).length}")
  println(s"reset=0x${board.addressMap.bootRomBase.toString(16)}")
  println(s"ram=0x${board.addressMap.ramBase.toString(16)}+0x${board.addressMap.ramBytes.toString(16)}")
  println(s"plic=0x${board.addressMap.plicBase.toString(16)}+0x${board.addressMap.plicBytes.toString(16)}")
  println(s"plic-sources=${board.plicSourceCount}")
  println(s"board-profile=${if (productBoard) "product" else "legacy"}")
  println(s"uart=0x${board.addressMap.uartBase.toString(16)}")
  println(s"mtime=0x${board.addressMap.mtimeAddress.toString(16)}")
  println(s"mtimecmp=0x${board.addressMap.mtimecmpAddress.toString(16)}")
  if (spiSd) {
    println(s"spi=0x${board.addressMap.spiBase.toString(16)}")
    println(s"spi-cs-gpio=0x${board.addressMap.spiCsGpioBase.toString(16)}")
    println(s"spi-plic-irq=${board.spiPlicSourceId}")
    println(s"spi-clock-hz=${board.spiBusClockFrequencyHz}")
  }
}
