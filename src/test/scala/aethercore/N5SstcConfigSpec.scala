package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreProfiles, IsaConfig}

class N5SstcConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "bounded N5 Sstc configuration"

  it should "advertise Sstc only on the real N5 paging profile" in {
    CoreProfiles.rv32imasuSv32Software.isa.hasSstc shouldBe true
    CoreProfiles.rv32imsuSv32Software.isa.hasSstc shouldBe false
    CoreProfiles.rv32imsuSoftware.isa.hasSstc shouldBe false
    CoreProfiles.rv32imSoftware.isa.hasSstc shouldBe false
    CoreProfiles.rv64imCurrent.isa.hasSstc shouldBe false
  }

  it should "keep the current Sstc implementation bounded to RV32 Supervisor mode" in {
    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 32,
        extensions = Set('I', 'M'),
        privilegeModes = Set('M'),
        zExtensions = Set("Zicsr"),
        sstc = true
      )

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 64,
        extensions = Set('I', 'M'),
        privilegeModes = Set('M', 'S'),
        zExtensions = Set("Zicsr"),
        sstc = true
      )
  }
}
