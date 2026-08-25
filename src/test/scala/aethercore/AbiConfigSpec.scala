package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{AbiProfiles, CoreConfig, CoreProfiles, IsaConfig, SoftwareTarget}

class AbiConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "AbiConfig"

  it should "bind current integer software targets explicitly instead of deriving ABI from XLEN" in {
    val rv32 = SoftwareTarget(CoreProfiles.rv32imSoftware.isa, AbiProfiles.ilp32)
    rv32.march shouldBe "rv32im_zicsr"
    rv32.mabi shouldBe "ilp32"

    val rv64 = SoftwareTarget(CoreProfiles.rv64imCurrent.isa, AbiProfiles.lp64)
    rv64.march shouldBe "rv64im_zicsr_zifencei"
    rv64.mabi shouldBe "lp64"
  }

  it should "reject ABI and ISA XLEN mismatches independently from CoreConfig" in {
    an[IllegalArgumentException] should be thrownBy
      SoftwareTarget(CoreProfiles.rv32imSoftware.isa, AbiProfiles.lp64)

    an[IllegalArgumentException] should be thrownBy
      SoftwareTarget(CoreProfiles.rv64imCurrent.isa, AbiProfiles.ilp32)
  }

  it should "require floating point ISA capability only for floating point calling conventions" in {
    val rv64Integer = CoreProfiles.rv64imCurrent.isa
    SoftwareTarget(rv64Integer, AbiProfiles.lp64).mabi shouldBe "lp64"

    an[IllegalArgumentException] should be thrownBy
      SoftwareTarget(rv64Integer, AbiProfiles.lp64f)
    an[IllegalArgumentException] should be thrownBy
      SoftwareTarget(rv64Integer, AbiProfiles.lp64d)

    val rv64ifd = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'F', 'D'),
      privilegeModes = Set('M')
    )
    SoftwareTarget(rv64ifd, AbiProfiles.lp64f).mabi shouldBe "lp64f"
    SoftwareTarget(rv64ifd, AbiProfiles.lp64d).mabi shouldBe "lp64d"
  }

  it should "keep ISA description ABI compatibility and production implementation as separate gates" in {
    val descriptiveRv64Ifd = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'F', 'D'),
      privilegeModes = Set('M')
    )

    val software = SoftwareTarget(descriptiveRv64Ifd, AbiProfiles.lp64d)
    software.march shouldBe "rv64ifd"
    software.mabi shouldBe "lp64d"

    an[IllegalArgumentException] should be thrownBy
      CoreConfig(
        "unsupported-rv64ifd-production",
        descriptiveRv64Ifd,
        CoreProfiles.rv64imCurrent.platform
      )
  }
}
