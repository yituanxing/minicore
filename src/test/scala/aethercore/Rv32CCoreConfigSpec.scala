package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig}

class Rv32CCoreConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "RVC CoreConfig"

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

  it should "admit RV64C implementation configurations without promoting the qualified RV64 profiles" in {
    val rv64c = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'C'),
      privilegeModes = Set('M'),
      zExtensions = Set("Zicsr")
    )
    val implementation = CoreConfig(
      "rv64imc-implementation",
      rv64c,
      CoreProfiles.rv64imCurrent.platform
    )

    implementation.isa.hasC shouldBe true
    implementation.isa.march shouldBe "rv64imc_zicsr"
    implementation.isa.mabi shouldBe "lp64"

    CoreProfiles.rv64imCurrent.isa.hasC shouldBe false
    CoreProfiles.rv64imasuSv39PmpSoftware.isa.hasC shouldBe false
  }
}
