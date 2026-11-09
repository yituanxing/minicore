package aethercore.soc

/** SoC-level cache sizing, independent of CPU ISA/geometry and board pins.
  *
  * P0 remains 64 x 8B (512B) per I/D cache. These strictly opt-in sizes
  * preserve the existing single 8B beat, byte-valid/PMP boundary semantics,
  * and same-cycle asynchronous read design. Larger values are experiments,
  * NOT claims of BRAM inference, routed timing, or Linux improvement.
  */
final case class ProductSoCCacheProfile(
    instructionEntries: Int = 64,
    dataEntries: Int = 64
) {
  private val reviewedSizes = Set(64, 256, 512)
  require(reviewedSizes.contains(instructionEntries),
    "Product I-cache A/B entries must be 64, 256, or 512")
  require(reviewedSizes.contains(dataEntries),
    "Product D-cache A/B entries must be 64, 256, or 512")
}

object ProductSoCCacheProfiles {
  val productionP0: ProductSoCCacheProfile = ProductSoCCacheProfile()
}
