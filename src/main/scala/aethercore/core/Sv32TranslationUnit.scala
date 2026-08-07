package aethercore.core

import chisel3._
import chisel3.util._
import aethercore.common.PrivilegeMode

/**
  * Composes the qualified Sv32 walker with the privilege/satp activation rule.
  *
  * This is intentionally still a pipeline-independent block: M-mode and Bare
  * accesses bypass translation, while S/U accesses under satp.MODE=Sv32 are
  * handed to the two-level walker. The only memory traffic emitted by this
  * unit is the walker's read-only implicit PTE port.
  *
  * No TLB is present yet. Consequently every translated access walks the page
  * tables, and there is no SFENCE.VMA state to invalidate in this slice.
  */
class Sv32TranslationUnit extends Module {
  private val PaddrBits = 34

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
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

  val idle :: walking :: bypassResponse :: Nil = Enum(3)
  val state = RegInit(idle)
  val bypassPhysicalAddress = RegInit(0.U(PaddrBits.W))

  val walker = Module(new Sv32PageTableWalker)
  val translationRequired =
    io.satpTranslationEnabled && io.privilege =/= PrivilegeMode.Machine.U

  walker.io.requestValid :=
    state === idle && io.requestValid && translationRequired
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
  walker.io.responseReady := state === walking && io.responseReady

  io.pteValid := walker.io.pteValid
  io.pteAddress := walker.io.pteAddress

  io.requestReady := state === idle &&
    Mux(translationRequired, walker.io.requestReady, true.B)

  io.responseValid := false.B
  io.physicalAddress := 0.U
  io.pageFault := false.B
  io.accessFault := false.B
  io.leafLevel := 0.U
  io.global := false.B

  when(state === walking) {
    io.responseValid := walker.io.responseValid
    io.physicalAddress := walker.io.physicalAddress
    io.pageFault := walker.io.pageFault
    io.accessFault := walker.io.accessFault
    io.leafLevel := walker.io.leafLevel
    io.global := walker.io.global
  }.elsewhen(state === bypassResponse) {
    io.responseValid := true.B
    io.physicalAddress := bypassPhysicalAddress
  }

  when(state === idle && io.requestValid && io.requestReady) {
    when(translationRequired) {
      state := walking
    }.otherwise {
      bypassPhysicalAddress := Cat(0.U(2.W), io.virtualAddress)
      state := bypassResponse
    }
  }.elsewhen(state === walking && walker.io.responseValid && io.responseReady) {
    state := idle
  }.elsewhen(state === bypassResponse && io.responseReady) {
    state := idle
  }
}
