package aethercore.core

import chisel3._

object Sv32SystemInstruction {
  private val SfenceVmaMask = BigInt("fe007fff", 16)
  private val SfenceVmaMatch = BigInt("12000073", 16)

  /** rs1/rs2 are intentionally excluded from the mask; the first TLB stage over-fences globally. */
  def isSfenceVma(inst: UInt): Bool =
    (inst & SfenceVmaMask.U(32.W)) === SfenceVmaMatch.U(32.W)
}
