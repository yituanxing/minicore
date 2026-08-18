package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util.Cat

/**
  * Architecture-neutral satp state for one XLEN family.
  *
  * The module separates the architectural satp field layout from the concrete
  * translation engine. Bare is always available. Unsupported MODE writes have
  * no effect, while supported paged modes retain the complete root PPN. ASIDLEN
  * is explicit and defaults to zero so the current frozen Sv32 behavior can be
  * migrated without silently adding address-space state.
  *
  * 通用 satp 状态层：MODE/ASID/PPN 的字段布局由页表几何决定，具体 walker/TLB
  * 不在这里。默认 ASIDLEN=0，便于保持当前 Sv32 冻结语义，再逐步承接 Sv39/Sv48。
  */
class SatpRegister(
    supportedModes: Seq[PageTableGeometry],
    implementedAsidBits: Int = 0
) extends Module {
  require(supportedModes.nonEmpty, "SatpRegister requires at least one paged mode")

  private val xlenValues = supportedModes.map(_.xlen).distinct
  require(xlenValues.size == 1, s"all satp modes must share one XLEN: $xlenValues")
  private val xlen = xlenValues.head
  private val modeBits = if (xlen == 32) 1 else 4
  private val ppnBitsValues = supportedModes.map(_.ppnBits).distinct
  private val asidBitsValues = supportedModes.map(_.asidBits).distinct
  require(ppnBitsValues.size == 1, s"all satp modes must share one PPN field width: $ppnBitsValues")
  require(asidBitsValues.size == 1, s"all satp modes must share one ASID field width: $asidBitsValues")
  private val ppnBits = ppnBitsValues.head
  private val asidBits = asidBitsValues.head
  private val modeNames = supportedModes.map(_.name).toSet

  require(
    modeBits + asidBits + ppnBits == xlen,
    s"satp field widths must exactly cover XLEN=$xlen"
  )
  require(
    implementedAsidBits >= 0 && implementedAsidBits <= asidBits,
    s"implemented ASID width must be 0..$asidBits, got $implementedAsidBits"
  )
  require(
    !modeNames.contains("Sv48") || modeNames.contains("Sv39"),
    "Sv48 satp support requires Sv39 support"
  )

  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(xlen.W))

    val readData = Output(UInt(xlen.W))
    val translationEnabled = Output(Bool())
    val mode = Output(UInt(modeBits.W))
    val rootPpn = Output(UInt(ppnBits.W))
    val asid = Output(UInt(asidBits.W))
  })

  private val modeHigh = xlen - 1
  private val modeLow = xlen - modeBits
  private val asidLow = ppnBits

  val satp = RegInit(0.U(xlen.W))
  val requestedMode = io.writeData(modeHigh, modeLow)
  val supportedMode = supportedModes
    .map(mode => requestedMode === mode.satpMode.U(modeBits.W))
    .reduce(_ || _)

  val canonicalAsid = WireDefault(0.U(asidBits.W))
  if (implementedAsidBits > 0) {
    canonicalAsid := io.writeData(asidLow + implementedAsidBits - 1, asidLow).pad(asidBits)
  }

  when(io.writeEnable) {
    when(requestedMode === 0.U) {
      // Selecting Bare with a canonical zero payload is deterministic and
      // preserves the existing bounded Sv32 policy.
      satp := 0.U
    }.elsewhen(supportedMode) {
      satp := Cat(
        requestedMode,
        canonicalAsid,
        io.writeData(ppnBits - 1, 0)
      )
    }
    // The privileged architecture requires an unsupported MODE write to leave
    // the entire satp value unchanged, so there is intentionally no otherwise.
  }

  io.readData := satp
  io.mode := satp(modeHigh, modeLow)
  io.translationEnabled := io.mode =/= 0.U
  io.rootPpn := satp(ppnBits - 1, 0)
  io.asid := satp(ppnBits + asidBits - 1, ppnBits)
}
