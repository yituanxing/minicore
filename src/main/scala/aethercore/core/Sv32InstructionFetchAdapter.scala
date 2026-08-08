package aethercore.core

import chisel3._

/** Cancellable instruction-side wrapper around the qualified translation unit. */
class Sv32InstructionFetchAdapter(val paddrBits: Int = 34) extends Module {
  require(paddrBits >= 34, s"Sv32 instruction translation requires PA>=34, got $paddrBits")

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val kill = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(32.W))
    val privilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(Sv32Satp.PpnBits.W))
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(paddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(32.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(paddrBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val translation = Module(new Sv32TranslationUnit)
  translation.io.requestValid := io.requestValid
  translation.io.kill := io.kill
  translation.io.flush := io.flush
  translation.io.virtualAddress := io.virtualAddress
  translation.io.privilege := io.privilege
  translation.io.write := false.B
  translation.io.execute := true.B
  translation.io.satpTranslationEnabled := io.satpTranslationEnabled
  translation.io.satpRootPpn := io.satpRootPpn
  translation.io.sum := false.B
  translation.io.mxr := io.mxr
  translation.io.pteReady := io.pteReady
  translation.io.pteData := io.pteData
  translation.io.pteFault := io.pteFault
  translation.io.responseReady := io.responseReady

  io.requestReady := translation.io.requestReady
  io.pteValid := translation.io.pteValid
  io.pteAddress := translation.io.pteAddress.pad(paddrBits)
  io.responseValid := translation.io.responseValid
  io.physicalAddress := translation.io.physicalAddress.pad(paddrBits)
  io.pageFault := translation.io.pageFault
  io.accessFault := translation.io.accessFault
}
