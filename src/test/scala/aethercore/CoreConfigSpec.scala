package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreConfig, CoreProfiles, IsaConfig, PlatformConfig}

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
    config.isa.hasZifencei shouldBe true
    config.isa.hasSv32 shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.hasWordOps shouldBe true
    config.isa.march shouldBe "rv64im_zicsr_zifencei"
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
    config.isa.hasSstc shouldBe false
    config.isa.hasWordOps shouldBe false
    config.isa.march shouldBe "rv32i"
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
    config.isa.hasSstc shouldBe false
    config.isa.hasWordOps shouldBe false
    config.isa.march shouldBe "rv32im_zicsr"
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
    config.isa.hasSstc shouldBe false
    config.isa.march shouldBe "rv32im_zicsr"
    config.platform shouldBe CoreProfiles.rv32iMinimal.platform
  }

  it should "describe the bounded RV32 Sv32 PA34 data-translation profile" in {
    val config = CoreProfiles.rv32imsuSv32Software
    config.name shouldBe "rv32imsu-sv32-software"
    config.isa.xlen shouldBe 32
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasSv32 shouldBe true
    config.isa.hasSstc shouldBe false
    config.isa.hasA shouldBe false
    config.isa.hasPmp shouldBe false
    config.isa.march shouldBe "rv32im_zicsr"
    config.platform.paddrBits shouldBe 34
    config.platform.busDataBits shouldBe 32
    config.platform.resetVector shouldBe BigInt("80000000", 16)
  }

  it should "describe the N5 RV32IMA Sv32 plus Sstc workload profile" in {
    val config = CoreProfiles.rv32imasuSv32Software
    config.isa.xlen shouldBe 32
    config.isa.hasA shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasSv32 shouldBe true
    config.isa.hasSstc shouldBe true
    config.isa.march shouldBe "rv32ima_zicsr_zifencei"
  }

  it should "describe the composed RV32IMA Sv32 PMP16 workload profile" in {
    val config = CoreProfiles.rv32imasuSv32PmpSoftware
    config.name shouldBe "rv32imasu-sv32-pmp-software"
    config.isa.xlen shouldBe 32
    config.isa.hasA shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasSv32 shouldBe true
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 16
    config.isa.hasSstc shouldBe true
    config.platform.paddrBits shouldBe 34
    config.isa.march shouldBe "rv32ima_zicsr_zifencei"
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
    isa.hasSstc shouldBe false
    isa.virtualMemoryModes shouldBe Set("Sv32")
    isa.march shouldBe "rv32im_zicsr"
  }

  it should "keep the pre-atomic protected profile available for regressions" in {
    val config = CoreProfiles.rv32imuPmpOsSoftware
    config.name shouldBe "rv32imu-pmp-os-software"
    config.isa.hasA shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.pmpEntries shouldBe 16
    config.isa.march shouldBe "rv32im_zicsr_zifencei"
  }

  it should "describe the exact NuttX protected RV32IMAU PMP16 OS profile" in {
    val config = CoreProfiles.rv32imauPmpOsSoftware
    config.name shouldBe "rv32imau-pmp-os-software"
    config.isa.xlen shouldBe 32
    config.isa.hasM shouldBe true
    config.isa.hasA shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasS shouldBe false
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 16
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe true
    config.isa.hasSv32 shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.hasC shouldBe false
    config.isa.march shouldBe "rv32ima_zicsr_zifencei"
    config.platform shouldBe CoreProfiles.rv32iMinimal.platform
  }

  it should "describe the bounded RV64 M/S/U PMP16 profile" in {
    val config = CoreProfiles.rv64imsuPmpSoftware
    config.name shouldBe "rv64imsu-pmp-software"
    config.isa.xlen shouldBe 64
    config.isa.hasM shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasA shouldBe false
    config.isa.hasC shouldBe false
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 16
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.march shouldBe "rv64im_zicsr"
    config.platform.paddrBits shouldBe 56
    config.platform.busDataBits shouldBe 64
  }

  it should "describe the bounded RV64A Sv39 PMP16 production profile" in {
    val config = CoreProfiles.rv64imasuSv39PmpSoftware
    config.name shouldBe "rv64imasu-sv39-pmp-software"
    config.isa.xlen shouldBe 64
    config.isa.hasM shouldBe true
    config.isa.hasA shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasPmp shouldBe true
    config.isa.pmpEntries shouldBe 16
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.hasSv39 shouldBe true
    config.isa.hasSv48 shouldBe false
    config.isa.hasC shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.march shouldBe "rv64ima_zicsr"
    config.platform.paddrBits shouldBe 56
    config.platform.busDataBits shouldBe 64
  }

  it should "derive an independent RV32I ISA contract" in {
    val isa = IsaConfig(xlen = 32, extensions = Set('I'), privilegeModes = Set('M'))
    isa.xBytes shouldBe 4
    isa.shiftBits shouldBe 5
    isa.hasZicsr shouldBe false
    isa.hasZifencei shouldBe false
    isa.hasSv32 shouldBe false
    isa.hasSstc shouldBe false
    isa.hasWordOps shouldBe false
    isa.march shouldBe "rv32i"
  }

  it should "keep IsaConfig descriptive while CoreConfig rejects unrealizable AetherCore profiles" in {
    val rv32Platform = CoreProfiles.rv32iMinimal.platform
    val rv32Sv32Platform = CoreProfiles.rv32imsuSv32Software.platform
    val rv64Platform = CoreProfiles.rv64imCurrent.platform

    val rv64ima = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M', 'A'),
      privilegeModes = Set('M')
    )
    rv64ima.march shouldBe "rv64ima"
    val supportedRv64A = CoreConfig("supported-rv64a", rv64ima, rv64Platform)
    supportedRv64A.isa.hasA shouldBe true
    supportedRv64A.platform.busDataBits shouldBe 64

    val rv64Pmp16 = IsaConfig(
      xlen = 64,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr"),
      pmpEntries = 16
    )
    rv64Pmp16.hasPmp shouldBe true
    val supportedRv64Pmp16 = CoreConfig(
      "supported-rv64-pmp16",
      rv64Pmp16,
      CoreProfiles.rv64imsuPmpSoftware.platform
    )
    supportedRv64Pmp16.platform.paddrBits shouldBe 56
    an[IllegalArgumentException] should be thrownBy
      CoreConfig("unsupported-rv64-pmp-pa64", rv64Pmp16, rv64Platform)

    val rv32Pmp64 = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'U'),
      zExtensions = Set("Zicsr"),
      pmpEntries = 64
    )
    rv32Pmp64.pmpEntries shouldBe 64
    an[IllegalArgumentException] should be thrownBy
      CoreConfig("unsupported-rv32-pmp64", rv32Pmp64, rv32Platform)

    val sv32Pmp16 = IsaConfig(
      xlen = 32,
      extensions = Set('I', 'M'),
      privilegeModes = Set('M', 'S', 'U'),
      zExtensions = Set("Zicsr"),
      virtualMemoryModes = Set("Sv32"),
      pmpEntries = 16
    )
    sv32Pmp16.hasSv32 shouldBe true
    sv32Pmp16.hasPmp shouldBe true
    val composed = CoreConfig("composed-sv32-pmp16", sv32Pmp16, rv32Sv32Platform)
    composed.isa.hasSv32 shouldBe true
    composed.isa.hasPmp shouldBe true
    composed.platform.paddrBits shouldBe 34

    val unsupportedProfiles = Seq(
      "float" -> IsaConfig(32, Set('I', 'F'), Set('M')),
      "double" -> IsaConfig(32, Set('I', 'D'), Set('M')),
      "bitmanip" -> IsaConfig(32, Set('I', 'B'), Set('M')),
      "unknown-z" -> IsaConfig(32, Set('I'), Set('M'), zExtensions = Set("Zba")),
      "hypervisor" -> IsaConfig(32, Set('I'), Set('M', 'H'))
    )

    for ((name, isa) <- unsupportedProfiles) {
      an[IllegalArgumentException] should be thrownBy
        CoreConfig(s"unsupported-$name", isa, rv32Platform)
    }
  }

  it should "reject unsupported architectural, extension, VM and platform widths early" in {
    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 48, extensions = Set('I'), privilegeModes = Set('M'))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 32, extensions = Set('I'), privilegeModes = Set('M'), zExtensions = Set("icsr"))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 32, extensions = Set('I'), privilegeModes = Set('M', 'S'), virtualMemoryModes = Set("Sv48"))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 64, extensions = Set('I'), privilegeModes = Set('M', 'S'), virtualMemoryModes = Set("Sv32"))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 32, extensions = Set('I'), privilegeModes = Set('M'), virtualMemoryModes = Set("Sv32"))

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 32, extensions = Set('I'), privilegeModes = Set('M'), sstc = true)

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(xlen = 64, extensions = Set('I'), privilegeModes = Set('M', 'S'), sstc = true)

    an[IllegalArgumentException] should be thrownBy
      IsaConfig(
        xlen = 32,
        extensions = Set('I'),
        privilegeModes = Set('M', 'U'),
        pmpEntries = 4
      )

    val sv32Isa = IsaConfig(
      xlen = 32,
      extensions = Set('I'),
      privilegeModes = Set('M', 'S'),
      virtualMemoryModes = Set("Sv32")
    )
    an[IllegalArgumentException] should be thrownBy
      CoreConfig(
        "bad-sv32-pa",
        sv32Isa,
        PlatformConfig(
          resetVector = BigInt("80000000", 16),
          paddrBits = 32,
          busDataBits = 32,
          uartAddress = BigInt("10000000", 16),
          exitAddress = BigInt("10000008", 16),
          mtimeAddress = BigInt("0200bff8", 16),
          mtimecmpAddress = BigInt("02004000", 16)
        )
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
