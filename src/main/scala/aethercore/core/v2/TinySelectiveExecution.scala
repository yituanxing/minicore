package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

/**
  * A8 execution composition for selective compute issue.
  *
  * Leaf execution semantics are reused unchanged from F3. This composition adds
  * only two maturation facts that the frozen F3 cluster did not need to expose:
  * fair response arbitration and read-only per-compute-resource acceptance.
  * Branch remains present for the conservative head-only branch path, but it is
  * intentionally absent from TinyComputeAvailability.
  */
class TinySelectiveExecutionCluster(val xlen: Int, val hasCompressed: Boolean) extends Module {
  require(xlen == 32 || xlen == 64, s"selective execution cluster XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val computeAvailability = Output(new TinyComputeAvailability)
  })

  private val integer = Module(new V2IntegerUnit(xlen))
  private val branch = Module(new V2BranchUnit(xlen, hasCompressed))
  private val multiply = Module(new V2MulUnit(xlen))
  private val divide = Module(new V2IterativeDivider(xlen))

  private val mulOperation =
    io.request.bits.aluOp === AluOp.Mul ||
      io.request.bits.aluOp === AluOp.Mulh ||
      io.request.bits.aluOp === AluOp.Mulhsu ||
      io.request.bits.aluOp === AluOp.Mulhu
  private val divOperation =
    io.request.bits.aluOp === AluOp.Div ||
      io.request.bits.aluOp === AluOp.Divu ||
      io.request.bits.aluOp === AluOp.Rem ||
      io.request.bits.aluOp === AluOp.Remu

  private val routeInteger = io.request.bits.executionClass === ExecutionClass.Integer
  private val routeBranch = io.request.bits.executionClass === ExecutionClass.Branch
  private val routeMultiply = io.request.bits.executionClass === ExecutionClass.MulDiv && mulOperation
  private val routeDivide = io.request.bits.executionClass === ExecutionClass.MulDiv && divOperation

  integer.io.request.valid := io.request.valid && routeInteger
  integer.io.request.bits := io.request.bits
  branch.io.request.valid := io.request.valid && routeBranch
  branch.io.request.bits := io.request.bits
  multiply.io.request.valid := io.request.valid && routeMultiply
  multiply.io.request.bits := io.request.bits
  divide.io.request.valid := io.request.valid && routeDivide
  divide.io.request.bits := io.request.bits

  io.request.ready := MuxCase(false.B, Seq(
    routeInteger -> integer.io.request.ready,
    routeBranch -> branch.io.request.ready,
    routeMultiply -> multiply.io.request.ready,
    routeDivide -> divide.io.request.ready
  ))

  // Availability is a read-only view of the real FU request acceptance state.
  // It is not a scheduler-owned busy scoreboard.
  io.computeAvailability.integer := integer.io.request.ready
  io.computeAvailability.multiply := multiply.io.request.ready
  io.computeAvailability.divide := divide.io.request.ready

  private val responses = Module(new RRArbiter(
    new ExecutionResponse(xlen, IdentityBits, GenerationBits),
    4
  ))
  responses.io.in(0) <> integer.io.response
  responses.io.in(1) <> branch.io.response
  responses.io.in(2) <> multiply.io.response
  responses.io.in(3) <> divide.io.response
  io.response <> responses.io.out
}
