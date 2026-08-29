package aethercore.core.v2

import chisel3._
import aethercore.common.{AtomicOp, MemSize}
import aethercore.memory.MemoryAttributes

/**
  * P8.4-M1 read-only facts for the single active LSU lifetime.
  *
  * This bundle deliberately carries no issue, bypass or speculation policy.
  * A later memory-ordering owner may combine these facts with ROB age and a
  * Store Queue, while TinyBlockingLsu remains the owner of the current physical
  * memory lifetime.
  */
class TinyMemoryLifetimeStatus(
    val xlen: Int,
    val paddrBits: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"memory-lifetime XLEN must be 32 or 64, got $xlen")
  require(paddrBits > 0, s"memory-lifetime physical address width must be positive, got $paddrBits")

  val valid = Bool()
  val drained = Bool()

  val robToken = new RobToken(identityBits, generationBits)
  val kind = MemoryOperationKind()
  val atomicOp = AtomicOp()
  val size = MemSize()
  val effectiveAddress = UInt(xlen.W)
  val writeLike = Bool()

  val physicalAddressValid = Bool()
  val physicalAddress = UInt(paddrBits.W)
  val attributesValid = Bool()
  val attributes = new MemoryAttributes

  val writePermitMatched = Bool()

  /**
    * True once this lifetime has crossed the physical-request handshake.
    *
    * This is intentionally not "response outstanding": the blocking LSU keeps
    * physicalIssued asserted after a response has arrived and through any held
    * completion, until completion.fire releases the architectural lifetime.
    */
  val physicalRequestIssued = Bool()

  /** A fresh or backpressured architectural completion is currently valid. */
  val completionPending = Bool()
}
