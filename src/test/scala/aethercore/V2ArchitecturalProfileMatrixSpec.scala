package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.{CoreConfig, CoreProfiles}

class V2ArchitecturalProfileMatrixSpec extends AnyFlatSpec with Matchers {
  behavior of "AetherCore v2 supported architectural profiles"

  private final case class ExpectedProfile(
      config: CoreConfig,
      xlen: Int,
      march: String,
      mabi: String,
      privilegeModes: Set[Char],
      vmModes: Set[String],
      pmpEntries: Int,
      compressed: Boolean
  )

  private val representativeProfiles = Seq(
    ExpectedProfile(
      config = CoreProfiles.rv32imcSoftware,
      xlen = 32,
      march = "rv32imc_zicsr",
      mabi = "ilp32",
      privilegeModes = Set('M'),
      vmModes = Set.empty,
      pmpEntries = 0,
      compressed = true
    ),
    ExpectedProfile(
      config = CoreProfiles.rv32imacsuSv32PmpSoftware,
      xlen = 32,
      march = "rv32imac_zicsr_zifencei",
      mabi = "ilp32",
      privilegeModes = Set('M', 'S', 'U'),
      vmModes = Set("Sv32"),
      pmpEntries = 16,
      compressed = true
    ),
    ExpectedProfile(
      config = CoreProfiles.rv64imCurrent,
      xlen = 64,
      march = "rv64im_zicsr_zifencei",
      mabi = "lp64",
      privilegeModes = Set('M'),
      vmModes = Set.empty,
      pmpEntries = 0,
      compressed = false
    ),
    ExpectedProfile(
      config = CoreProfiles.rv64imcSoftware,
      xlen = 64,
      march = "rv64imc_zicsr",
      mabi = "lp64",
      privilegeModes = Set('M'),
      vmModes = Set.empty,
      pmpEntries = 0,
      compressed = true
    ),
    ExpectedProfile(
      config = CoreProfiles.rv64imasuSv39PmpSoftware,
      xlen = 64,
      march = "rv64ima_zicsr",
      mabi = "lp64",
      privilegeModes = Set('M', 'S', 'U'),
      vmModes = Set("Sv39"),
      pmpEntries = 16,
      compressed = false
    )
  )

  it should "freeze named RV32 and RV64 support points without implying an arbitrary cross-product" in {
    representativeProfiles.foreach { expected =>
      val isa = expected.config.isa
      isa.xlen shouldBe expected.xlen
      isa.march shouldBe expected.march
      isa.mabi shouldBe expected.mabi
      isa.privilegeModes shouldBe expected.privilegeModes
      isa.virtualMemoryModes shouldBe expected.vmModes
      isa.pmpEntries shouldBe expected.pmpEntries
      isa.hasC shouldBe expected.compressed
    }
  }

  it should "keep current integer ABI ownership separate from the ISA extension set" in {
    CoreProfiles.rv32imcSoftware.isa.mabi shouldBe "ilp32"
    CoreProfiles.rv32imacsuSv32PmpSoftware.isa.mabi shouldBe "ilp32"
    CoreProfiles.rv64imCurrent.isa.mabi shouldBe "lp64"
    CoreProfiles.rv64imcSoftware.isa.mabi shouldBe "lp64"
    CoreProfiles.rv64imasuSv39PmpSoftware.isa.mabi shouldBe "lp64"

    representativeProfiles.foreach { expected =>
      expected.config.isa.extensions.contains('F') shouldBe false
      expected.config.isa.extensions.contains('D') shouldBe false
    }
  }

  it should "keep VM geometry paired with the correct XLEN in the qualified application profiles" in {
    val rv32 = CoreProfiles.rv32imacsuSv32PmpSoftware
    rv32.isa.hasSv32 shouldBe true
    rv32.isa.hasSv39 shouldBe false
    rv32.platform.paddrBits shouldBe 34

    val rv64 = CoreProfiles.rv64imasuSv39PmpSoftware
    rv64.isa.hasSv32 shouldBe false
    rv64.isa.hasSv39 shouldBe true
    rv64.platform.paddrBits shouldBe 56
  }

  it should "keep PMP bounded to the qualified PMP16 application surface" in {
    CoreProfiles.rv32imacsuSv32PmpSoftware.isa.pmpEntries shouldBe 16
    CoreProfiles.rv64imasuSv39PmpSoftware.isa.pmpEntries shouldBe 16
  }

  it should "publish RV64C only at the compiler-qualified machine profile boundary" in {
    val rv64c = CoreProfiles.rv64imcSoftware
    rv64c.name shouldBe "rv64imc-software"
    rv64c.isa.hasC shouldBe true
    rv64c.isa.march shouldBe "rv64imc_zicsr"
    rv64c.isa.mabi shouldBe "lp64"
    rv64c.isa.privilegeModes shouldBe Set('M')
    rv64c.isa.virtualMemoryModes shouldBe empty
    rv64c.isa.pmpEntries shouldBe 0

    // Keep the older RV64 machine point and the Linux/OpenSBI-class profile
    // conservative. C is not promoted across privilege/VM/PMP axes merely
    // because the machine-mode compiler workload is now qualified.
    CoreProfiles.rv64imCurrent.isa.hasC shouldBe false
    CoreProfiles.rv64imasuSv39PmpSoftware.isa.hasC shouldBe false
  }
}
