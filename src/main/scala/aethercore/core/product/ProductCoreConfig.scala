package aethercore.core.product

import aethercore.config.{AbiConfig, AbiProfiles, CoreProfiles, IsaConfig, PlatformConfig, SoftwareTarget}

/** Elaborated geometry is independent of ISA/ABI and of the FPGA board.
  *
  * This is a design-time description, NOT an instantiated ProductCore.
  * The same backend must implement all *qualified* geometries; an unsupported
  * hardware feature must never become available merely by setting a field.
  */
final case class ProductCoreGeometry(
    decodeWidth: Int,
    dispatchWidth: Int,
    issueWidth: Int,
    retireWidth: Int,
    robEntries: Int,
    iqEntries: Int,
    loadQueueEntries: Int,
    storeQueueEntries: Int,
    integerPhysicalRegisters: Int,
    integerReadPorts: Int,
    integerWritePorts: Int
) {
  require(Set(1, 2).contains(decodeWidth), "initial product decode width must be 1 or 2")
  require(dispatchWidth > 0 && dispatchWidth <= decodeWidth, "dispatch exceeds decode width")
  require(issueWidth > 0 && issueWidth <= dispatchWidth, "issue exceeds dispatch width")
  require(retireWidth > 0 && retireWidth <= dispatchWidth, "retire exceeds dispatch width")
  require(robEntries >= 4 && (robEntries & (robEntries - 1)) == 0, "ROB depth must be a power of two >= 4")
  require(robEntries >= dispatchWidth && robEntries % dispatchWidth == 0, "ROB depth must support whole dispatch groups")
  require(iqEntries >= issueWidth && iqEntries >= dispatchWidth, "IQ cannot hold a full dispatch group")
  require(loadQueueEntries >= 1 && storeQueueEntries >= 1, "both LoadQ and StoreQ must exist")
  require(integerPhysicalRegisters > 32, "integer PRF requires the architectural mapping plus a spare destination")
  require(integerReadPorts >= issueWidth * 2, "one 2-source integer issue requires two PRF reads")
  require(integerWritePorts >= issueWidth, "integer PRF write bandwidth must cover issue width")

  /** Architectural correctness does not require a 32+ROB sized PRF:
    * a smaller PRF must backpressure rename when exhausted. This flag is only
    * a potential throughput warning, not a hard validation failure.
    */
  val freeRegisterPressureBeforeFullRob: Boolean = integerPhysicalRegisters < 32 + robEntries
}

/** A target ISA/ABI can exist before the RTL implementing it.
  *
  * IsaConfig is an architectural request. Do not wrap a requested F/D ISA
  * in the existing CoreConfig, which correctly rejects unsupported legacy RTL.
  */
final case class ProductCoreConfig(
    name: String,
    geometry: ProductCoreGeometry,
    software: SoftwareTarget,
    platform: PlatformConfig
) {
  require(name.nonEmpty, "product configuration requires a name")
  require(software.isa.xlen == 64, "this product line targets RV64")
  require(software.isa.privilegeModes.contains('S') && software.isa.privilegeModes.contains('U'),
    "full distribution target requires M/S/U modes")
  require(software.isa.hasSv39, "product profile requires Sv39")
  require(!software.isa.extensions.contains('D') || software.isa.extensions.contains('F'),
    "D requires F")

  /** Product RTL has not yet been implemented; never treat a preset as proof. */
  val rtlImplemented: Boolean = false
  def march: String = software.march
  def mabi: String = software.mabi
}

object ProductCoreProfiles {
  // Keep the existing, verified platform numbers while hardware ownership
  // transitions; a concrete FPGA board can choose a separate PlatformConfig.
  private val reference = CoreProfiles.rv64imasuSv39PmpSoftware

  val smallGeometry = ProductCoreGeometry(
    decodeWidth = 1, dispatchWidth = 1, issueWidth = 1, retireWidth = 1,
    robEntries = 8, iqEntries = 4, loadQueueEntries = 2, storeQueueEntries = 2,
    integerPhysicalRegisters = 48, integerReadPorts = 2, integerWritePorts = 1
  )
  val mediumGeometry = ProductCoreGeometry(
    decodeWidth = 2, dispatchWidth = 2, issueWidth = 1, retireWidth = 2,
    robEntries = 16, iqEntries = 8, loadQueueEntries = 4, storeQueueEntries = 4,
    integerPhysicalRegisters = 64, integerReadPorts = 2, integerWritePorts = 1
  )
  val largeGeometry = ProductCoreGeometry(
    decodeWidth = 2, dispatchWidth = 2, issueWidth = 2, retireWidth = 2,
    robEntries = 64, iqEntries = 16, loadQueueEntries = 16, storeQueueEntries = 16,
    integerPhysicalRegisters = 96, integerReadPorts = 4, integerWritePorts = 2
  )

  /** Engineering target for retiring an actual SmallOoO RTL before F/D. */
  val engineeringSoftware: SoftwareTarget =
    SoftwareTarget(
      reference.isa.copy(zExtensions = reference.isa.zExtensions + "Zifencei"),
      AbiProfiles.lp64
    )

  // Real C-capable Product hardware may run soft-float LP64 software before
  // the F/D state and execution pipeline is implemented. Keep this distinct
  // from the requested RV64GC/LP64D distro target; ISA support is not implied
  // by a geometry or by a software profile name.
  val compressedEngineeringSoftware: SoftwareTarget =
    SoftwareTarget(
      engineeringSoftware.isa.copy(extensions = engineeringSoftware.isa.extensions + 'C'),
      AbiProfiles.lp64
    )

  /** Upstream Debian riscv64 target, not yet implemented by either ProductCore or v2. */
  val distroSoftware: SoftwareTarget =
    SoftwareTarget(
      reference.isa.copy(
        extensions = Set('I', 'M', 'A', 'F', 'D', 'C'),
        zExtensions = reference.isa.zExtensions ++ Set("Zicsr", "Zifencei")
      ),
      AbiProfiles.lp64d
    )

  def configure(name: String, geometry: ProductCoreGeometry, target: SoftwareTarget): ProductCoreConfig =
    ProductCoreConfig(name, geometry, target, reference.platform)

  val smallEngineering: ProductCoreConfig = configure("small-ima-engineering", smallGeometry, engineeringSoftware)
  val smallCompressedEngineering: ProductCoreConfig =
    configure("small-imac-engineering", smallGeometry, compressedEngineeringSoftware)
  val smallDebianTarget: ProductCoreConfig = configure("small-rv64gc-debian-target", smallGeometry, distroSoftware)
  val mediumDebianTarget: ProductCoreConfig = configure("medium-rv64gc-debian-target", mediumGeometry, distroSoftware)
  val largeDebianTarget: ProductCoreConfig = configure("large-rv64gc-debian-target", largeGeometry, distroSoftware)
}
