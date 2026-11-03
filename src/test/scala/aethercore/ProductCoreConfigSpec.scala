package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{AbiProfiles, SoftwareTarget}
import aethercore.core.product._

/** Configuration legality only. This does not instantiate or qualify ProductCore RTL. */
class ProductCoreConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "One product CPU family with independent ISA/ABI and FPGA geometry"

  it should "build Small/Medium/Large from one geometry type" in {
    val small = ProductCoreProfiles.smallEngineering
    val medium = ProductCoreProfiles.mediumDebianTarget
    val large = ProductCoreProfiles.largeDebianTarget

    small.geometry.robEntries shouldBe 8
    medium.geometry.robEntries shouldBe 16
    large.geometry.robEntries shouldBe 64
    small.geometry.issueWidth shouldBe 1
    medium.geometry.retireWidth shouldBe 2
    large.geometry.issueWidth shouldBe 2
    Seq(small, medium, large).foreach { c =>
      c.geometry.freeRegisterPressureBeforeFullRob shouldBe false
      c.rtlImplemented shouldBe false
    }
  }

  it should "allow RV64GC LP64D on Small without requiring large ROB" in {
    val small = ProductCoreProfiles.smallDebianTarget
    small.geometry shouldBe ProductCoreProfiles.smallGeometry
    small.march should include ("rv64imafdc")
    small.mabi shouldBe "lp64d"
    small.software.isa.hasSv39 shouldBe true
    small.software.isa.hasC shouldBe true
    small.software.isa.extensions.contains('F') shouldBe true
    small.software.isa.extensions.contains('D') shouldBe true
    small.rtlImplemented shouldBe false
    ProductCoreProfiles.smallEngineering.mabi shouldBe "lp64"
    ProductCoreProfiles.smallEngineering.software.isa.hasZifencei shouldBe true
  }

  it should "reject impossible dispatch width, ROB and PRF port configurations" in {
    val g = ProductCoreProfiles.smallGeometry
    intercept[IllegalArgumentException] { g.copy(dispatchWidth = 2) }
    intercept[IllegalArgumentException] { g.copy(robEntries = 7) }
    intercept[IllegalArgumentException] { g.copy(iqEntries = 0) }
    intercept[IllegalArgumentException] { g.copy(integerPhysicalRegisters = 32) }
    intercept[IllegalArgumentException] { g.copy(integerReadPorts = 1) }
    intercept[IllegalArgumentException] { g.copy(integerWritePorts = 0) }
    intercept[IllegalArgumentException] { ProductCoreProfiles.largeGeometry.copy(integerReadPorts = 2) }
  }

  it should "apply physical-register pressure as backpressure policy, not ISA legality" in {
    val narrow = ProductCoreProfiles.largeGeometry.copy(integerPhysicalRegisters = 64)
    narrow.freeRegisterPressureBeforeFullRob shouldBe true
    val target = ProductCoreProfiles.configure(
      "narrow-small", ProductCoreProfiles.smallGeometry, ProductCoreProfiles.distroSoftware)
    target.software.abi shouldBe AbiProfiles.lp64d
    target.rtlImplemented shouldBe false
  }

  it should "reject unsupported distribution combinations without misadvertising RTL" in {
    val target = ProductCoreProfiles.smallDebianTarget
    intercept[IllegalArgumentException] {
      target.copy(software = SoftwareTarget(
        target.software.isa.copy(extensions = Set('I', 'M', 'A')),
        AbiProfiles.lp64d))
    }
    intercept[IllegalArgumentException] {
      target.copy(name = "")
    }
  }
}
