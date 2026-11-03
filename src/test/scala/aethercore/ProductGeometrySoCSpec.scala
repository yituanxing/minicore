package aethercore

import _root_.circt.stage.ChiselStage
import aethercore.core.product.{ProductCoreGeometry, ProductCoreProfiles, ProductSmallPagedCore}
import aethercore.soc.{AetherCoreProductAxi4SoC, AetherCoreProductFpgaSoC,
  AetherCoreProductUnifiedMemorySoC}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Qualifies *real* single-lane Product hardware at different storage depths.
  *
  * Capacity scaling is intentionally independent of an unimplemented two-wide
  * dispatch/retire path. These checks exercise one RTL implementation, from
  * ROB/PRF/IQ through LQ/SQ, memory-hub tags, AXI and the FPGA shell. Passing
  * configuration-only case-class tests would not prove these boundaries.
  */
class ProductGeometrySoCSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product one-wide SoC capacity scaling"

  private val small = ProductCoreProfiles.smallGeometry
  private val expanded = small.copy(
    robEntries = 16,
    iqEntries = 8,
    integerPhysicalRegisters = 64,
    loadQueueEntries = 4,
    storeQueueEntries = 4
  )
  private val geometries = Seq(
    ("small", small, 4),
    ("expanded", expanded, 5)
  )

  private def validateSingleLane(g: ProductCoreGeometry): Unit = {
    g.decodeWidth shouldBe 1
    g.dispatchWidth shouldBe 1
    g.issueWidth shouldBe 1
    g.retireWidth shouldBe 1
    g.integerReadPorts shouldBe 2
    g.integerWritePorts shouldBe 1
  }

  it should "reject an unimplemented F/D Debian profile while accepting the real RV64IMAC hardware slice" in {
    val compressed = ProductCoreProfiles.smallCompressedEngineering
    compressed.software.isa.hasC shouldBe true
    compressed.software.isa.extensions.contains('F') shouldBe false
    compressed.software.isa.extensions.contains('D') shouldBe false
    compressed.mabi shouldBe "lp64"
    ChiselStage.emitCHIRRTL(new ProductSmallPagedCore(compressed)) should
      include("ProductSmallPagedCore")

    intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new ProductSmallPagedCore(
        ProductCoreProfiles.smallDebianTarget))
    }
  }

  it should "elaborate both capacities across unified, AXI4 and FPGA compositions" in {
    geometries.foreach { case (name, g, externalIdBits) =>
      withClue(name) {
        validateSingleLane(g)
        val unified = ChiselStage.emitCHIRRTL(
          new AetherCoreProductUnifiedMemorySoC(
            implementedPaddrBits = 32, productGeometry = g))
        unified should include("AetherCoreProductUnifiedMemorySoC")
        unified should include("ProductSmallPagedCore")
        unified should include("ProductPhysicalLsq")
        unified should include("AetherSoCMemoryHub")
        unified should include(s"txnId : UInt<$externalIdBits>")

        val axi = ChiselStage.emitCHIRRTL(
          new AetherCoreProductAxi4SoC(
            implementedPaddrBits = 32, productGeometry = g))
        axi should include("AetherCoreProductAxi4SoC")
        axi should include("AetherMemToAxi4Bridge")
        axi should include(s"id : UInt<$externalIdBits>")

        val fpga = ChiselStage.emitCHIRRTL(
          new AetherCoreProductFpgaSoC(
            implementedPaddrBits = 32, productGeometry = g))
        fpga should include("AetherCoreProductFpgaSoC")
        fpga should include("AetherUart8N1Phy")
        fpga should include(s"id : UInt<$externalIdBits>")
      }
    }
  }

  it should "retain exact external transaction IDs and BootROM execution for each capacity" in {
    val ram = BigInt("80000000", 16)
    geometries.foreach { case (name, g, externalIdBits) =>
      withClue(name) {
        simulate(new AetherCoreProductUnifiedMemorySoC(
          implementedPaddrBits = 32, productGeometry = g)) { d =>
          d.io.memoryRequest.bits.txnId.getWidth shouldBe externalIdBits
          d.io.memoryResponse.bits.txnId.getWidth shouldBe externalIdBits
          d.io.rxValid.poke(false.B)
          d.io.rxByte.poke(0.U)
          d.io.memoryRequest.ready.poke(false.B)
          d.io.memoryResponse.valid.poke(false.B)
          d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
          d.clock.step()

          // A real BootROM jump and external-RAM code path, rather than a
          // synthetic dispatch into the OoO backend. Both geometries use the
          // same instructions and memory contract.
          val words = Map[BigInt, BigInt](
            ram -> BigInt("02a00313", 16),      // addi x6,x0,42
            (ram + 4) -> BigInt("0402a503", 16), // lw x10,64(x5)
            (ram + 8) -> BigInt("0000006f", 16), // jal x0,0
            (ram + 64) -> BigInt("00000055", 16)
          )
          def readAt(address: BigInt): BigInt =
            (0 until 8).foldLeft(BigInt(0)) { (acc, offset) =>
              val a = address + offset
              val word = words.getOrElse(a & ~BigInt(3), BigInt("00000013", 16))
              val b = (word >> ((a & 3).toInt * 8)) & 255
              acc | (b << (offset * 8))
            }

          var pending = Option.empty[(BigInt, BigInt)]
          var sawBoot = false
          var sawAdd = false
          var sawLoad = false
          var sawExternal = false
          for (_ <- 0 until 800 if !(sawBoot && sawAdd && sawLoad && sawExternal)) {
            val ready = pending.isEmpty
            d.io.memoryRequest.ready.poke(ready.B)
            d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
            pending.foreach { case (id, data) =>
              d.io.memoryResponse.bits.txnId.poke(id.U)
              d.io.memoryResponse.bits.rdata.poke(data.U)
              d.io.memoryResponse.bits.fault.poke(false.B)
              d.io.memoryResponse.bits.last.poke(true.B)
            }
            val issue = ready && d.io.memoryRequest.valid.peek().litToBoolean
            val request = if (issue) {
              val addr = d.io.memoryRequest.bits.paddr.peek().litValue
              val id = d.io.memoryRequest.bits.txnId.peek().litValue
              addr should be >= ram
              id should be < (BigInt(1) << externalIdBits)
              sawExternal = true
              Some(id -> readAt(addr))
            } else None
            val complete = pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean

            if (d.io.retire.valid.peek().litToBoolean) {
              val pc = d.io.retire.bits.pc.peek().litValue
              if (pc == BigInt("1000", 16)) sawBoot = true
              if (pc == ram) {
                d.io.retire.bits.exception.valid.expect(false.B)
                d.io.retire.bits.value.expect(42.U)
                sawAdd = true
              }
              if (pc == ram + 4) {
                d.io.retire.bits.exception.valid.expect(false.B)
                d.io.retire.bits.value.expect(85.U)
                sawLoad = true
              }
            }
            d.clock.step()
            if (complete) pending = None
            if (request.nonEmpty) pending = request
          }
          sawBoot shouldBe true
          sawAdd shouldBe true
          sawLoad shouldBe true
          sawExternal shouldBe true
        }
      }
    }
  }
}
