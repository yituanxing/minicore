package aethercore.core

import chisel3._
import aethercore.common.MemSize

/**
  * Serial correctness-first adapter for one RV32 Load/Store memory operation.
  *
  * The CPU holds request fields stable until requestComplete. The adapter first
  * translates the virtual address (or performs the architectural Bare/M-mode
  * bypass), then exposes exactly one physical data-bus transaction. Translation
  * faults complete without issuing a data-bus request.
  */
class Sv32DataPathAdapter(val paddrBits: Int = 34) extends Module {
  require(paddrBits >= 34, s"Sv32 data path requires PA>=34, got $paddrBits")

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val virtualAddress = Input(UInt(32.W))
    val privilege = Input(UInt(2.W))
    val write = Input(Bool())
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))
    val size = Input(MemSize())

    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(Sv32Satp.PpnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(paddrBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(32.W))
    val pteFault = Input(Bool())

    val dataValid = Output(Bool())
    val dataWrite = Output(Bool())
    val dataAddress = Output(UInt(paddrBits.W))
    val dataWdata = Output(UInt(32.W))
    val dataWmask = Output(UInt(4.W))
    val dataSize = Output(MemSize())
    val dataReady = Input(Bool())
    val dataRdata = Input(UInt(32.W))
    val dataFault = Input(Bool())

    val requestComplete = Output(Bool())
    val physicalAddress = Output(UInt(paddrBits.W))
    val readData = Output(UInt(32.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val translation = Module(new Sv32TranslationUnit)
  translation.io.requestValid := io.requestValid
  translation.io.virtualAddress := io.virtualAddress
  translation.io.privilege := io.privilege
  translation.io.write := io.write
  translation.io.execute := false.B
  translation.io.satpTranslationEnabled := io.satpTranslationEnabled
  translation.io.satpRootPpn := io.satpRootPpn
  translation.io.sum := io.sum
  translation.io.mxr := io.mxr

  translation.io.pteReady := io.pteReady
  translation.io.pteData := io.pteData
  translation.io.pteFault := io.pteFault
  io.pteValid := translation.io.pteValid
  io.pteAddress := translation.io.pteAddress.pad(paddrBits)

  val translationFault = translation.io.pageFault || translation.io.accessFault
  io.dataValid := io.requestValid && translation.io.responseValid && !translationFault
  io.dataWrite := io.write
  io.dataAddress := translation.io.physicalAddress.pad(paddrBits)
  io.dataWdata := io.wdata
  io.dataWmask := io.wmask
  io.dataSize := io.size

  val physicalComplete = io.dataValid && io.dataReady
  val faultComplete = io.requestValid && translation.io.responseValid && translationFault
  io.requestComplete := physicalComplete || faultComplete
  translation.io.responseReady := io.requestComplete

  io.physicalAddress := translation.io.physicalAddress.pad(paddrBits)
  io.readData := io.dataRdata
  io.pageFault := faultComplete && translation.io.pageFault
  io.accessFault :=
    (faultComplete && translation.io.accessFault) ||
      (physicalComplete && io.dataFault)
}
