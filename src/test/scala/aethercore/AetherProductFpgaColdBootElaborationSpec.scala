package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.ProductCoreProfiles
import aethercore.soc.{AetherCoreProductFpgaColdBootPins, AetherCoreProductSoCProfiles}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AetherProductFpgaColdBootElaborationSpec extends AnyFlatSpec with Matchers {
  behavior of "RV64GC Product FPGA cold boot physical boundary"

  it should "elaborate the real selected Product CPU behind DDR ownership and AXI isolation" in {
    val target = AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(
      32, ProductCoreProfiles.smallGeometry)
    val rtl = ChiselStage.emitCHIRRTL(new AetherCoreProductFpgaColdBootPins(
      implementedPaddrBits = 32,
      productConfigOverride = Some(target)))
    rtl should include("AetherCoreProductFpgaColdBootPins")
    rtl should include("AetherProductColdBootControl")
    rtl should include("AetherProductAxiColdBootIsolation")
    rtl should include("AetherCoreProductFpgaPins")
    rtl should include("ProductSmallPagedCore")
    rtl should include("ProductFpArithmetic")
    rtl should include("loaderOwnsDdr")
    rtl should include("cpuReleased")
    rtl should include("imageLoadFailed")
  }
}
