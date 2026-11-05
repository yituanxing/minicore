package aethercore

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import aethercore.config.CoreProfiles
import aethercore.soc.{AetherSoCBoardSpec, AetherSoCDts, ProductSoCBoardProfiles}

/**
  * Emit the qualified RV64 AetherSoC device tree from the same Scala board
  * specification used to construct RTL.
  *
  * Usage:
  *   EmitAetherSoCDts --output path/to/aethersoc.dts
  *                    [--isa rv64ima_zicsr_zifencei]
  *                    [--mmu sv39]
  *                    [--bootargs "..."]
  *                    [--spi-sd] [--spi-clock-hz 50000000]
  *                    [--spi-max-hz 12500000]
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
  private val spiSd = args.contains("--spi-sd")
  private val spiClockHz = option("--spi-clock-hz").map(_.toLong)
  private val spiMaxHz = option("--spi-max-hz").map(_.toLong)

  private val platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
  private val selectedDevices = if (spiSd) ProductSoCBoardProfiles.persistentSpiSd
    else ProductSoCBoardProfiles.existingRamOnly
  private val boardDevices = selectedDevices.copy(
    spiBusClockFrequencyHz = spiClockHz.getOrElse(selectedDevices.spiBusClockFrequencyHz),
    spiMaxFrequencyHz = spiMaxHz.getOrElse(selectedDevices.spiMaxFrequencyHz)
  )
  private val originalBoard = AetherSoCBoardSpec.qualifiedLinux(platform)
  private val board = originalBoard.copy(
    spiBusClockFrequencyHz = boardDevices.spiBusClockFrequencyHz,
    spiMaxFrequencyHz = boardDevices.spiMaxFrequencyHz
  )
  private val text = AetherSoCDts.render(board, isa, mmu, bootargs,
    enableSpiSd = boardDevices.spiSdEnabled)

  private val path = Paths.get(output)
  Option(path.getParent).foreach(parent => Files.createDirectories(parent))
  Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  println(s"AETHERSOC_DTS_RESULT: status=PASS")
  println(s"output=${path.toAbsolutePath}")
  println(s"bytes=${text.getBytes(StandardCharsets.UTF_8).length}")
  println(s"reset=0x${board.addressMap.bootRomBase.toString(16)}")
  println(s"ram=0x${board.addressMap.ramBase.toString(16)}+0x${board.addressMap.ramBytes.toString(16)}")
  println(s"plic=0x${board.addressMap.plicBase.toString(16)}+0x${board.addressMap.plicBytes.toString(16)}")
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
