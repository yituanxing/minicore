package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._

/**
  * Cancellable instruction-side adapter over one geometry-driven translation
  * unit. The wrapper owns only frontend request/response plumbing; page-table
  * semantics remain inside TranslationUnit/PageTableWalker.
  *
  * 通用取指翻译适配器：只负责前端握手与宽度适配，页表语义仍由共享翻译单元承载。
  */
class InstructionFetchAdapter(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8
) extends Module {
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else geometry.architecturalPhysicalAddressBits
  require(
    PhysicalBits >= geometry.architecturalPhysicalAddressBits,
    s"${geometry.name} instruction translation requires PA>=${geometry.architecturalPhysicalAddressBits}, got $PhysicalBits"
  )

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val requestReady = Output(Bool())
    val kill = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(geometry.xlen.W))
    val privilege = Input(UInt(2.W))
    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val physicalAddress = Output(UInt(PhysicalBits.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val translation = Module(new TranslationUnit(geometry, tlbEntries))
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
  io.pteAddress := translation.io.pteAddress.pad(PhysicalBits)
  io.responseValid := translation.io.responseValid
  io.physicalAddress := translation.io.physicalAddress.pad(PhysicalBits)
  io.pageFault := translation.io.pageFault
  io.accessFault := translation.io.accessFault
}
