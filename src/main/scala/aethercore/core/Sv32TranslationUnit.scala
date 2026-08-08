package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode

/** Composes Sv32 activation rules, a bounded TLB, and the two-level walker. */
class Sv32TranslationUnit(val tlbEntries: Int = 8) extends Module {
  private val PaddrBits = 34

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val kill = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(32.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val execute = Input(Bool())

    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(Sv32Satp.PpnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PaddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(32.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(PaddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
    val leafLevel = Output(UInt(1.W))
    val global = Output(Bool())
  })

  val idle :: walking :: bypassResponse :: tlbResponse :: Nil = Enum(4)
  val state = RegInit(idle)
  val bypassPhysicalAddress = RegInit(0.U(PaddrBits.W))
  val cachedPhysicalAddress = RegInit(0.U(PaddrBits.W))
  val cachedLeafLevel = RegInit(0.U(1.W))
  val cachedGlobal = RegInit(false.B)

  // A miss keeps the accepted architectural context stable until the walker
  // response is consumed and committed into the TLB.
  val requestVirtualAddress = Reg(UInt(32.W))
  val requestRootPpn = Reg(UInt(Sv32Satp.PpnBits.W))
  val requestPrivilege = Reg(UInt(2.W))
  val requestWrite = Reg(Bool())
  val requestExecute = Reg(Bool())
  val requestSum = Reg(Bool())
  val requestMxr = Reg(Bool())

  val walker = Module(new Sv32PageTableWalker)
  val tlb = Module(new Sv32Tlb(tlbEntries))
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

  walker.io.requestValid := lookupActive && !tlb.io.hit
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
  io.requestReady := state === idle && !abort && Mux(
    translationRequired,
    Mux(tlb.io.hit, true.B, walker.io.requestReady),
    true.B
  )

  io.responseValid := false.B
  io.physicalAddress := 0.U
  io.pageFault := false.B
  io.accessFault := false.B
  io.leafLevel := 0.U
  io.global := false.B

  when(state === walking) {
    io.responseValid := walker.io.responseValid && !abort
    io.physicalAddress := walker.io.physicalAddress
    io.pageFault := walker.io.pageFault
    io.accessFault := walker.io.accessFault
    io.leafLevel := walker.io.leafLevel
    io.global := walker.io.global
  }.elsewhen(state === bypassResponse) {
    io.responseValid := !abort
    io.physicalAddress := bypassPhysicalAddress
  }.elsewhen(state === tlbResponse) {
    io.responseValid := !abort
    io.physicalAddress := cachedPhysicalAddress
    io.leafLevel := cachedLeafLevel
    io.global := cachedGlobal
  }

  when(abort) {
    state := idle
    bypassPhysicalAddress := 0.U
    cachedPhysicalAddress := 0.U
    cachedLeafLevel := 0.U
    cachedGlobal := false.B
  }.elsewhen(state === idle && io.requestValid && io.requestReady) {
    when(translationRequired) {
      when(tlb.io.hit) {
        cachedPhysicalAddress := tlb.io.physicalAddress
        cachedLeafLevel := tlb.io.leafLevel
        cachedGlobal := tlb.io.global
        state := tlbResponse
      }.otherwise {
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
      bypassPhysicalAddress := Cat(0.U(2.W), io.virtualAddress)
      state := bypassResponse
    }
  }.elsewhen(state === walking && walker.io.responseValid && io.responseReady) {
    state := idle
  }.elsewhen(state === bypassResponse && io.responseReady) {
    state := idle
  }.elsewhen(state === tlbResponse && io.responseReady) {
    state := idle
  }
}
