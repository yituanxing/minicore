package aethercore.core

import chisel3._
import chisel3.util._

/**
  * Small correctness-first fully-associative Sv32 translation cache.
  *
  * ASIDLEN is currently zero, so the active root PPN is part of the tag. The
  * first implementation deliberately includes the complete access context in
  * the tag (privilege, R/W/X kind, SUM and MXR). That may produce more misses
  * than a permission-aware production TLB, but it cannot incorrectly reuse a
  * translation across different permission checks.
  *
  * Both 4 KiB leaves and 4 MiB megapages are represented. SFENCE.VMA initially
  * drives the coarse `flush` input and invalidates every entry; selective
  * address/ASID invalidation can be added after the architectural fence path is
  * qualified.
  */
class Sv32Tlb(val entries: Int = 8) extends Module {
  require(entries > 0 && isPow2(entries), s"Sv32 TLB entries must be a positive power of two, got $entries")

  private val PaddrBits = 34
  private val PpnBits = 22
  private val VpnBits = 20
  private val IndexBits = log2Ceil(entries)

  class Entry extends Bundle {
    val valid = Bool()
    val rootPpn = UInt(PpnBits.W)
    val vpn = UInt(VpnBits.W)
    val physicalBase = UInt(PaddrBits.W)
    val privilege = UInt(2.W)
    val write = Bool()
    val execute = Bool()
    val sum = Bool()
    val mxr = Bool()
    val leafLevel = UInt(1.W)
  }

  val io = IO(new Bundle {
    val lookupValid = Input(Bool())
    val virtualAddress = Input(UInt(32.W))
    val rootPpn = Input(UInt(PpnBits.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val hit = Output(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val leafLevel = Output(UInt(1.W))

    val refillValid = Input(Bool())
    val refillVirtualAddress = Input(UInt(32.W))
    val refillPhysicalAddress = Input(UInt(PaddrBits.W))
    val refillRootPpn = Input(UInt(PpnBits.W))
    val refillPrivilege = Input(UInt(2.W))
    val refillWrite = Input(Bool())
    val refillExecute = Input(Bool())
    val refillSum = Input(Bool())
    val refillMxr = Input(Bool())
    val refillLeafLevel = Input(UInt(1.W))

    val flush = Input(Bool())
  })

  val table = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val replacement = RegInit(0.U(IndexBits.W))

  val lookupVpn = io.virtualAddress(31, 12)
  val lookupVpn1 = io.virtualAddress(31, 22)
  val matches = Wire(Vec(entries, Bool()))

  for (i <- 0 until entries) {
    val entry = table(i)
    val vpnMatch = Mux(
      entry.leafLevel === 1.U,
      entry.vpn(19, 10) === lookupVpn1,
      entry.vpn === lookupVpn
    )
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
  io.physicalAddress := Mux(
    io.hit,
    Mux(
      hitEntry.leafLevel === 1.U,
      Cat(hitEntry.physicalBase(33, 22), io.virtualAddress(21, 0)),
      Cat(hitEntry.physicalBase(33, 12), io.virtualAddress(11, 0))
    ),
    0.U
  )

  val invalidMask = VecInit(table.map(entry => !entry.valid)).asUInt
  val hasInvalid = invalidMask.orR
  val refillIndex = Mux(hasInvalid, PriorityEncoder(invalidMask), replacement)
  val refillBase = Mux(
    io.refillLeafLevel === 1.U,
    Cat(io.refillPhysicalAddress(33, 22), 0.U(22.W)),
    Cat(io.refillPhysicalAddress(33, 12), 0.U(12.W))
  )

  when(io.flush) {
    for (i <- 0 until entries) table(i).valid := false.B
    replacement := 0.U
  }.elsewhen(io.refillValid) {
    val entry = table(refillIndex)
    entry.valid := true.B
    entry.rootPpn := io.refillRootPpn
    entry.vpn := io.refillVirtualAddress(31, 12)
    entry.physicalBase := refillBase
    entry.privilege := io.refillPrivilege
    entry.write := io.refillWrite
    entry.execute := io.refillExecute
    entry.sum := io.refillSum
    entry.mxr := io.refillMxr
    entry.leafLevel := io.refillLeafLevel
    replacement := refillIndex + 1.U
  }
}
