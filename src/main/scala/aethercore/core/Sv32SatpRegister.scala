package aethercore.core

import aethercore.config.PageTableGeometry
import chisel3._

object Sv32Satp {
  private val geometry = PageTableGeometry.Sv32

  val ModeBare: Int = 0
  val ModeSv32: Int = geometry.satpMode
  val ModeBit: Int = 31
  val AsidHigh: Int = 30
  val AsidLow: Int = geometry.ppnBits
  val AsidBits: Int = geometry.asidBits
  val PpnHigh: Int = geometry.ppnBits - 1
  val PpnLow: Int = 0
  val PpnBits: Int = geometry.ppnBits
}

/**
  * Compatibility surface for the frozen RV32 Sv32 satp contract.
  *
  * The architectural state now comes from the shared geometry-driven
  * SatpRegister. Keeping this thin wrapper preserves every existing Sv32 V2
  * source/test interface while making that frozen workload the first production
  * consumer of the VM framework that will later host Sv39 and Sv48.
  *
  * 兼容现有 Sv32 V2 接口的薄封装。真正的 satp 状态由共享 SatpRegister 提供，
  * 这样历史 Sv32 回归可以直接约束后续 Sv39/Sv48 所复用的实现。
  */
class Sv32SatpRegister extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(32.W))

    val readData = Output(UInt(32.W))
    val translationEnabled = Output(Bool())
    val rootPpn = Output(UInt(Sv32Satp.PpnBits.W))
    val asid = Output(UInt(Sv32Satp.AsidBits.W))
  })

  private val shared = Module(new SatpRegister(Seq(PageTableGeometry.Sv32), implementedAsidBits = 0))
  shared.io.writeEnable := io.writeEnable
  shared.io.writeData := io.writeData

  io.readData := shared.io.readData
  io.translationEnabled := shared.io.translationEnabled
  io.rootPpn := shared.io.rootPpn
  io.asid := shared.io.asid
}
