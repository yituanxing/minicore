package aethercore.core.product

import aethercore.core.WarmCsrSeed
import chisel3._
import chisel3.util.Valid

/** Experimental *simulation-only* architectural seed. No production Product
  * top elaborates this port unless its caller explicitly opts in. `hold` must
  * be asserted continuously from the first clock after reset through the
  * complete seed; the core may execute only after it is deasserted.
  *
  * This deliberately seeds only PC/privilege/mstatus/SATP/PMP and XPRs. It is
  * not a complete QEMU state import (FPR/FCSR, all CSRs and devices are absent).
  */
class ProductWarmGprWrite extends Bundle {
  val index = UInt(5.W)
  val data = UInt(64.W)
}

class ProductWarmSeedState(paddrBits: Int) extends Bundle {
  val hold = Bool()
  val load = Bool()
  val pc = UInt(64.W)
  val csr = new WarmCsrSeed(paddrBits)
  val gpr = Valid(new ProductWarmGprWrite)
}
