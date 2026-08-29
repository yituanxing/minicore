package aethercore.core

import chisel3._

/** XLEN-neutral privileged-system instruction classifiers. */
object SystemInstruction {
  private val SfenceVmaMask = BigInt("fe007fff", 16)
  private val SfenceVmaMatch = BigInt("12000073", 16)

  /** rs1/rs2 are intentionally excluded; the current first TLB stage over-fences globally. */
  def isSfenceVma(inst: UInt): Bool =
    (inst & SfenceVmaMask.U(32.W)) === SfenceVmaMatch.U(32.W)
}
