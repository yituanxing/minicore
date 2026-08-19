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

  val pageTableGeometries: Set[PageTableGeometry] =
    PageTableGeometry.validateArchitecturalModes(xlen, privilegeModes, virtualMemoryModes)

  require(!sstc || privilegeModes.contains('S'), "Sstc requires Supervisor mode")
  require(!sstc || xlen == 32, "the current bounded Sstc implementation is RV32-only")
  require(
    Set(0, 16, 64).contains(pmpEntries),
    s"standard PMP implementation count must be 0, 16 or 64, got $pmpEntries"
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
  val hasPagedVirtualMemory: Boolean = pageTableGeometries.nonEmpty
  val hasSv32: Boolean = virtualMemoryModes.contains("Sv32")
  val hasSv39: Boolean = virtualMemoryModes.contains("Sv39")
  val hasSv48: Boolean = virtualMemoryModes.contains("Sv48")
  val hasSstc: Boolean = sstc
  val hasWordOps: Boolean = xlen == 64
  val hasPmp: Boolean = pmpEntries > 0

  /** Deterministic satp mode order for hardware construction. */
  val orderedPageTableGeometries: Seq[PageTableGeometry] =
    pageTableGeometries.toSeq.sortBy(_.satpMode)

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
  val instructionExtensions: Set[Char] = Set('I', 'M', 'A', 'C')
  val zExtensions: Set[String] = Set("Zicsr", "Zifencei")
  val privilegeModes: Set[Char] = Set('M', 'S', 'U')
  val virtualMemoryModes: Set[String] = Set("Sv32", "Sv39")
}

final case class CoreConfig(
    name: String,
    isa: IsaConfig,
    platform: PlatformConfig
) {
  private val architecturalPmpPhysicalBits = if (isa.xlen == 32) 34 else 56

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
  require(
    isa.virtualMemoryModes.subsetOf(AetherCoreCapabilities.virtualMemoryModes),
    s"virtual-memory modes ${isa.virtualMemoryModes} are not integrated into the production AetherCore"
  )
  require(
    isa.pageTableGeometries.size <= 1,
    "the current production translation datapath supports one active page-table geometry per core profile"
  )
  require(!isa.hasA || isa.xlen == 32, "the current atomic execution path implements RV32A word operations only")
  require(!isa.hasC || isa.xlen == 32, "the current compressed frontend implements RV32C only")
  isa.pageTableGeometries.foreach { geometry =>
    require(
      platform.paddrBits >= geometry.architecturalPhysicalAddressBits,
      s"${geometry.name} requires at least ${geometry.architecturalPhysicalAddressBits} physical address bits, got ${platform.paddrBits}"
    )
  }
  require(
    !isa.hasPmp || isa.pmpEntries == 16,
    "the current AetherCore PMP implementation exposes the bounded standard PMP16 surface"
  )
  require(
    !isa.hasPmp || platform.paddrBits <= architecturalPmpPhysicalBits,
    s"RV${isa.xlen} PMP can protect at most $architecturalPmpPhysicalBits physical address bits, got ${platform.paddrBits}"
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

  private val rv64Platform: PlatformConfig = PlatformConfig(
    resetVector = BigInt("80000000", 16),
    paddrBits = 64,
    busDataBits = 64,
    uartAddress = BigInt("10000000", 16),
    exitAddress = BigInt("10000008", 16),
    mtimeAddress = mtimeAddress,
    mtimecmpAddress = mtimecmpAddress
  )

  // RV64 PMP and Sv39 both terminate in the architectural PA56 domain.
  private val rv64PmpPlatform: PlatformConfig = rv64Platform.copy(paddrBits = 56)

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

  val rv32imcSoftware: CoreConfig = CoreConfig(
    name = "rv32imc-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'C'),
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

  val rv32imasuSv32PmpSoftware: CoreConfig = CoreConfig(
    name = "rv32imasu-sv32-pmp-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr", "Zifencei"),
      virtualMemoryModes = Set("Sv32"),
      pmpEntries = 16,
      sstc = true
    ),
    platform = rv32Sv32Platform
  )

  val rv32imacsuSv32PmpSoftware: CoreConfig = CoreConfig(
    name = "rv32imacsu-sv32-pmp-software",
    isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M', 'A', 'C'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr", "Zifencei"),
      virtualMemoryModes = Set("Sv32"),
      pmpEntries = 16,
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
      pmpEntries = 16
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
      pmpEntries = 16
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
      pmpEntries = 16
    ),
    platform = rv32Platform
  )

  /** First bounded RV64 privileged software profile: bare M/S/U, no later system facilities. */
  val rv64imsuSoftware: CoreConfig = CoreConfig(
    name = "rv64imsu-software",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr")
    ),
    platform = rv64Platform
  )

  /** RV64 PMP V1: same M/S/U execution profile with an independently bounded PA56 protected domain. */
  val rv64imsuPmpSoftware: CoreConfig = CoreConfig(
    name = "rv64imsu-pmp-software",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      pmpEntries = 16
    ),
    platform = rv64PmpPlatform
  )

  /** First production RV64 paged profile: Sv39 + PMP16 over the shared PA56 domain. */
  val rv64imsuSv39PmpSoftware: CoreConfig = CoreConfig(
    name = "rv64imsu-sv39-pmp-software",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv39"),
      pmpEntries = 16
    ),
    platform = rv64PmpPlatform
  )

  val rv64imCurrent: CoreConfig = CoreConfig(
    name = "rv64im-current",
    isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M'),
      zExtensions = Set("Zicsr", "Zifencei")
    ),
    platform = rv64Platform
  )
}
