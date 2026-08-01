package aethercore.config

import chisel3.util.log2Ceil

final case class IsaConfig(
    xlen: Int,
    extensions: Set[Char],
    privilegeModes: Set[Char]
) {
  require(xlen == 32 || xlen == 64, s"XLEN must be 32 or 64, got $xlen")
  require(extensions.contains('I'), "the base I extension is required")
  require(privilegeModes.contains('M'), "machine mode is required")

  val xBytes: Int = xlen / 8
  val shiftBits: Int = log2Ceil(xlen)
  val hasM: Boolean = extensions.contains('M')
  val hasA: Boolean = extensions.contains('A')
  val hasC: Boolean = extensions.contains('C')
  val hasS: Boolean = privilegeModes.contains('S')
  val hasU: Boolean = privilegeModes.contains('U')
  val hasWordOps: Boolean = xlen == 64

  val march: String = {
    val ordered = Seq('I', 'M', 'A', 'F', 'D', 'C')
    val suffix = ordered.filter(extensions.contains).map(_.toLower).mkString
    s"rv$xlen$suffix"
  }

  val mabi: String = xlen match {
    case 32 => "ilp32"
    case 64 => "lp64"
  }
}

final case class PlatformConfig(
    resetVector: BigInt,
    paddrBits: Int,
    busDataBits: Int,
    uartAddress: BigInt,
    exitAddress: BigInt
) {
  require(paddrBits > 0 && paddrBits <= 64, s"physical address width must be 1..64, got $paddrBits")
  require(Set(32, 64).contains(busDataBits), s"busDataBits must be 32 or 64, got $busDataBits")
  require(resetVector >= 0 && resetVector.bitLength <= paddrBits, "reset vector exceeds physical address width")
  require(uartAddress >= 0 && uartAddress.bitLength <= paddrBits, "UART address exceeds physical address width")
  require(exitAddress >= 0 && exitAddress.bitLength <= paddrBits, "exit address exceeds physical address width")

  val busBytes: Int = busDataBits / 8
}

final case class CoreConfig(
    name: String,
    isa: IsaConfig,
    platform: PlatformConfig
) {
  require(name.nonEmpty, "core profile name must not be empty")
}

object CoreProfiles {
  val rv64imCurrent: CoreConfig = CoreConfig(
    name = "rv64im-current",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M')
    ),
    platform = PlatformConfig(
      resetVector = BigInt("80000000", 16),
      paddrBits = 64,
      busDataBits = 64,
      uartAddress = BigInt("10000000", 16),
      exitAddress = BigInt("10000008", 16)
    )
  )
}
