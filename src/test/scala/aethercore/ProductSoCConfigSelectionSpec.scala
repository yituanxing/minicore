package aethercore

import aethercore.core.product.ProductCoreProfiles
import aethercore.soc.AetherCoreProductSoCProfiles
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Contract for the one Product target selected at a SoC board boundary.
  * The old geometry argument stays source-compatible, but may not silently
  * override a conflicting non-default capacity request.
  */
class ProductSoCConfigSelectionSpec extends AnyFlatSpec with Matchers {
  behavior of "Product board target selection"

  private val small = ProductCoreProfiles.smallGeometry
  private val expanded = ProductCoreProfiles.expandedSingleLaneGeometry

  it should "preserve legacy engineering geometry when no target is supplied" in {
    val selected = AetherCoreProductSoCProfiles.selectEngineeringTarget(32, expanded, None)
    selected.geometry shouldBe expanded
    selected.mabi shouldBe "lp64"
    selected.platform.paddrBits shouldBe 32
    selected shouldBe AetherCoreProductSoCProfiles.smallOpenSbiEngineering(32, expanded)
  }

  it should "treat an explicit expanded target as authoritative over the default Small preset" in {
    val exact = AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(32, expanded)
    AetherCoreProductSoCProfiles.selectEngineeringTarget(32, small, Some(exact)) shouldBe exact
    AetherCoreProductSoCProfiles.selectEngineeringTarget(32, expanded, Some(exact)) shouldBe exact
    exact.mabi shouldBe "lp64d"
    exact.software.isa.extensions should contain('F')
    exact.software.isa.extensions should contain('D')
  }

  it should "reject a conflicting explicit non-default geometry before elaboration" in {
    val exactSmall = AetherCoreProductSoCProfiles.smallOpenSbiEngineering(32, small)
    val rejected = intercept[IllegalArgumentException] {
      AetherCoreProductSoCProfiles.selectEngineeringTarget(32, expanded, Some(exactSmall))
    }
    rejected.getMessage should include("explicit geometry disagrees")
  }

  it should "retain the distro ABI and enforce the physical board address width" in {
    val distro = AetherCoreProductSoCProfiles.selectDistroTarget(32, small, None)
    distro.mabi shouldBe "lp64d"
    distro.software.isa.extensions should contain('F')
    distro.software.isa.extensions should contain('D')
    distro shouldBe AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(32, small)

    val rejected = intercept[IllegalArgumentException] {
      AetherCoreProductSoCProfiles.selectDistroTarget(56, small, Some(distro))
    }
    rejected.getMessage should include("physical address width")
  }
}
