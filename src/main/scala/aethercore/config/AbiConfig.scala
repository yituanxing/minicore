package aethercore.config

/**
  * Compiler/userspace ABI description, deliberately separate from IsaConfig.
  *
  * XLEN and ISA extensions describe architectural execution semantics. An ABI
  * describes how software uses that architecture: data model, calling
  * convention and (for F/D ABIs) whether floating-point argument registers are
  * part of the calling convention. Keeping this as a distinct type prevents a
  * hardware ISA description from silently choosing a software ABI.
  */
final case class AbiConfig(
    name: String,
    xlen: Int,
    requiredExtensions: Set[Char] = Set.empty
) {
  require(name.nonEmpty, "ABI name must not be empty")
  require(xlen == 32 || xlen == 64, s"ABI XLEN must be 32 or 64, got $xlen")

  def validateAgainst(isa: IsaConfig): Unit = {
    require(
      isa.xlen == xlen,
      s"ABI $name requires RV$xlen but ISA target is RV${isa.xlen}"
    )
    require(
      requiredExtensions.subsetOf(isa.extensions),
      s"ABI $name requires ISA extensions $requiredExtensions, got ${isa.extensions}"
    )
  }
}

/** Canonical standard RISC-V ABI identities currently relevant to AetherCore. */
object AbiProfiles {
  val ilp32: AbiConfig = AbiConfig("ilp32", 32)
  val ilp32f: AbiConfig = AbiConfig("ilp32f", 32, Set('F'))
  val ilp32d: AbiConfig = AbiConfig("ilp32d", 32, Set('F', 'D'))

  val lp64: AbiConfig = AbiConfig("lp64", 64)
  val lp64f: AbiConfig = AbiConfig("lp64f", 64, Set('F'))
  val lp64d: AbiConfig = AbiConfig("lp64d", 64, Set('F', 'D'))
}

/**
  * Compiler-facing ISA + ABI pair.
  *
  * This validates only architectural/ABI compatibility. It deliberately does
  * not claim that the current AetherCore RTL implements every ISA accepted by
  * IsaConfig; CoreConfig/AetherCoreCapabilities remain the separate production
  * implementation gate.
  */
final case class SoftwareTarget(
    isa: IsaConfig,
    abi: AbiConfig
) {
  abi.validateAgainst(isa)

  val march: String = isa.march
  val mabi: String = abi.name
}
