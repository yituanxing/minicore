package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.ProductCoreProfiles
import aethercore.sim.AetherCoreProductOpenSbiRV64SimTop
import aethercore.soc.{AetherCoreProductAxi4SoC, AetherCoreProductFpgaSoC,
  AetherCoreProductSoCProfiles, AetherCoreProductUnifiedMemorySoC}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** All three independent Product SoC hardware compositions are real Chisel
  * hierarchies and must elaborate even without a Verilator executable.
  */
class AetherCoreProductSoCElaborationSpec extends AnyFlatSpec with Matchers {
  behavior of "Product SmallOoO SoC hardware tops"

  it should "elaborate unified, AXI4, and pin-level FPGA topologies" in {
    val unified = ChiselStage.emitCHIRRTL(new AetherCoreProductUnifiedMemorySoC())
    unified should include("AetherCoreProductUnifiedMemorySoC")
    unified should include("ProductSmallPagedCore")
    unified should include("AetherDirectMappedReadCache")
    unified should include("AetherSoCMemoryHub")
    unified should include("AetherSoCBootRom")

    val axi = ChiselStage.emitCHIRRTL(new AetherCoreProductAxi4SoC())
    axi should include("AetherCoreProductAxi4SoC")
    axi should include("AetherMemToAxi4Bridge")

    val fpga = ChiselStage.emitCHIRRTL(new AetherCoreProductFpgaSoC())
    fpga should include("AetherCoreProductFpgaSoC")
    fpga should include("AetherUart8N1Phy")

    val host = ChiselStage.emitCHIRRTL(new AetherCoreProductOpenSbiRV64SimTop)
    host should include("AetherCoreProductOpenSbiRV64SimTop")
    host should include("AetherSoCUnifiedHostMemoryAdapter")
  }

  it should "carry one explicitly selected RV64GC/LP64D target across FPGA, AXI4 and paged CPU" in {
    // The selected target, not the legacy productGeometry default, owns both
    // the actual F/D datapath and the external LSQ/AXI transaction geometry.
    val target = AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(
      32, ProductCoreProfiles.expandedSingleLaneGeometry)
    target.mabi shouldBe "lp64d"
    target.software.isa.extensions.contains('F') shouldBe true
    target.software.isa.extensions.contains('D') shouldBe true
    target.platform.paddrBits shouldBe 32

    val fpga = ChiselStage.emitCHIRRTL(new AetherCoreProductFpgaSoC(
      implementedPaddrBits = 32,
      productConfigOverride = Some(target)))
    fpga should include("AetherCoreProductFpgaSoC")
    fpga should include("AetherCoreProductAxi4SoC")
    fpga should include("AetherCoreProductUnifiedMemorySoC")
    fpga should include("ProductSmallPagedCore")
    fpga should include("ProductFpArithmetic")
    fpga should include("id : UInt<5>")

    // A selected CPU target cannot silently disagree with the physical
    // address seam of the FPGA/AXI/peripheral shell.
    val rejected = intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new AetherCoreProductFpgaSoC(
        implementedPaddrBits = 56,
        productConfigOverride = Some(target)))
    }
    rejected.getMessage should include("physical address width")
  }
}
