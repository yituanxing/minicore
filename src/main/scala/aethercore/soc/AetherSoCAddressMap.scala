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
  /**
    * Frozen software-visible map used by the qualified RV64 OpenSBI/Linux path.
    * PlatformConfig remains the source of the legacy UART/exit/MTIMER addresses
    * until the CoreConfig/SoCConfig split is completed.
    */
  def qualifiedLinux(platform: PlatformConfig): AetherSoCAddressMap =
    AetherSoCAddressMap(
      bootRomBase = BigInt("00001000", 16),
      bootRomBytes = BigInt("00001000", 16),
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
