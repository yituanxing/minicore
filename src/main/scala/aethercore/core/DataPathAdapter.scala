package aethercore.core

import aethercore.common.MemSize
import aethercore.config.PageTableGeometry
import chisel3._

/**
  * Serial correctness-first data-side adapter shared by one paged VM geometry.
  *
  * Translation permission intent is deliberately separate from physical bus
  * direction: an AMO read phase may require store permission while still
  * issuing a physical read before the write-back phase.
  *
  * 通用数据侧翻译适配器。翻译权限意图与物理总线读写方向分离，便于后续 RV64
  * 与原子扩展共用同一边界，而不复制 Sv32 专用状态机。
  */
class DataPathAdapter(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8
) extends Module {
  private val Xlen = geometry.xlen
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  private val BusBytes = Xlen / 8

  require(
    PhysicalBits >= geometry.architecturalPhysicalAddressBits,
    s"${geometry.name} data path requires PA>=${geometry.architecturalPhysicalAddressBits}, got $PhysicalBits"
  )

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val privilege = Input(UInt(2.W))
    val translateWrite = Input(Bool())
    val write = Input(Bool())
    val wdata = Input(UInt(Xlen.W))
    val wmask = Input(UInt(BusBytes.W))
    val size = Input(MemSize())

    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val dataValid = Output(Bool())
    val dataWrite = Output(Bool())
    val dataAddress = Output(UInt(PhysicalBits.W))
    val dataWdata = Output(UInt(Xlen.W))
    val dataWmask = Output(UInt(BusBytes.W))
    val dataSize = Output(MemSize())
    val dataReady = Input(Bool())
    val dataRdata = Input(UInt(Xlen.W))
    val dataFault = Input(Bool())

    val requestComplete = Output(Bool())
    val physicalAddress = Output(UInt(PhysicalBits.W))
    val readData = Output(UInt(Xlen.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val translation = Module(new TranslationUnit(geometry, tlbEntries))
  translation.io.requestValid := io.requestValid
  translation.io.kill := false.B
  translation.io.flush := io.flush
  translation.io.virtualAddress := io.virtualAddress
  translation.io.privilege := io.privilege
  translation.io.write := io.translateWrite
  translation.io.execute := false.B
  translation.io.satpTranslationEnabled := io.satpTranslationEnabled
  translation.io.satpRootPpn := io.satpRootPpn
  translation.io.sum := io.sum
  translation.io.mxr := io.mxr

  translation.io.pteReady := io.pteReady
  translation.io.pteData := io.pteData
  translation.io.pteFault := io.pteFault
  io.pteValid := translation.io.pteValid
  io.pteAddress := translation.io.pteAddress.pad(PhysicalBits)

  val translationFault = translation.io.pageFault || translation.io.accessFault
  io.dataValid := io.requestValid && translation.io.responseValid && !translationFault
  io.dataWrite := io.write
  io.dataAddress := translation.io.physicalAddress.pad(PhysicalBits)
  io.dataWdata := io.wdata
  io.dataWmask := io.wmask
  io.dataSize := io.size

  val physicalComplete = io.dataValid && io.dataReady
  val faultComplete = io.requestValid && translation.io.responseValid && translationFault
  io.requestComplete := physicalComplete || faultComplete
  translation.io.responseReady := io.requestComplete

  io.physicalAddress := translation.io.physicalAddress.pad(PhysicalBits)
  io.readData := io.dataRdata
  io.pageFault := faultComplete && translation.io.pageFault
  io.accessFault :=
    (faultComplete && translation.io.accessFault) ||
      (physicalComplete && io.dataFault)
}
