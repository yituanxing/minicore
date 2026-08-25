package aethercore.core.v2

import chisel3._
import aethercore.common.AluOp

/**
  * First backend-owned classification boundary.
  *
  * This module consumes only DecodedInstruction architectural semantics. It is
  * intentionally forbidden from interpreting opcode/funct fields or using raw
  * instruction bits to choose scheduler/execution behavior.
  */
class TinyBackendClassifier(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"backend-classifier XLEN must be 32 or 64, got $xlen")

  val io = IO(new Bundle {
    val decoded = Input(new DecodedInstruction(xlen))
    val dispatch = Output(new RobDispatch(xlen))
  })

  private val mulDiv =
    io.decoded.aluOp === AluOp.Mul ||
      io.decoded.aluOp === AluOp.Mulh ||
      io.decoded.aluOp === AluOp.Mulhsu ||
      io.decoded.aluOp === AluOp.Mulhu ||
      io.decoded.aluOp === AluOp.Div ||
      io.decoded.aluOp === AluOp.Divu ||
      io.decoded.aluOp === AluOp.Rem ||
      io.decoded.aluOp === AluOp.Remu

  private val executionClass = WireDefault(ExecutionClass.Integer)
  when(io.decoded.system.kind =/= SystemOperationKind.None) {
    executionClass := ExecutionClass.System
  }.elsewhen(io.decoded.memory.kind =/= MemoryOperationKind.None) {
    executionClass := ExecutionClass.Memory
  }.elsewhen(io.decoded.controlFlow.kind =/= ControlFlowKind.None) {
    executionClass := ExecutionClass.Branch
  }.elsewhen(mulDiv) {
    executionClass := ExecutionClass.MulDiv
  }

  io.dispatch := 0.U.asTypeOf(new RobDispatch(xlen))
  io.dispatch.decoded := io.decoded
  io.dispatch.executionClass := executionClass
  io.dispatch.producesValue :=
    io.decoded.writesRd && io.decoded.rd =/= 0.U && !io.decoded.exception.valid
}
