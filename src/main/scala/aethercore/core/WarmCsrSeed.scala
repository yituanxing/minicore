package aethercore.core

import chisel3._

/** Strictly opt-in simulation warm-start inputs for the privileged CSR owner.
  * These fields cover only the first-instruction synthetic gate, not every CSR.
  */
class WarmCsrSeed(paddrBits: Int) extends Bundle {
  val valid = Bool()
  val privilege = UInt(2.W)
  val mstatus = UInt(64.W)
  val satp = UInt(64.W)
  val pmpConfig = Vec(PmpConstants.MaxEntries, UInt(8.W))
  val pmpAddress = Vec(PmpConstants.MaxEntries,
    UInt(PmpGeometry(64, paddrBits).encodedAddressBits.W))
}
