package aethercore.core.v2

import chisel3._
import aethercore.common.AluOp

/**
  * First backend-owned classification boundary.
  *
  * The interface is intentionally narrower than DecodedInstruction: raw and
  * canonical instruction bits, immediates and ISA/profile legality are not
  * visible here. Backend classification therefore cannot re-decode opcode or
  * funct fields by construction.
  */
class TinyBackendClassifier extends Module {
  val io = IO(new Bundle {
    val aluOp = Input(AluOp())
    val systemKind = Input(SystemOperationKind())
    val memoryKind = Input(MemoryOperationKind())
    val controlFlowKind = Input(ControlFlowKind())
    val writesRd = Input(Bool())
    val rd = Input(UInt(5.W))
    val exceptionValid = Input(Bool())

    val executionClass = Output(ExecutionClass())
    val producesValue = Output(Bool())
  })

  private val mulDiv =
    io.aluOp === AluOp.Mul ||
      io.aluOp === AluOp.Mulh ||
      io.aluOp === AluOp.Mulhsu ||
      io.aluOp === AluOp.Mulhu ||
      io.aluOp === AluOp.Div ||
      io.aluOp === AluOp.Divu ||
      io.aluOp === AluOp.Rem ||
      io.aluOp === AluOp.Remu

  io.executionClass := ExecutionClass.Integer
  when(io.systemKind =/= SystemOperationKind.None) {
    io.executionClass := ExecutionClass.System
  }.elsewhen(io.memoryKind =/= MemoryOperationKind.None) {
    io.executionClass := ExecutionClass.Memory
  }.elsewhen(io.controlFlowKind =/= ControlFlowKind.None) {
    io.executionClass := ExecutionClass.Branch
  }.elsewhen(mulDiv) {
    io.executionClass := ExecutionClass.MulDiv
  }

  io.producesValue := io.writesRd && io.rd =/= 0.U && !io.exceptionValid
}
