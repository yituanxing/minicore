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
    integerWritePorts: Int,
    floatingPhysicalRegisters: Int = 48,
    floatingReadPorts: Int = 3,
    floatingWritePorts: Int = 1
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
  // Geometry is independent of the ISA switch: these banks are instantiated
  // only when the selected Product software target enables F/D.  In
  // particular, FMA has three FP operands even on a one-wide issue path.
  require(floatingPhysicalRegisters > 32,
    "floating PRF requires the architectural FPR mapping plus a spare destination")
  // ProductIssue/Completion currently encode both destination banks with
  // log2Ceil(integerPhysicalRegisters) bits. The banks are independent, but
  // permitting a larger FPR bank would truncate upper FP physical tags and
  // could silently retire a result into the wrong register after rename.
  // Widen that shared tag type before proposing an FPR bank larger than XPR.
  require(floatingPhysicalRegisters <= integerPhysicalRegisters,
    "FP PRF exceeds the currently implemented shared physical-tag width")
  require(floatingReadPorts >= 3,
    "one FP fused multiply-add needs three physical source operands")
  require(floatingWritePorts >= 1, "a floating destination requires a write port")

  /** Architectural correctness does not require a 32+ROB sized PRF:
    * a smaller PRF must backpressure rename when exhausted. This flag is only
    * a potential throughput warning, not a hard validation failure.
    */
  val freeRegisterPressureBeforeFullRob: Boolean = integerPhysicalRegisters < 32 + robEntries
  val freeFloatingRegisterPressureBeforeFullRob: Boolean =
    floatingPhysicalRegisters < 32 + robEntries
}

/** Three independent Product choices: RTL geometry, software ISA/ABI, board.
  *
  * An elaboratable combination does not imply that its instruction semantics
  * have passed differentials, that Debian PID1 reached a milestone, or that a
  * physical FPGA board has passed its DDR/SD timing and boot qualification.
  * Keep those statements independent: a target ISA/ABI can be requested
  * before it is software-qualified, without pretending the RTL is absent.
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

  /** The instantiated Product datapath currently has one integer issue lane,
    * two XPR read ports, one XPR write port, three FPR read ports and one FPR
    * write port. These geometry fields describe *physical* ports, so a caller
    * must not obtain a true RTL implementation gate merely by requesting a
    * larger port count that the actual backend silently ignores.
    *
    * Likewise, IsaConfig permits future extensions and VM modes for software
    * planning, but this Product implementation decodes only IMAFDC, Zicsr,
    * Zifencei, M/S/U and Sv39. Its CSR/PMP bank implements at most PMP16;
    * the data resolver narrows Sv39 physical addresses to at most 56 bits;
    * the board's external memory seam is fixed at 64 data bits. Keep the
    * software target legal to describe, yet distinguish it from RTL that can
    * faithfully implement the requested features.
    *
    * This is a static compatibility predicate, never evidence of Debian,
    * simulation, timing or physical-board qualification.
    */
  val rtlElaboratable: Boolean = geometry.decodeWidth == 1 &&
    geometry.dispatchWidth == 1 && geometry.issueWidth == 1 &&
    geometry.retireWidth == 1 &&
    geometry.integerReadPorts == 2 && geometry.integerWritePorts == 1 &&
    geometry.floatingReadPorts == 3 && geometry.floatingWritePorts == 1 &&
    software.isa.extensions.subsetOf(Set('I', 'M', 'A', 'F', 'D', 'C')) &&
    software.isa.zExtensions.subsetOf(Set("Zicsr", "Zifencei")) &&
    software.isa.privilegeModes == Set('M', 'S', 'U') &&
    software.isa.virtualMemoryModes == Set("Sv39") &&
    software.isa.pmpEntries <= 16 &&
    platform.paddrBits >= 32 && platform.paddrBits <= 56 &&
    platform.busDataBits == 64

  /** Hardware bring-up and distro qualification are separate, explicit axes.
    * All remain false until an exact image/board/run ID is recorded; the
    * Product SmallOoO RTL existing is not evidence of D1/D2/D3/D4 success.
    */
  val qualifiedDebianD1: Boolean = false
  val qualifiedDebianD2: Boolean = false
  val qualifiedDebianD3: Boolean = false
  val qualifiedFpgaDistributionD4: Boolean = false

  // Deprecated compatibility flag from the G1 proposal. Its historical
  // meaning was "fully verified Product RTL", not "does Chisel RTL exist".
  // Keep strict-false to avoid silently upgrading old qualified test gates;
  // new code must use rtlElaboratable and explicit milestone qualification.
  @deprecated("Use rtlElaboratable or the explicit Debian/FPGA qualification flags", "Product G5")
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
  // A second *implemented* capacity of the same one-wide RTL, used for FPGA
  // cost A/B. The Medium/Large two-wide profiles below are still future RTL.
  val expandedSingleLaneGeometry: ProductCoreGeometry = smallGeometry.copy(
    robEntries = 16, iqEntries = 8, integerPhysicalRegisters = 64,
    loadQueueEntries = 4, storeQueueEntries = 4,
    floatingPhysicalRegisters = 64
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

  /** Upstream Debian riscv64 target: Product RTL exists but distro boot is not yet qualified. */
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
