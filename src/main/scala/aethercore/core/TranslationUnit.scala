package aethercore.core

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util._

/**
  * Correctness-first translation-unit composition shared by one paged VM mode.
  *
  * PageTableGeometry owns the architectural shape; PageTableWalker owns the
  * traversal; TranslationTlb owns cached translations. This layer owns only the
  * request/response state machine, bypass semantics and TLB refill lifecycle.
  *
  * A bare or Machine-mode request still crosses the architectural-to-physical
  * address boundary. Discarded high address bits therefore produce an access
  * fault instead of silently aliasing a narrower physical-address domain.
  *
  * 共享地址翻译单元只负责组合状态机、bypass 与 TLB refill。页表模式差异由
  * PageTableGeometry 描述，walker/TLB 不复制。即使 bypass，也必须经过物理地址
  * 收窄边界；被丢弃的高位产生 access fault，禁止静默回绕。
  */
class TranslationUnit(
    val geometry: PageTableGeometry,
    val tlbEntries: Int = 8,
    val externalWalkGate: Boolean = false
) extends Module {
  private val Xlen = geometry.xlen
  private val PaddrBits = geometry.architecturalPhysicalAddressBits
  private val PpnBits = geometry.ppnBits
  private val LevelBits = math.max(1, log2Ceil(geometry.levels))

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val kill = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())

    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(PpnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val walkAllowed = if (externalWalkGate) Some(Input(Bool())) else None

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PaddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
    val leafLevel = Output(UInt(LevelBits.W))
    val global = Output(Bool())
  })

  val idle :: walking :: bypassResponse :: Nil = Enum(3)
  val state = RegInit(idle)
  val bypassPhysicalAddress = RegInit(0.U(PaddrBits.W))
  val bypassAccessFault = RegInit(false.B)

  // Keep the accepted architectural context stable across a miss until the
  // walker response is consumed and refilled into the TLB.
  val requestVirtualAddress = Reg(UInt(Xlen.W))
  val requestRootPpn = Reg(UInt(PpnBits.W))
  val requestPrivilege = Reg(UInt(2.W))
  val requestWrite = Reg(Bool())
  val requestExecute = Reg(Bool())
  val requestSum = Reg(Bool())
  val requestMxr = Reg(Bool())

  val walker = Module(new PageTableWalker(geometry))
  val tlb = Module(new TranslationTlb(geometry, tlbEntries))
  val abort = io.kill || io.flush
  val translationRequired =
    io.satpTranslationEnabled && io.privilege =/= PrivilegeMode.Machine.U
  val lookupActive = state === idle && io.requestValid && translationRequired && !abort

  tlb.io.lookupValid := lookupActive
  tlb.io.virtualAddress := io.virtualAddress
  tlb.io.rootPpn := io.satpRootPpn
  tlb.io.privilege := io.privilege
  tlb.io.write := io.write
  tlb.io.execute := io.execute
  tlb.io.sum := io.sum
  tlb.io.mxr := io.mxr
  tlb.io.flush := io.flush

  val refill = state === walking && walker.io.responseValid && io.responseReady &&
    !walker.io.pageFault && !walker.io.accessFault && !abort
  tlb.io.refillValid := refill
  tlb.io.refillVirtualAddress := requestVirtualAddress
  tlb.io.refillPhysicalAddress := walker.io.physicalAddress
  tlb.io.refillRootPpn := requestRootPpn
  tlb.io.refillPrivilege := requestPrivilege
  tlb.io.refillWrite := requestWrite
  tlb.io.refillExecute := requestExecute
  tlb.io.refillSum := requestSum
  tlb.io.refillMxr := requestMxr
  tlb.io.refillLeafLevel := walker.io.leafLevel
  tlb.io.refillGlobal := walker.io.global

  val walkAllowed = if (externalWalkGate) io.walkAllowed.get else true.B
  walker.io.requestValid := lookupActive && !tlb.io.hit && walkAllowed
  walker.io.kill := abort
  walker.io.virtualAddress := io.virtualAddress
  walker.io.rootPpn := io.satpRootPpn
  walker.io.privilege := io.privilege
  walker.io.write := io.write
  walker.io.execute := io.execute
  walker.io.sum := io.sum
  walker.io.mxr := io.mxr
  walker.io.pteReady := io.pteReady
  walker.io.pteData := io.pteData
  walker.io.pteFault := io.pteFault
  walker.io.responseReady := state === walking && io.responseReady && !abort

  io.pteValid := walker.io.pteValid && !abort
  io.pteAddress := walker.io.pteAddress
  // A TLB hit is a combinational response. Without a response register the
  // request may only be accepted when the downstream response is accepted too;
  // backpressure therefore keeps the source request stable rather than losing it.
  io.requestReady := state === idle && !abort && Mux(
    translationRequired,
    Mux(tlb.io.hit, io.responseReady, walkAllowed && walker.io.requestReady),
    true.B
  )

  io.responseValid := false.B
  io.physicalAddress := 0.U
  io.pageFault := false.B
  io.accessFault := false.B
  io.leafLevel := 0.U
  io.global := false.B

  // Hot hit path: return the cached translation in the same cycle as lookup.
  // Miss/walk/refill and bare-address behavior remain on their existing states.
  when(lookupActive && tlb.io.hit) {
    io.responseValid := true.B
    io.physicalAddress := tlb.io.physicalAddress
    io.leafLevel := tlb.io.leafLevel
    io.global := tlb.io.global
  }.elsewhen(state === walking) {
    io.responseValid := walker.io.responseValid && !abort
    io.physicalAddress := walker.io.physicalAddress
    io.pageFault := walker.io.pageFault
    io.accessFault := walker.io.accessFault
    io.leafLevel := walker.io.leafLevel
    io.global := walker.io.global
  }.elsewhen(state === bypassResponse) {
    io.responseValid := !abort
    io.physicalAddress := bypassPhysicalAddress
    io.accessFault := bypassAccessFault
  }

  val (barePhysicalAddress, bareOutOfRange) =
    PhysicalAddressNarrowing(io.virtualAddress, PaddrBits)

  when(abort) {
    state := idle
    bypassPhysicalAddress := 0.U
    bypassAccessFault := false.B
  }.elsewhen(state === idle && io.requestValid && io.requestReady) {
    when(translationRequired) {
      // A hit is accepted and consumed entirely on this cycle; state remains idle.
      when(!tlb.io.hit) {
        requestVirtualAddress := io.virtualAddress
        requestRootPpn := io.satpRootPpn
        requestPrivilege := io.privilege
        requestWrite := io.write
        requestExecute := io.execute
        requestSum := io.sum
        requestMxr := io.mxr
        state := walking
      }
    }.otherwise {
      bypassPhysicalAddress := barePhysicalAddress
      bypassAccessFault := bareOutOfRange
      state := bypassResponse
    }
  }.elsewhen(state === walking && walker.io.responseValid && io.responseReady) {
    state := idle
  }.elsewhen(state === bypassResponse && io.responseReady) {
    state := idle
  }
}
