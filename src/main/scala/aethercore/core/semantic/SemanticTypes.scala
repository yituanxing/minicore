package aethercore.core.semantic

import chisel3._
import chisel3.ChiselEnum
import aethercore.common.{AluOp, AtomicOp, BranchType, CsrOp, MemSize, TrapInfo, XRetOp}

/** Architectural instruction semantics, shared by the qualified v2 and ProductCore.
  * No ROB geometry, physical register, issue port or Tiny type may enter here.
  */
/** Semantic source of an execution operand.
  *
  * This describes what the instruction means, not a bypass mux, physical port,
  * pipeline stage or scheduler choice. The first issue implementation can use
  * it to materialize concrete lhs/rhs values without re-decoding raw opcode
  * bits.
  */
object OperandSourceKind extends ChiselEnum {
  val Zero, Rs1, Rs2, Pc, Immediate = Value
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
  // CSR immediate instructions encode a semantic zero-extended five-bit zimm
  // in the rs1 field. Preserve it explicitly so F5 never has to reinterpret a
  // logical register field as an immediate operand.
  val csrImmediate = UInt(5.W)
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
  // RV64 *W integer/M operations reuse AluOp but execute with 32-bit operands
  // and sign-extend the low 32-bit result. This is architectural decode
  // semantics, not an execution-port or pipeline control bit.
  val wordOp = Bool()
  val lhsSource = OperandSourceKind()
  val rhsSource = OperandSourceKind()

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

