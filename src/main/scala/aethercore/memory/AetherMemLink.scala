package aethercore.memory

import chisel3._
import chisel3.ChiselEnum
import aethercore.common.{AtomicOp, MemSize}

/** Core-internal physical-memory operation. Bus protocols such as AXI remain
  * outside this contract.
  */
object AetherMemOp extends ChiselEnum {
  val Read, Write, Atomic = Value
}

/** PMA-like properties carried across the core/memory boundary.
  *
  * The first implementation may derive these from a simple address map. The
  * fields exist now so later caches/MMIO handling do not require ad-hoc address
  * checks to leak back into the core pipeline.
  */
class MemoryAttributes extends Bundle {
  val cacheable = Bool()
  val idempotent = Bool()
  val sideEffecting = Bool()
  val ordered = Bool()
  val executable = Bool()
  val supportsAtomic = Bool()
  val supportsPartial = Bool()
}

class AetherMemRequest(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int = 1
) extends Bundle {
  require(addrBits > 0, s"AetherMem address width must be positive, got $addrBits")
  require(dataBits > 0 && dataBits % 8 == 0, s"AetherMem data width must be byte aligned, got $dataBits")
  require(txnIdBits > 0, s"AetherMem transaction-id width must be positive, got $txnIdBits")

  val txnId = UInt(txnIdBits.W)
  val op = AetherMemOp()
  val paddr = UInt(addrBits.W)
  val size = MemSize()
  val wdata = UInt(dataBits.W)
  val wmask = UInt((dataBits / 8).W)
  val atomicOp = AtomicOp()
  val attributes = new MemoryAttributes
}

class AetherMemResponse(
    val dataBits: Int,
    val txnIdBits: Int = 1
) extends Bundle {
  require(dataBits > 0 && dataBits % 8 == 0, s"AetherMem response width must be byte aligned, got $dataBits")
  require(txnIdBits > 0, s"AetherMem response transaction-id width must be positive, got $txnIdBits")

  val txnId = UInt(txnIdBits.W)
  val rdata = UInt(dataBits.W)
  val fault = Bool()
  val last = Bool()
}
