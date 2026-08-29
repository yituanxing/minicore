package aethercore

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import aethercore.config.CoreProfiles
import aethercore.soc.{AetherSoCBoardSpec, AetherSoCDts}

/**
  * Emit the qualified RV64 AetherSoC device tree from the same Scala board
  * specification used to construct RTL.
  *
  * Usage:
  *   EmitAetherSoCDts --output path/to/aethersoc.dts
  *                    [--isa rv64ima_zicsr_zifencei]
  *                    [--mmu sv39]
  *                    [--bootargs "..."]
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

  private val platform = CoreProfiles.rv64imasuSv39PmpSoftware.platform
  private val board = AetherSoCBoardSpec.qualifiedLinux(platform)
  private val text = AetherSoCDts.render(board, isa, mmu, bootargs)

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
}
