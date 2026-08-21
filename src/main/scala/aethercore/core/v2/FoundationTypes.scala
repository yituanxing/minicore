package aethercore.core.v2

import chisel3._
import chisel3.ChiselEnum
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, TrapInfo, XRetOp}

/** Backend execution classification. It intentionally selects a broad kind of
  * work, not a fixed execution port or pipeline stage.
  */
object ExecutionClass extends ChiselEnum {
  val None, Integer, Branch, MulDiv, Memory, System = Value
}

object ControlFlowKind extends ChiselEnum {
  val None, Conditional, DirectJump, IndirectJump = Value
}

object MemoryOperationKind extends ChiselEnum {
  val None, Load, Store, Atomic = Value
}

object SystemOperationKind extends ChiselEnum {
  val None, Csr, Ecall, Ebreak, Wfi, Xret, Fence, FenceI, SfenceVma = Value
}

/** Decode-visible scheduling/serialization semantics for system and memory
  * operations. Address-dependent PMA/device ordering belongs to the resolved
  * memory transaction attributes, not to instruction decode.
  */
object OrderingClass extends ChiselEnum {
  val Normal,
      SerializeBefore,
      SerializeAfter,
      SerializeBoth,
      MemoryFence,
      TranslationFence = Value
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

class DecodedMemoryOperation extends Bundle {
  val kind = MemoryOperationKind()
  val size = MemSize()
  val unsigned = Bool()
  val atomicOp = AtomicOp()
  val acquire = Bool()
  val release = Bool()
}

class DecodedControlFlow extends Bundle {
  val kind = ControlFlowKind()
  val branchType = BranchType()
}

class DecodedSystemOperation extends Bundle {
  val kind = SystemOperationKind()
  val csrOp = CsrOp()
  val csrAddress = UInt(12.W)
  val csrUseImmediate = Bool()
  val xret = XRetOp()
}

/** Architectural instruction semantics after decode.
  *
  * This record deliberately excludes ROB slots, physical registers, branch
  * masks, queue entries, predictor metadata, backend execution classes, bypass
  * selectors and execution-port assignments.
  */
class DecodedInstruction(val xlen: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"decoded-instruction XLEN must be 32 or 64, got $xlen")

  val pc = UInt(xlen.W)
  val inst = UInt(32.W)
  val rawInst = UInt(32.W)
  val instBytes = UInt(3.W)

  val aluOp = AluOp()

  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val writesRd = Bool()
  val immediate = UInt(xlen.W)

  val controlFlow = new DecodedControlFlow
  val memory = new DecodedMemoryOperation
  val system = new DecodedSystemOperation
  val ordering = OrderingClass()
  val exception = new TrapInfo(xlen)
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
  val controlFlowKind = ControlFlowKind()
  val branchType = BranchType()
  val lhs = UInt(xlen.W)
  val rhs = UInt(xlen.W)
  val pc = UInt(xlen.W)
  val immediate = UInt(xlen.W)
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
}
