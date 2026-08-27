package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

/**
  * A8 execution composition for selective compute issue.
  *
  * Compute availability remains a read-only view of the real Integer/MUL/DIV
  * resources. Branch is head-only and owns an independent request seam: this is
  * important once branch responses may flow through combinationally, because a
  * shared request mux would otherwise close a scheduler -> execution -> response
  * arbitration -> compute-availability combinational cycle.
  *
  * Branch and compute still share the fair response arbiter. Production keeps
  * the global one-launch-per-cycle invariant by blocking selective compute while
  * the exact-head branch request is live; no second architectural issue lane is
  * introduced by this separation.
  */
class TinySelectiveExecutionCluster(val xlen: Int, val hasCompressed: Boolean) extends Module {
  require(xlen == 32 || xlen == 64, s"selective execution cluster XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val branchRequest = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val computeRequest = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val secondaryIntegerRequest = Flipped(Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    val response = Decoupled(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val computeAvailability = Output(new TinyComputeAvailability)
    val secondaryIntegerAvailable = Output(Bool())
  })

  private val integer = Module(new V2IntegerUnit(xlen))
  private val secondaryInteger = Module(new V2IntegerUnit(xlen))
  private val branch = Module(new V2BranchUnit(xlen, hasCompressed))
  private val multiply = Module(new V2MulUnit(xlen))
  private val divide = Module(new V2IterativeDivider(xlen))

  private val mulOperation =
    io.computeRequest.bits.aluOp === AluOp.Mul ||
      io.computeRequest.bits.aluOp === AluOp.Mulh ||
      io.computeRequest.bits.aluOp === AluOp.Mulhsu ||
      io.computeRequest.bits.aluOp === AluOp.Mulhu
  private val divOperation =
    io.computeRequest.bits.aluOp === AluOp.Div ||
      io.computeRequest.bits.aluOp === AluOp.Divu ||
      io.computeRequest.bits.aluOp === AluOp.Rem ||
      io.computeRequest.bits.aluOp === AluOp.Remu

  private val routeInteger = io.computeRequest.bits.executionClass === ExecutionClass.Integer
  private val routeMultiply = io.computeRequest.bits.executionClass === ExecutionClass.MulDiv && mulOperation
  private val routeDivide = io.computeRequest.bits.executionClass === ExecutionClass.MulDiv && divOperation

  integer.io.request.valid := io.computeRequest.valid && routeInteger
  integer.io.request.bits := io.computeRequest.bits
  multiply.io.request.valid := io.computeRequest.valid && routeMultiply
  multiply.io.request.bits := io.computeRequest.bits
  divide.io.request.valid := io.computeRequest.valid && routeDivide
  divide.io.request.bits := io.computeRequest.bits

  io.computeRequest.ready := MuxCase(false.B, Seq(
    routeInteger -> integer.io.request.ready,
    routeMultiply -> multiply.io.request.ready,
    routeDivide -> divide.io.request.ready
  ))

  secondaryInteger.io.request <> io.secondaryIntegerRequest
  io.secondaryIntegerAvailable := secondaryInteger.io.request.ready
  when(io.secondaryIntegerRequest.valid) {
    assert(io.secondaryIntegerRequest.bits.executionClass === ExecutionClass.Integer,
      "secondary compute lane must carry only Integer execution")
  }

  branch.io.request <> io.branchRequest

  when(io.branchRequest.valid) {
    assert(io.branchRequest.bits.executionClass === ExecutionClass.Branch,
      "head-only branch seam must carry only Branch execution")
  }
  when(io.computeRequest.valid) {
    assert(io.computeRequest.bits.executionClass =/= ExecutionClass.Branch,
      "selective compute seam must never carry Branch execution")
  }

  // Availability stays owned by the real compute FU request acceptance state.
  // In particular, this preserves same-cycle consume-and-replace behavior for a
  // one-entry Integer/MUL response; no registered/shadow availability is added
  // merely to accommodate branch flow-through.
  io.computeAvailability.integer := integer.io.request.ready
  io.computeAvailability.multiply := multiply.io.request.ready
  io.computeAvailability.divide := divide.io.request.ready

  private val responses = Module(new RRArbiter(
    new ExecutionResponse(xlen, IdentityBits, GenerationBits),
    5
  ))
  responses.io.in(0) <> integer.io.response
  responses.io.in(1) <> secondaryInteger.io.response
  responses.io.in(2) <> branch.io.response
  responses.io.in(3) <> multiply.io.response
  responses.io.in(4) <> divide.io.response
  io.response <> responses.io.out
}
