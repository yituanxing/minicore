package aethercore.soc

/** Board devices form a configuration axis independent of the CPU ISA and
  * Small/Expanded physical geometry. This selects concrete device RTL and
  * its physical pin seam; it never changes the instruction-set profile.
  *
  * A physical board must supply the declared CPU clock, DDR/PLL and SD pads.
  * This record does not pretend to instantiate a DDR PHY or SD card model.
  */
final case class ProductSoCBoardConfig(
    spiSdEnabled: Boolean,
    spiBusClockFrequencyHz: Long = 50_000_000L,
    spiMaxFrequencyHz: Long = 12_500_000L
) {
  require(spiBusClockFrequencyHz > 0)
  require(spiMaxFrequencyHz > 0)
  require(spiMaxFrequencyHz <= spiBusClockFrequencyHz / 2)
}

object ProductSoCBoardProfiles {
  /** Frozen engineering and G5 D1 initial-RAM paths have no external SD. */
  val existingRamOnly: ProductSoCBoardConfig =
    ProductSoCBoardConfig(spiSdEnabled = false)

  /** D3 persistent-rootfs hardware, without asserting physical board signoff. */
  val persistentSpiSd: ProductSoCBoardConfig =
    ProductSoCBoardConfig(spiSdEnabled = true)
}
