package aethercore.config

import chisel3.util.log2Ceil

final case class IsaConfig(
    xlen: Int,
    extensions: Set[Char],
    privilegeModes: Set[Char],
    zExtensions: Set[String] = Set.empty,
    pmpEntries: Int = 0
) {
  require(xlen == 32 || xlen == 64, s"XLEN must be 32 or 64, got $xlen")
  require(extensions.contains('I'), "the base I extension is required")
  require(privilegeModes.contains('M'), "machine mode is required")
  require(
    zExtensions.forall(name => name.startsWith("Z") && name.length > 1),
    s"multi-letter extensions must use canonical Z-prefixed names: $zExtensions"
  )
  require(pmpEntries >= 0 && pmpEntries <= 4, s"this core supports 0..4 PMP entries, got $pmpEntries")
  require(pmpEntries == 0 || xlen == 32, "the current four-entry pmpcfg0 packing is RV32-only")

  val xBytes: Int = xlen / 8
  val shiftBits: Int = log2Ceil(xlen)
  val hasM: Boolean = extensions.contains('M')
  val hasA: Boolean = extensions.contains('A')
  val hasC: Boolean = extensions.contains('C')
  val hasZicsr: Boolean = zExtensions.contains("Zicsr")
  val hasS: Boolean = privilegeModes.contains('S')
  val hasU: Boolean = privilegeModes.contains('U')
  val hasWordOps: Boolean = xlen == 64
  val hasPmp: Boolean = pmpEntries > 0

  val march: String = {
    val ordered = Seq('I', 'M', 'A', 'F', 'D', 'C')
    val baseSuffix = ordered.filter(extensions.contains).map(_.toLower).mkString
    val multiLetterSuffix = zExtensions.toSeq.sorted.map(_.toLowerCase).mkString("_")
    if (multiLetterSuffix.isEmpty) s"rv$xlen$baseSuffix"
    else s"rv$xlen${baseSuffix}_$multiLetterSuffix"
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
    exitAddress: BigInt,
    mtimeAddress: BigInt,
    mtimecmpAddress: BigInt
) {
  require(paddrBits > 0 && paddrBits <= 64, s"physical address width must be 1..64, got $paddrBits")
  require(Set(32, 64).contains(busDataBits), s"busDataBits must be 32 or 64, got $busDataBits")
  require(resetVector >= 0 && resetVector.bitLength <= paddrBits, "reset vector exceeds physical address width")
  require(uartAddress >= 0 && uartAddress.bitLength <= paddrBits, "UART address exceeds physical address width")
  require(exitAddress >= 0 && exitAddress.bitLength <= paddrBits, "exit address exceeds physical address width")
  require(mtimeAddress >= 0 && mtimeAddress.bitLength <= paddrBits, "mtime address exceeds physical address width")
  require(mtimecmpAddress >= 0 && mtimecmpAddress.bitLength <= paddrBits, "mtimecmp address exceeds physical address width")
  require((mtimeAddress & 7) == 0, "mtime must be 64-bit aligned")
  require((mtimecmpAddress & 7) == 0, "mtimecmp must be 64-bit aligned")

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
  private val mtimeAddress = BigInt("0200bff8", 16)
  private val mtimecmpAddress = BigInt("02004000", 16)

  private val rv32Platform: PlatformConfig = PlatformConfig(
    resetVector = BigInt("80000000", 16),
    paddrBits = 32,
    busDataBits = 32,
    uartAddress = BigInt("10000000", 16),
    exitAddress = BigInt("10000008", 16),
    mtimeAddress = mtimeAddress,
    mtimecmpAddress = mtimecmpAddress
  )

  val rv32iMinimal: CoreConfig = CoreConfig(
    name = "rv32i-minimal",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I'),
      privilegeModes = Set('M')
    ),
    platform = rv32Platform
  )

  val rv32imSoftware: CoreConfig = CoreConfig(
    name = "rv32im-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M'),
      zExtensions = Set("Zicsr")
    ),
    platform = rv32Platform
  )

  val rv32imuSoftware: CoreConfig = CoreConfig(
    name = "rv32imu-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr")
    ),
    platform = rv32Platform
  )

  val rv32imuPmpSoftware: CoreConfig = CoreConfig(
    name = "rv32imu-pmp-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr"),
      pmpEntries = 4
    ),
    platform = rv32Platform
  )

  val rv64imCurrent: CoreConfig = CoreConfig(
    name = "rv64im-current",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M'),
      zExtensions = Set("Zicsr")
    ),
    platform = PlatformConfig(
      resetVector = BigInt("80000000", 16),
      paddrBits = 64,
      busDataBits = 64,
      uartAddress = BigInt("10000000", 16),
      exitAddress = BigInt("10000008", 16),
      mtimeAddress = mtimeAddress,
      mtimecmpAddress = mtimecmpAddress
    )
  )
}
