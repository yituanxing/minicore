package aethercore

import aethercore.config.{AetherCoreCapabilities, CoreConfig, CoreProfiles, IsaConfig, PageTableGeometry}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RV64VirtualMemoryConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "RV64 virtual-memory configuration boundary"

  it should "advertise the first bounded production Sv39 profile only after shared plumbing exists" in {
    val config = CoreProfiles.rv64imsuSv39PmpSoftware
    config.name shouldBe "rv64imsu-sv39-pmp-software"
    config.isa.xlen shouldBe 64
    config.isa.hasPagedVirtualMemory shouldBe true
    config.isa.hasSv32 shouldBe false
    config.isa.hasSv39 shouldBe true
    config.isa.hasSv48 shouldBe false
    config.isa.orderedPageTableGeometries shouldBe Seq(PageTableGeometry.Sv39)
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 16
    config.platform.paddrBits shouldBe 56
    config.platform.busDataBits shouldBe 64
    AetherCoreCapabilities.virtualMemoryModes shouldBe Set("Sv32", "Sv39")
  }

  it should "keep Sv48 descriptive but fail closed at the current production core boundary" in {
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

    an[IllegalArgumentException] should be thrownBy
      CoreConfig("not-yet-integrated-rv64-sv48", isa, CoreProfiles.rv64imsuPmpSoftware.platform)
  }

  it should "keep Sv32 production profiles unchanged through the generalized geometry layer" in {
    val config = CoreProfiles.rv32imasuSv32PmpSoftware
    config.isa.hasPagedVirtualMemory shouldBe true
    config.isa.orderedPageTableGeometries shouldBe Seq(PageTableGeometry.Sv32)
    config.platform.paddrBits shouldBe 34
  }
}
