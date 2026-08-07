package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreProfiles, IsaConfig, PlatformConfig}

class CoreConfigSpec extends AnyFlatSpec with Matchers {
  behavior of "CoreConfig"

  it should "describe the exact current RV64IM software contract" in {
    val config = CoreProfiles.rv64imCurrent

    config.name shouldBe "rv64im-current"
    config.isa.xlen shouldBe 64
    config.isa.xBytes shouldBe 8
    config.isa.shiftBits shouldBe 6
    config.isa.hasM shouldBe true
    config.isa.hasA shouldBe false
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasWordOps shouldBe true
    config.isa.march shouldBe "rv64im_zicsr"
    config.isa.mabi shouldBe "lp64"
    config.platform.resetVector shouldBe BigInt("80000000", 16)
    config.platform.busDataBits shouldBe 64
    config.platform.busBytes shouldBe 8
    config.platform.mtimeAddress shouldBe BigInt("0200bff8", 16)
    config.platform.mtimecmpAddress shouldBe BigInt("02004000", 16)
  }

  it should "describe the executable RV32I profile" in {
    val config = CoreProfiles.rv32iMinimal

    config.name shouldBe "rv32i-minimal"
    config.isa.xlen shouldBe 32
    config.isa.xBytes shouldBe 4
    config.isa.shiftBits shouldBe 5
    config.isa.hasM shouldBe false
    config.isa.hasZicsr shouldBe false
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasWordOps shouldBe false
    config.isa.march shouldBe "rv32i"
    config.isa.mabi shouldBe "ilp32"
    config.platform.resetVector shouldBe BigInt("80000000", 16)
    config.platform.paddrBits shouldBe 32
    config.platform.busDataBits shouldBe 32
    config.platform.busBytes shouldBe 4
    config.platform.mtimeAddress shouldBe BigInt("0200bff8", 16)
    config.platform.mtimecmpAddress shouldBe BigInt("02004000", 16)
  }

  it should "describe the RV32IM real-software profile" in {
    val config = CoreProfiles.rv32imSoftware

    config.name shouldBe "rv32im-software"
    config.isa.xlen shouldBe 32
    config.isa.xBytes shouldBe 4
    config.isa.shiftBits shouldBe 5
    config.isa.hasM shouldBe true
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasWordOps shouldBe false
    config.isa.march shouldBe "rv32im_zicsr"
    config.isa.mabi shouldBe "ilp32"
    config.platform shouldBe CoreProfiles.rv32iMinimal.platform
  }

  it should "describe the bounded RV32IM M/S/U V1 supervisor profile" in {
    val config = CoreProfiles.rv32imsuSoftware

    config.name shouldBe "rv32imsu-software"
    config.isa.xlen shouldBe 32
    config.isa.hasM shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasA shouldBe false
    config.isa.hasPmp shouldBe false
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.march shouldBe "rv32im_zicsr"
    config.isa.mabi shouldBe "ilp32"
    config.platform shouldBe CoreProfiles.rv32iMinimal.platform
  }

  it should "describe an independent RV32 Sv32 Supervisor ISA contract" in {
    val isa = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv32")
    )

    isa.hasS shouldBe true
    isa.hasU shouldBe true
    isa.hasSv32 shouldBe true
    isa.virtualMemoryModes shouldBe Set("Sv32")
    isa.march shouldBe "rv32im_zicsr"
    isa.mabi shouldBe "ilp32"
  }

  it should "keep the pre-atomic protected profile available for regressions" in {
    val config = CoreProfiles.rv32imuPmpOsSoftware

    config.name shouldBe "rv32imu-pmp-os-software"
    config.isa.hasA shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.march shouldBe "rv32im_zicsr_zifencei"
  }

  it should "describe the exact NuttX protected RV32IMAU OS profile" in {
    val config = CoreProfiles.rv32imauPmpOsSoftware

    config.name shouldBe "rv32imau-pmp-os-software"
    config.isa.xlen shouldBe 32
    config.isa.hasM shouldBe true
    config.isa.hasA shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasS shouldBe false
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 4
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe true
    config.isa.hasSv32 shouldBe false
    config.isa.hasC shouldBe false
    config.isa.march shouldBe "rv32ima_zicsr_zifencei"
    config.isa.mabi shouldBe "ilp32"
    config.platform shouldBe CoreProfiles.rv32iMinimal.platform
  }

  it should "derive an independent RV32I software contract" in {
    val isa = IsaConfig(
      xlen = 32,
      extensions = Set('I'),
      privilegeModes = Set('M')
    )

    isa.xBytes shouldBe 4
    isa.shiftBits shouldBe 5
    isa.hasZicsr shouldBe false
    isa.hasZifencei shouldBe false
    isa.hasSv32 shouldBe false
    isa.hasWordOps shouldBe false
    isa.march shouldBe "rv32i"
    isa.mabi shouldBe "ilp32"
  }

  it should "reject unsupported architectural, extension, VM and platform widths early" in {
    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 48, extensions = Set('I'), privilegeModes = Set('M'))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 32,
        extensions = Set('I'),
        privilegeModes = Set('M'),
        zExtensions = Set("icsr")
      )

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 32,
        extensions = Set('I'),
        privilegeModes = Set('M', 'S'),
        virtualMemoryModes = Set("Sv48")
      )

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 64,
        extensions = Set('I'),
        privilegeModes = Set('M', 'S'),
        virtualMemoryModes = Set("Sv32")
      )

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 32,
        extensions = Set('I'),
        privilegeModes = Set('M'),
        virtualMemoryModes = Set("Sv32")
      )

    an[IllegalArgumentException] should be thrownBy
      PlatformConfig(
        resetVector = 0,
        paddrBits = 40,
        busDataBits = 128,
        uartAddress = 0,
        exitAddress = 8,
        mtimeAddress = BigInt("0200bff8", 16),
        mtimecmpAddress = BigInt("02004000", 16)
      )
  }
}
