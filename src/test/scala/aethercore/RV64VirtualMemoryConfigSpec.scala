package aethercore

import aethercore.config.{AetherCoreCapabilities, CoreConfig, CoreProfiles, IsaConfig, PageTableGeometry}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RV64VirtualMemoryConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "RV64 virtual-memory configuration boundary"

  it should "describe Sv39 independently from production capability advertisement" in {
    val isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv39"),
      pmpEntries = 16
    )

    isa.hasPagedVirtualMemory shouldBe true
    isa.hasSv32 shouldBe false
    isa.hasSv39 shouldBe true
    isa.hasSv48 shouldBe false
    isa.orderedPageTableGeometries shouldBe Seq(PageTableGeometry.Sv39)
    PageTableGeometry.Sv39.architecturalPhysicalAddressBits shouldBe 56

    AetherCoreCapabilities.virtualMemoryModes shouldBe Set("Sv32")
    an[IllegalArgumentException] should be thrownBy
      CoreConfig("not-yet-integrated-rv64-sv39", isa, CoreProfiles.rv64imsuPmpSoftware.platform)
  }

  it should "require Sv39 when an RV64 architectural surface describes Sv48" in {
    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 64,
        extensions = Set('I', 'M'),
        privilegeModes = Set('M', 'S', 'U'),
        virtualMemoryModes = Set("Sv48")
      )

    val isa = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      virtualMemoryModes = Set("Sv39", "Sv48")
    )
    isa.hasSv39 shouldBe true
    isa.hasSv48 shouldBe true
    isa.orderedPageTableGeometries shouldBe Seq(PageTableGeometry.Sv39, PageTableGeometry.Sv48)
  }

  it should "keep Sv32 production profiles unchanged through the generalized geometry layer" in {
    val config = CoreProfiles.rv32imasuSv32PmpSoftware
    config.isa.hasPagedVirtualMemory shouldBe true
    config.isa.orderedPageTableGeometries shouldBe Seq(PageTableGeometry.Sv32)
    config.platform.paddrBits shouldBe 34
  }
}
