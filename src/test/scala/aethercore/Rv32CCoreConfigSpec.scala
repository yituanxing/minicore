package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig}

class Rv32CCoreConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "RV32C CoreConfig"

  it should "advertise the bounded RV32IMC software profile" in {
    val config = CoreProfiles.rv32imcSoftware
    config.isa.hasC shouldBe true
    config.isa.xlen shouldBe 32
    config.isa.march shouldBe "rv32imc_zicsr"
  }

  it should "compose C with the existing RV32 Sv32 and PMP surfaces" in {
    val sv32 = CoreProfiles.rv32imsuSv32Software.copy(
      name = "rv32imcsu-sv32-test",
      isa = CoreProfiles.rv32imsuSv32Software.isa.copy(
        extensions = CoreProfiles.rv32imsuSv32Software.isa.extensions + 'C'
      )
    )
    sv32.isa.hasC shouldBe true
    sv32.isa.hasSv32 shouldBe true

    val pmp = CoreConfig(
      name = "rv32imcu-pmp-test",
      isa = IsaConfig(
        xlen = 32,
        extensions = Set('I', 'M', 'C'),
        privilegeModes = Set('M', 'U'),
        zExtensions = Set("Zicsr"),
        pmpEntries = 16
      ),
      platform = CoreProfiles.rv32iMinimal.platform
    )
    pmp.isa.hasC shouldBe true
    pmp.isa.hasPmp shouldBe true
  }

  it should "keep compressed execution RV32-only" in {
    val rv64c = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'C'),
      privilegeModes = Set('M')
    )
    an[IllegalArgumentException] should be thrownBy
      CoreConfig("unsupported-rv64c", rv64c, CoreProfiles.rv64imCurrent.platform)
  }
}
