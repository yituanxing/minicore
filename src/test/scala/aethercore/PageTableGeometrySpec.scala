package aethercore

import aethercore.config.PageTableGeometry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PageTableGeometrySpec extends AnyFlatSpec with Matchers {
  behavior of "PageTableGeometry"

  it should "preserve the frozen Sv32 architectural shape" in {
    val mode = PageTableGeometry.Sv32
    mode.name shouldBe "Sv32"
    mode.xlen shouldBe 32
    mode.satpMode shouldBe 1
    mode.vaBits shouldBe 32
    mode.levels shouldBe 2
    mode.vpnBitsPerLevel shouldBe 10
    mode.vpnBits shouldBe 20
    mode.pteBytes shouldBe 4
    mode.pteBits shouldBe 32
    mode.ppnBits shouldBe 22
    mode.architecturalPhysicalAddressBits shouldBe 34
    mode.asidBits shouldBe 9
  }

  it should "describe Sv39 and Sv48 as one RV64 page-table family" in {
    val sv39 = PageTableGeometry.Sv39
    val sv48 = PageTableGeometry.Sv48

    sv39.xlen shouldBe 64
    sv39.satpMode shouldBe 8
    sv39.vaBits shouldBe 39
    sv39.levels shouldBe 3
    sv39.vpnBitsPerLevel shouldBe 9
    sv39.vpnBits shouldBe 27

    sv48.xlen shouldBe 64
    sv48.satpMode shouldBe 9
    sv48.vaBits shouldBe 48
    sv48.levels shouldBe 4
    sv48.vpnBitsPerLevel shouldBe 9
    sv48.vpnBits shouldBe 36

    sv39.pteBytes shouldBe 8
    sv48.pteBytes shouldBe 8
    sv39.ppnBits shouldBe 44
    sv48.ppnBits shouldBe 44
    sv39.architecturalPhysicalAddressBits shouldBe 56
    sv48.architecturalPhysicalAddressBits shouldBe 56
    sv39.asidBits shouldBe 16
    sv48.asidBits shouldBe 16
  }

  it should "validate architectural mode families without implying hardware support" in {
    PageTableGeometry.validateArchitecturalModes(32, Set('M', 'S', 'U'), Set("Sv32")) shouldBe
      Set(PageTableGeometry.Sv32)
    PageTableGeometry.validateArchitecturalModes(64, Set('M', 'S', 'U'), Set("Sv39")) shouldBe
      Set(PageTableGeometry.Sv39)
    PageTableGeometry.validateArchitecturalModes(64, Set('M', 'S', 'U'), Set("Sv39", "Sv48")) shouldBe
      Set(PageTableGeometry.Sv39, PageTableGeometry.Sv48)

    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry.validateArchitecturalModes(64, Set('M', 'S', 'U'), Set("Sv48"))
    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry.validateArchitecturalModes(32, Set('M', 'S', 'U'), Set("Sv39"))
    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry.validateArchitecturalModes(64, Set('M', 'U'), Set("Sv39"))
    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry.validateArchitecturalModes(64, Set('M', 'S'), Set("Sv57"))
  }

  it should "reject internally inconsistent geometry" in {
    an[IllegalArgumentException] should be thrownBy
      PageTableGeometry(
        name = "bad",
        xlen = 64,
        satpMode = 10,
        vaBits = 48,
        levels = 3,
        vpnBitsPerLevel = 9,
        pteBytes = 8,
        ppnBits = 44,
        asidBits = 16
      )
  }
}
