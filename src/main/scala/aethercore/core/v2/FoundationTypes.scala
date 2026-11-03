package aethercore.core.v2

import chisel3._
import chisel3.ChiselEnum
import aethercore.common.{AluOp, BranchType, TrapInfo}

/** Backend execution classification. It intentionally selects a broad kind of
  * work, not a fixed execution port or pipeline stage.
  */
object ExecutionClass extends ChiselEnum {
  val None, Integer, Branch, MulDiv, Memory, System = Value
}

/** Program-order/lifetime identity. Generation rejects stale completions after
  * a circular ROB slot has been reused. Concrete implementations must choose
  * the generation width explicitly; the contract does not claim one bit is
  * universally sufficient.
  */
class RobToken(val indexBits: Int, val generationBits: Int) extends Bundle {
  require(indexBits > 0, s"ROB index width must be positive, got $indexBits")
  require(generationBits > 0, s"ROB generation width must be positive, got $generationBits")

  val index = UInt(indexBits.W)
  val generation = UInt(generationBits.W)
}

/** Dependency/wakeup identity.
  *
  * The first v2 implementation may numerically allocate this from the same ROB
  * slot as RobToken, but the type is intentionally distinct so wakeup identity
  * can later move independently.
  */
class ProducerTag(val idBits: Int, val generationBits: Int) extends Bundle {
  require(idBits > 0, s"producer-tag width must be positive, got $idBits")
  require(generationBits > 0, s"producer-tag generation width must be positive, got $generationBits")

  val id = UInt(idBits.W)
  val generation = UInt(generationBits.W)
}

/** Value-storage identity.
  *
  * Initially this may identify a ROB result field. A future PRF-backed design
  * may change this representation without changing RobToken or ProducerTag.
  */
class ValueRef(val idBits: Int, val generationBits: Int) extends Bundle {
  require(idBits > 0, s"value-reference width must be positive, got $idBits")
  require(generationBits > 0, s"value-reference generation width must be positive, got $generationBits")

  val id = UInt(idBits.W)
  val generation = UInt(generationBits.W)
}

/** First backend-owned representation.
  *
  * Architectural instruction and backend uOp are separate concepts even while
  * the bring-up implementation maps them 1:1.
  */
class BackendUop(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val decoded = new DecodedInstruction(xlen)
  val executionClass = ExecutionClass()
  val robToken = new RobToken(identityBits, generationBits)
  val producerTag = new ProducerTag(identityBits, generationBits)
  val valueRef = new ValueRef(identityBits, generationBits)
  val producesValue = Bool()
}

/** Narrow request contract for integer/branch/MUL-DIV style execution units.
  * All three identities cross the execution seam independently: order/lifetime,
  * dependency wakeup and value storage must never be inferred from one another.
  */
class ExecutionRequest(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"execution-request XLEN must be 32 or 64, got $xlen")

  val robToken = new RobToken(identityBits, generationBits)
  val producerTag = new ProducerTag(identityBits, generationBits)
  val valueRef = new ValueRef(identityBits, generationBits)
  val executionClass = ExecutionClass()
  val aluOp = AluOp()
  val wordOp = Bool()
  val controlFlowKind = ControlFlowKind()
  val branchType = BranchType()
  val lhs = UInt(xlen.W)
  val rhs = UInt(xlen.W)
  val pc = UInt(xlen.W)
  // Required for architectural link values: compressed jumps retire a link of
  // pc+2 while ordinary 32-bit jumps use pc+4.
  val instBytes = UInt(3.W)
  val immediate = UInt(xlen.W)
}

/**
  * Completion-time description of a privileged architectural effect.
  *
  * This record is deliberately *pending*: execution may calculate a CSR write
  * or validate xRET, but MachineCsrFile is not mutated until the matching ROB
  * head retires. Trap entry itself is represented by ExecutionResponse.exception
  * and is likewise applied only at retirement.
  */
class PendingPrivilegedEffect(val xlen: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"privileged-effect XLEN must be 32 or 64, got $xlen")

  val csrWriteValid = Bool()
  val csrAddress = UInt(12.W)
  val csrWriteData = UInt(xlen.W)
  val trapReturn = Bool()
  val trapReturnSupervisor = Bool()
}

/** Tagged completion. Execution latency is deliberately absent from the type. */
class ExecutionResponse(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  require(xlen == 32 || xlen == 64, s"execution-response XLEN must be 32 or 64, got $xlen")

  val robToken = new RobToken(identityBits, generationBits)
  val producerTag = new ProducerTag(identityBits, generationBits)
  val valueRef = new ValueRef(identityBits, generationBits)
  val hasValue = Bool()
  val value = UInt(xlen.W)

  val branchValid = Bool()
  val branchTaken = Bool()
  val branchTarget = UInt(xlen.W)

  val exception = new TrapInfo(xlen)
  val privileged = new PendingPrivilegedEffect(xlen)
}
