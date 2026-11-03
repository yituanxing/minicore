package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.sim.AetherCoreProductOpenSbiRV64SimTop
import aethercore.soc.{AetherCoreProductAxi4SoC, AetherCoreProductFpgaSoC, AetherCoreProductUnifiedMemorySoC}
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
}
