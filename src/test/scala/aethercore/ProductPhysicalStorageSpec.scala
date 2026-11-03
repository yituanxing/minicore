package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.{ProductCoreProfiles, ProductDataResolver, ProductPhysicalLsq}
import aethercore.soc.AetherCoreProductFpgaSoC
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A structural gate for the actual FPGA RTL, not merely a geometry preset.
  * No Verilator installation is needed for the physical storage assertions.
  */
class ProductPhysicalStorageSpec extends AnyFlatSpec with Matchers {
  behavior of "Product physical LSQ and translation stage ownership"

  private def slotsWidth(fir: String): String =
    fir.linesIterator.find(_.contains("wire _slots_WIRE :")).getOrElse(
      fail("missing physical slots bundle in emitted FIRRTL"))

  it should "scale wide LSQ storage by LQ+SQ, independently of ROB depth" in {
    val baseline = ProductCoreProfiles.smallGeometry
    val deepRob = baseline.copy(robEntries = 32)
    val expanded = ProductCoreProfiles.expandedSingleLaneGeometry

    val a = ChiselStage.emitCHIRRTL(new ProductPhysicalLsq(baseline))
    val b = ChiselStage.emitCHIRRTL(new ProductPhysicalLsq(deepRob))
    val c = ChiselStage.emitCHIRRTL(new ProductPhysicalLsq(expanded))

    slotsWidth(a) should include("[4]")
    slotsWidth(b) should include("[4]")
    slotsWidth(c) should include("[8]")
  }

  it should "keep one PMP snapshot owner through classification and accept non-power-of-two queues" in {
    val baseline = ProductCoreProfiles.smallEngineering
    val expanded = baseline.copy(geometry = ProductCoreProfiles.expandedSingleLaneGeometry)
    val sixSlots = baseline.copy(geometry = baseline.geometry.copy(
      loadQueueEntries = 3, storeQueueEntries = 3))

    Seq(baseline, expanded, sixSlots).foreach { config =>
      val fir = ChiselStage.emitCHIRRTL(new ProductDataResolver(config))
      slotsWidth(fir) should include(s"[${config.geometry.loadQueueEntries + config.geometry.storeQueueEntries}]")
      fir should include("reg postPaddr :")
      fir should not include "reg post :"
    }
  }

  it should "elaborate the identical FPGA top with both compact LSQ capacities" in {
    Seq(ProductCoreProfiles.smallGeometry,
      ProductCoreProfiles.expandedSingleLaneGeometry).foreach { geometry =>
      val fir = ChiselStage.emitCHIRRTL(new AetherCoreProductFpgaSoC(
        implementedPaddrBits = 32, productGeometry = geometry))
      fir should include("AetherCoreProductFpgaSoC")
      fir should include("ProductPhysicalLsq")
      fir should include("ProductDataResolver")
    }
  }
}
