package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util._

/**
  * Small correctness-first fully-associative translation cache shared by the
  * standard Sv32/Sv39/Sv48 geometries.
  *
  * ASIDLEN is currently zero in the production profiles, so the active root PPN
  * remains part of the tag. The complete access context is also tagged to avoid
  * reusing a translation across privilege or permission checks. This is more
  * conservative than a permission-aware TLB but intentionally simple to audit.
  *
  * Leaf level is architectural page-table level: level 0 is a 4 KiB page, each
  * higher level replaces one additional low PPN component with the matching VPN
  * component. The same rule covers Sv32 megapages, Sv39 super/gigapages and the
  * additional Sv48 terapage level without mode-specific data paths.
  *
  * 共享 TLB 只依赖 PageTableGeometry。不同虚拟内存模式不复制 tag、refill、
  * superpage 命中和物理地址重建逻辑。
  */
class TranslationTlb(
    val geometry: PageTableGeometry,
    val entries: Int = 8,
    val implementedPaddrBits: Int = -1
) extends Module {
  require(entries >= 2 && isPow2(entries), s"translation TLB entries must be a power of two >= 2, got $entries")

  private val Xlen = geometry.xlen
  private val PaddrBits = geometry.architecturalPhysicalAddressBits
  private val StoredPaddrBits =
    if (implementedPaddrBits > 0) math.min(implementedPaddrBits, PaddrBits) else PaddrBits
  private val MaxPageBits =
    geometry.pageOffsetBits + (geometry.levels - 1) * geometry.vpnBitsPerLevel
  require(
    StoredPaddrBits > MaxPageBits,
    s"stored TLB PA width must exceed largest page offset ($MaxPageBits), got $StoredPaddrBits"
  )
  private val PpnBits = geometry.ppnBits
  private val VpnBits = geometry.vpnBits
  private val LevelBits = math.max(1, log2Ceil(geometry.levels))
  private val IndexBits = log2Ceil(entries)

  class Entry extends Bundle {
    val valid = Bool()
    val rootPpn = UInt(PpnBits.W)
    val vpn = UInt(VpnBits.W)
    val physicalBase = UInt(StoredPaddrBits.W)
    val privilege = UInt(2.W)
    val write = Bool()
    val execute = Bool()
    val sum = Bool()
    val mxr = Bool()
    val leafLevel = UInt(LevelBits.W)
    val global = Bool()
  }

  val io = IO(new Bundle {
    val lookupValid = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val rootPpn = Input(UInt(PpnBits.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val hit = Output(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val leafLevel = Output(UInt(LevelBits.W))
    val global = Output(Bool())

    val refillValid = Input(Bool())
    val refillVirtualAddress = Input(UInt(Xlen.W))
    val refillPhysicalAddress = Input(UInt(PaddrBits.W))
    val refillRootPpn = Input(UInt(PpnBits.W))
    val refillPrivilege = Input(UInt(2.W))
    val refillWrite = Input(Bool())
    val refillExecute = Input(Bool())
    val refillSum = Input(Bool())
    val refillMxr = Input(Bool())
    val refillLeafLevel = Input(UInt(LevelBits.W))
    val refillGlobal = Input(Bool())

    val flush = Input(Bool())
  })

  val table = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val replacement = RegInit(0.U(IndexBits.W))

  val lookupVpn = io.virtualAddress(geometry.vaBits - 1, geometry.pageOffsetBits)
  val matches = Wire(Vec(entries, Bool()))

  for (i <- 0 until entries) {
    val entry = table(i)
    val vpnMatch = WireDefault(false.B)

    for (leaf <- 0 until geometry.levels) {
      val ignoredVpnBits = leaf * geometry.vpnBitsPerLevel
      when(entry.leafLevel === leaf.U) {
        if (ignoredVpnBits == 0) {
          vpnMatch := entry.vpn === lookupVpn
        } else {
          vpnMatch := entry.vpn(VpnBits - 1, ignoredVpnBits) === lookupVpn(VpnBits - 1, ignoredVpnBits)
        }
      }
    }

    matches(i) := io.lookupValid && entry.valid &&
      entry.rootPpn === io.rootPpn && vpnMatch &&
      entry.privilege === io.privilege &&
      entry.write === io.write && entry.execute === io.execute &&
      entry.sum === io.sum && entry.mxr === io.mxr
  }

  io.hit := matches.asUInt.orR
  val hitIndex = PriorityEncoder(matches.asUInt)
  val hitEntry = table(hitIndex)
  io.leafLevel := Mux(io.hit, hitEntry.leafLevel, 0.U)
  io.global := io.hit && hitEntry.global

  val hitPhysicalAddress = WireDefault(0.U(PaddrBits.W))
  for (leaf <- 0 until geometry.levels) {
    val pageBits = geometry.pageOffsetBits + leaf * geometry.vpnBitsPerLevel
    when(hitEntry.leafLevel === leaf.U) {
      val storedPhysicalAddress = Cat(
        hitEntry.physicalBase(StoredPaddrBits - 1, pageBits),
        io.virtualAddress(pageBits - 1, 0)
      )
      hitPhysicalAddress := storedPhysicalAddress.pad(PaddrBits)
    }
  }
  io.physicalAddress := Mux(io.hit, hitPhysicalAddress, 0.U)

  val invalidMask = VecInit(table.map(entry => !entry.valid)).asUInt
  val hasInvalid = invalidMask.orR
  val refillIndex = Mux(hasInvalid, PriorityEncoder(invalidMask), replacement)

  // A narrower platform never caches an architectural PA that would be
  // rejected at the implementation boundary.  Such a translation remains a
  // miss, so the walker response reaches the existing fail-closed range check
  // without ever aliasing into the compact TLB payload.
  val refillOutOfRange =
    if (StoredPaddrBits >= PaddrBits) false.B
    else io.refillPhysicalAddress(PaddrBits - 1, StoredPaddrBits).orR

  val refillBase = WireDefault(0.U(StoredPaddrBits.W))
  for (leaf <- 0 until geometry.levels) {
    val pageBits = geometry.pageOffsetBits + leaf * geometry.vpnBitsPerLevel
    when(io.refillLeafLevel === leaf.U) {
      refillBase := Cat(
        io.refillPhysicalAddress(StoredPaddrBits - 1, pageBits),
        0.U(pageBits.W)
      )
    }
  }

  when(io.flush) {
    for (i <- 0 until entries) table(i).valid := false.B
    replacement := 0.U
  }.elsewhen(io.refillValid && !refillOutOfRange) {
    val entry = table(refillIndex)
    entry.valid := true.B
    entry.rootPpn := io.refillRootPpn
    entry.vpn := io.refillVirtualAddress(geometry.vaBits - 1, geometry.pageOffsetBits)
    entry.physicalBase := refillBase
    entry.privilege := io.refillPrivilege
    entry.write := io.refillWrite
    entry.execute := io.refillExecute
    entry.sum := io.refillSum
    entry.mxr := io.refillMxr
    entry.leafLevel := io.refillLeafLevel
    entry.global := io.refillGlobal
    replacement := refillIndex + 1.U
  }
}
