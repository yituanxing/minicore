package aethercore.soc

import aethercore.config.PlatformConfig

/**
  * Software-visible AetherSoC physical address map.
  *
  * Keeping this as a pure Scala value makes it usable by RTL construction,
  * DTS generation and FPGA wrappers without duplicating magic constants.
  */
final case class AetherSoCAddressMap(
    bootRomBase: BigInt,
    bootRomBytes: BigInt,
    ramBase: BigInt,
    ramBytes: BigInt,
    uartBase: BigInt,
    uartBytes: BigInt,
    exitAddress: BigInt,
    mtimeAddress: BigInt,
    mtimecmpAddress: BigInt,
    plicBase: BigInt,
    plicBytes: BigInt
) {
  require(bootRomBytes > 0)
  require(ramBytes > 0)
  require(uartBytes > 0)
  require(plicBytes > 0)

  val bootRomLimit: BigInt = bootRomBase + bootRomBytes
  val ramLimit: BigInt = ramBase + ramBytes
  val uartLimit: BigInt = uartBase + uartBytes
  val plicLimit: BigInt = plicBase + plicBytes
}

object AetherSoCAddressMap {
  val QualifiedBootRomBase: BigInt = BigInt("00001000", 16)
  val QualifiedBootRomBytes: BigInt = BigInt("00001000", 16)

  /**
    * Frozen software-visible map used by the qualified RV64 OpenSBI/Linux path.
    * PlatformConfig remains the source of the legacy UART/exit/MTIMER addresses
    * until the CoreConfig/SoCConfig split is completed.
    */
  def qualifiedLinux(platform: PlatformConfig): AetherSoCAddressMap =
    AetherSoCAddressMap(
      bootRomBase = QualifiedBootRomBase,
      bootRomBytes = QualifiedBootRomBytes,
      ramBase = BigInt("80000000", 16),
      ramBytes = BigInt("10000000", 16),
      uartBase = platform.uartAddress,
      uartBytes = 8,
      exitAddress = platform.exitAddress,
      mtimeAddress = platform.mtimeAddress,
      mtimecmpAddress = platform.mtimecmpAddress,
      plicBase = BigInt("0c000000", 16),
      plicBytes = BigInt("00400000", 16)
    )
}


/**
  * Software-visible board topology shared by RTL construction and device-tree
  * generation. Values in this case class are not simulation metadata: they are
  * the hardware/software contract for the qualified single-hart AetherSoC v0.
  */
final case class AetherSoCBoardSpec(
    addressMap: AetherSoCAddressMap,
    plicSourceCount: Int,
    uartPlicSourceId: Int,
    supervisorExternalInterruptId: Int,
    machineTimerInterruptId: Int,
    timebaseFrequencyHz: Long,
    uartClockFrequencyHz: Long,
    uartBaud: Int
) {
  require(plicSourceCount > 0)
  require(uartPlicSourceId > 0 && uartPlicSourceId <= plicSourceCount)
  require(supervisorExternalInterruptId > 0)
  require(machineTimerInterruptId > 0)
  require(timebaseFrequencyHz > 0)
  require(uartClockFrequencyHz > 0)
  require(uartBaud > 0)

  private val uartDivisorDenominator = 16L * uartBaud.toLong
  require(
    uartClockFrequencyHz % uartDivisorDenominator == 0,
    "AetherSoC v0 UART clock must produce an exact ns16550 divisor"
  )
  val uartDefaultDivisor: Int =
    (uartClockFrequencyHz / uartDivisorDenominator).toInt
  require(
    uartDefaultDivisor >= 1 && uartDefaultDivisor <= 0xffff,
    "AetherSoC v0 UART divisor must fit the ns16550 DLL/DLM pair"
  )
}

object AetherSoCBoardSpec {
  // The qualified FPGA product implements a 32-bit physical address seam.
  // Sv39 remains architecturally PA56 inside the translation machinery; any
  // architectural PA outside this implemented window fails closed before it
  // can reach the board-facing memory fabric.
  val FpgaImplementedPaddrBits: Int = 32
  val QualifiedPlicSourceCount: Int = 16

  def qualifiedLinux(platform: PlatformConfig): AetherSoCBoardSpec =
    AetherSoCBoardSpec(
      addressMap = AetherSoCAddressMap.qualifiedLinux(platform),
      plicSourceCount = QualifiedPlicSourceCount,
      uartPlicSourceId = 10,
      supervisorExternalInterruptId = 9,
      machineTimerInterruptId = 7,
      timebaseFrequencyHz = 10_000_000L,
      uartClockFrequencyHz = 3_686_400L,
      uartBaud = 115_200
    )
}
