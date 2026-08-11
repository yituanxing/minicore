package aethercore.config

import chisel3.util.log2Ceil

final case class IsaConfig(
    xlen: Int,
    extensions: Set[Char],
    privilegeModes: Set[Char],
    zExtensions: Set[String] = Set.empty,
    virtualMemoryModes: Set[String] = Set.empty,
    pmpEntries: Int = 0,
    sstc: Boolean = false
) {
  require(xlen == 32 || xlen == 64, s"XLEN must be 32 or 64, got $xlen")
  require(extensions.contains('I'), "the base I extension is required")
  require(privilegeModes.contains('M'), "machine mode is required")
  require(
    zExtensions.forall(name => name.startsWith("Z") && name.length > 1),
    s"multi-letter extensions must use canonical Z-prefixed names: $zExtensions"
  )
  require(
    virtualMemoryModes.subsetOf(Set("Sv32")),
    s"unsupported virtual-memory mode set: $virtualMemoryModes"
  )
  require(
    !virtualMemoryModes.contains("Sv32") || (xlen == 32 && privilegeModes.contains('S')),
    "Sv32 requires RV32 with Supervisor mode"
  )
  require(
    !sstc || privilegeModes.contains('S'),
    "Sstc requires Supervisor mode"
  )
  require(
    !sstc || xlen == 32,
    "the current bounded Sstc implementation is RV32-only"
  )
  require(pmpEntries >= 0 && pmpEntries <= 4, s"this core supports 0..4 PMP entries, got $pmpEntries")
  require(pmpEntries == 0 || xlen == 32, "the current four-entry pmpcfg0 packing is RV32-only")
  require(
    !virtualMemoryModes.contains("Sv32") || pmpEntries == 0,
    "Sv32+PMP is deferred until PMP checks consume the full translated PA34"
  )

  val xBytes: Int = xlen / 8
  val shiftBits: Int = log2Ceil(xlen)
  val hasM: Boolean = extensions.contains('M')
  val hasA: Boolean = extensions.contains('A')
  val hasC: Boolean = extensions.contains('C')
  val hasZicsr: Boolean = zExtensions.contains("Zicsr")
  val hasZifencei: Boolean = zExtensions.contains("Zifencei")
  val hasS: Boolean = privilegeModes.contains('S')
  val hasU: Boolean = privilegeModes.contains('U')
  val hasSv32: Boolean = virtualMemoryModes.contains("Sv32")
  val hasSstc: Boolean = sstc
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

object AetherCoreCapabilities {
  val instructionExtensions: Set[Char] = Set('I', 'M', 'A')
  val zExtensions: Set[String] = Set("Zicsr", "Zifencei")
  val privilegeModes: Set[Char] = Set('M', 'S', 'U')
}

final case class CoreConfig(
    name: String,
    isa: IsaConfig,
    platform: PlatformConfig
) {
  require(name.nonEmpty, "core profile name must not be empty")
  require(
    isa.extensions.subsetOf(AetherCoreCapabilities.instructionExtensions),
    s"unsupported AetherCore instruction extension set: ${isa.extensions}"
  )
  require(
    isa.zExtensions.subsetOf(AetherCoreCapabilities.zExtensions),
    s"unsupported AetherCore Z-extension set: ${isa.zExtensions}"
  )
  require(
    isa.privilegeModes.subsetOf(AetherCoreCapabilities.privilegeModes),
    s"unsupported AetherCore privilege-mode set: ${isa.privilegeModes}"
  )
  require(!isa.hasA || isa.xlen == 32, "the current atomic execution path implements RV32A word operations only")
  require(
    !isa.hasSv32 || platform.paddrBits >= 34,
    s"Sv32 requires at least 34 physical address bits, got ${platform.paddrBits}"
  )
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

  private val rv32Sv32Platform: PlatformConfig = rv32Platform.copy(paddrBits = 34)

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

  val rv32imsuSoftware: CoreConfig = CoreConfig(
    name = "rv32imsu-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr")
    ),
    platform = rv32Platform
  )

  val rv32imsuSv32Software: CoreConfig = CoreConfig(
    name = "rv32imsu-sv32-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv32")
    ),
    platform = rv32Sv32Platform
  )

  // N5 real-paging profile: same frozen Sv32/MMU contract, with the RV32A
  // atomics and Sstc timer facility emitted by the pinned NuttX workload.
  val rv32imasuSv32Software: CoreConfig = CoreConfig(
    name = "rv32imasu-sv32-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr", "Zifencei"),
      virtualMemoryModes = Set("Sv32"),
      sstc = true
    ),
    platform = rv32Sv32Platform
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

  val rv32imuPmpOsSoftware: CoreConfig = CoreConfig(
    name = "rv32imu-pmp-os-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr", "Zifencei"),
      pmpEntries = 4
    ),
    platform = rv32Platform
  )

  val rv32imauPmpOsSoftware: CoreConfig = CoreConfig(
    name = "rv32imau-pmp-os-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr", "Zifencei"),
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
