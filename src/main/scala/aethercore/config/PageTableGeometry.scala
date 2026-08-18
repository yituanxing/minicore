package aethercore.config

/**
  * Architectural geometry for one paged virtual-memory mode.
  *
  * This object owns only specification-level shape: XLEN, satp MODE encoding,
  * virtual-address width, page-table depth, VPN partitioning, PTE width, PPN
  * width and architectural ASID field width. It deliberately does not claim
  * that AetherCore already implements every described mode.
  *
  * 页表模式的架构几何描述。这里仅描述规范层面的形状，不等同于 AetherCore
  * 已经实现该模式；具体硬件能力仍由 CoreConfig fail-closed 边界决定。
  */
final case class PageTableGeometry(
    name: String,
    xlen: Int,
    satpMode: Int,
    vaBits: Int,
    levels: Int,
    vpnBitsPerLevel: Int,
    pteBytes: Int,
    ppnBits: Int,
    asidBits: Int
) {
  val pageOffsetBits: Int = 12
  val vpnBits: Int = vaBits - pageOffsetBits
  val pteBits: Int = pteBytes * 8
  val architecturalPhysicalAddressBits: Int = ppnBits + pageOffsetBits

  require(name.nonEmpty, "virtual-memory mode name must not be empty")
  require(xlen == 32 || xlen == 64, s"virtual-memory XLEN must be 32 or 64, got $xlen")
  require(satpMode > 0, s"paged virtual-memory satp MODE must be nonzero, got $satpMode")
  require(vaBits > pageOffsetBits && vaBits <= xlen, s"invalid virtual-address width $vaBits for XLEN=$xlen")
  require(levels > 0, s"page-table level count must be positive, got $levels")
  require(vpnBitsPerLevel > 0, s"VPN bits per level must be positive, got $vpnBitsPerLevel")
  require(
    vpnBits == levels * vpnBitsPerLevel,
    s"VA/VPN geometry mismatch for $name: vpnBits=$vpnBits levels=$levels vpnBitsPerLevel=$vpnBitsPerLevel"
  )
  require(pteBytes == 4 || pteBytes == 8, s"PTE width must be 4 or 8 bytes, got $pteBytes")
  require(pteBits == xlen, s"current standard page-table modes require PTE width to match XLEN: $name")
  require(ppnBits > 0, s"PPN width must be positive, got $ppnBits")
  require(asidBits >= 0, s"ASID field width must be non-negative, got $asidBits")
}

object PageTableGeometry {
  /** Frozen RV32 Sv32 shape: two 10-bit VPN levels and 32-bit PTEs. */
  val Sv32: PageTableGeometry = PageTableGeometry(
    name = "Sv32",
    xlen = 32,
    satpMode = 1,
    vaBits = 32,
    levels = 2,
    vpnBitsPerLevel = 10,
    pteBytes = 4,
    ppnBits = 22,
    asidBits = 9
  )

  /** RV64 Sv39 shape: three 9-bit VPN levels and 64-bit PTEs. */
  val Sv39: PageTableGeometry = PageTableGeometry(
    name = "Sv39",
    xlen = 64,
    satpMode = 8,
    vaBits = 39,
    levels = 3,
    vpnBitsPerLevel = 9,
    pteBytes = 8,
    ppnBits = 44,
    asidBits = 16
  )

  /** RV64 Sv48 shape: Sv39 PTE/PPN format with one additional VPN level. */
  val Sv48: PageTableGeometry = PageTableGeometry(
    name = "Sv48",
    xlen = 64,
    satpMode = 9,
    vaBits = 48,
    levels = 4,
    vpnBitsPerLevel = 9,
    pteBytes = 8,
    ppnBits = 44,
    asidBits = 16
  )

  val standardModes: Seq[PageTableGeometry] = Seq(Sv32, Sv39, Sv48)
  private val byName: Map[String, PageTableGeometry] = standardModes.map(mode => mode.name -> mode).toMap

  def named(name: String): Option[PageTableGeometry] = byName.get(name)

  /**
    * Validate an architectural capability set without claiming production
    * implementation. Sv48 requires Sv39 to be present as well.
    */
  def validateArchitecturalModes(
      xlen: Int,
      privilegeModes: Set[Char],
      modeNames: Set[String]
  ): Set[PageTableGeometry] = {
    val unknown = modeNames -- byName.keySet
    require(unknown.isEmpty, s"unsupported virtual-memory mode set: $modeNames")

    val modes = modeNames.map(byName)
    require(modes.isEmpty || privilegeModes.contains('S'), "paged virtual memory requires Supervisor mode")
    require(
      modes.forall(_.xlen == xlen),
      s"virtual-memory modes $modeNames are incompatible with RV$xlen"
    )
    require(
      !modeNames.contains("Sv48") || modeNames.contains("Sv39"),
      "Sv48 support requires Sv39 support"
    )
    modes
  }
}
