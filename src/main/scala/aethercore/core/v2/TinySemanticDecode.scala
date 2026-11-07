package aethercore.core.v2

import chisel3._
import aethercore.common.AluOp
import aethercore.config.IsaConfig
import aethercore.core.semantic.SemanticDecode

/** v2-compatible RobDispatch adapter over the one canonical semantic decode.
  * ProductCore consumes SemanticDecode directly, never this v2 dispatch uOp.
  */
class TinySemanticDecode(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen
  val io = IO(new Bundle {
    val pc = Input(UInt(xlen.W))
    val inst = Input(UInt(32.W))
    val rawInst = Input(UInt(32.W))
    val instBytes = Input(UInt(3.W))
    val fetchException = Input(new aethercore.common.TrapInfo(xlen))
    val dispatch = Output(new RobDispatch(xlen))
  })

  val semantic = Module(new SemanticDecode(isa))
  semantic.io.pc := io.pc
  semantic.io.inst := io.inst
  semantic.io.rawInst := io.rawInst
  semantic.io.instBytes := io.instBytes
  semantic.io.fetchException := io.fetchException

  io.dispatch := 0.U.asTypeOf(new RobDispatch(xlen))
  io.dispatch.decoded := semantic.io.decoded

  private val decoded = semantic.io.decoded
  private val mulDiv =
    decoded.aluOp === AluOp.Mul ||
      decoded.aluOp === AluOp.Mulh ||
      decoded.aluOp === AluOp.Mulhsu ||
      decoded.aluOp === AluOp.Mulhu ||
      decoded.aluOp === AluOp.Div ||
      decoded.aluOp === AluOp.Divu ||
      decoded.aluOp === AluOp.Rem ||
      decoded.aluOp === AluOp.Remu

  private val classification = WireDefault(ExecutionClass.Integer)
  when(decoded.system.kind =/= SystemOperationKind.None) {
    classification := ExecutionClass.System
  }.elsewhen(decoded.memory.kind =/= MemoryOperationKind.None) {
    classification := ExecutionClass.Memory
  }.elsewhen(decoded.controlFlow.kind =/= ControlFlowKind.None) {
    classification := ExecutionClass.Branch
  }.elsewhen(mulDiv) {
    classification := ExecutionClass.MulDiv
  }
  io.dispatch.executionClass := classification
  io.dispatch.producesValue := decoded.writesRd && decoded.rd =/= 0.U
}
