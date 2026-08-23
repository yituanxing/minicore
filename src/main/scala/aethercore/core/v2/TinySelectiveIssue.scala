package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

/**
  * Resource-availability seam consumed by the first selective compute scheduler.
  *
  * It deliberately names only side-effect-free compute resources. Branch,
  * memory and system ordering remain outside this contract and therefore cannot
  * accidentally become selective merely because a functional unit exists.
  */
class TinyComputeAvailability extends Bundle {
  val integer = Bool()
  val multiply = Bool()
  val divide = Bool()
}

/**
  * A8.3 oldest-ready single-issue selector over the read-only scheduling view.
  *
  * This module owns policy and once-only issue state, but no uOp/dependency
  * storage. It scans the four live ROB ages and may select only exception-free,
  * normally ordered Integer or Mul/Div work whose operands and target resource
  * are ready. Branch and Memory may be bypassed because selected compute is
  * side-effect free; System, explicit serialization and known exception
  * boundaries stop younger issue. Architectural Commit remains in order.
  */
class TinySelectiveComputeIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"selective issue XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val window = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val allocated = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val block = Input(Bool())
    val availability = Input(new TinyComputeAvailability)
    val request = Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  })

  private val issuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val issuedToken = Reg(Vec(Entries, new RobToken(IdentityBits, GenerationBits)))

  private def sameRobToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private def isMultiply(op: AluOp.Type): Bool =
    op === AluOp.Mul || op === AluOp.Mulh || op === AluOp.Mulhsu || op === AluOp.Mulhu

  private def isDivide(op: AluOp.Type): Bool =
    op === AluOp.Div || op === AluOp.Divu || op === AluOp.Rem || op === AluOp.Remu

  private def resourceReady(entry: TinySchedulingEntry): Bool = {
    val executionClass = entry.uop.executionClass
    val op = entry.uop.decoded.aluOp
    (executionClass === ExecutionClass.Integer && io.availability.integer) ||
      (executionClass === ExecutionClass.MulDiv && isMultiply(op) && io.availability.multiply) ||
      (executionClass === ExecutionClass.MulDiv && isDivide(op) && io.availability.divide)
  }

  // A candidate may bypass ordinary compute/branch/memory work, but it must not
  // cross an older architectural serialization point or an already-known trap.
  // Keep this as an age-prefix permission instead of teaching the scheduler any
  // CSR/fence implementation details.
  private val bypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    val older = io.window(age - 1)
    val olderBlocksBypass = older.valid && (
      older.uop.executionClass === ExecutionClass.System ||
      older.uop.decoded.ordering =/= OrderingClass.Normal ||
      older.uop.decoded.exception.valid
    )
    bypassOpen(age) := bypassOpen(age - 1) && !olderBlocksBypass
  }

  private val eligible = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    val entry = io.window(age)
    val token = entry.uop.robToken
    val alreadyIssued = issuedValid(token.index) && sameRobToken(issuedToken(token.index), token)
    val safeClass = entry.uop.executionClass === ExecutionClass.Integer ||
      entry.uop.executionClass === ExecutionClass.MulDiv

    eligible(age) := bypassOpen(age) &&
      entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !entry.uop.decoded.exception.valid &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      safeClass &&
      resourceReady(entry) &&
      !alreadyIssued
  }

  // Emit candidates youngest-to-oldest so Chisel's last-connect priority leaves
  // age0 as the winner whenever multiple entries are eligible.
  private val selectedValid = WireDefault(false.B)
  private val selected = WireDefault(0.U.asTypeOf(new TinySchedulingEntry(xlen)))
  for (age <- (Entries - 1) to 0 by -1) {
    when(eligible(age)) {
      selectedValid := true.B
      selected := io.window(age)
    }
  }

  private def materializeSource(kind: OperandSourceKind.Type): UInt = {
    val value = WireDefault(0.U(xlen.W))
    switch(kind) {
      is(OperandSourceKind.Zero)      { value := 0.U }
      is(OperandSourceKind.Rs1)       { value := selected.rs1.value }
      is(OperandSourceKind.Rs2)       { value := selected.rs2.value }
      is(OperandSourceKind.Pc)        { value := selected.uop.decoded.pc }
      is(OperandSourceKind.Immediate) { value := selected.uop.decoded.immediate }
    }
    value
  }

  io.request.valid := selectedValid && !io.block
  io.request.bits := 0.U.asTypeOf(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  io.request.bits.robToken := selected.uop.robToken
  io.request.bits.producerTag := selected.uop.producerTag
  io.request.bits.valueRef := selected.uop.valueRef
  io.request.bits.executionClass := selected.uop.executionClass
  io.request.bits.aluOp := selected.uop.decoded.aluOp
  io.request.bits.wordOp := selected.uop.decoded.wordOp
  io.request.bits.controlFlowKind := selected.uop.decoded.controlFlow.kind
  io.request.bits.branchType := selected.uop.decoded.controlFlow.branchType
  io.request.bits.lhs := materializeSource(selected.uop.decoded.lhsSource)
  io.request.bits.rhs := materializeSource(selected.uop.decoded.rhsSource)
  io.request.bits.pc := selected.uop.decoded.pc
  io.request.bits.instBytes := selected.uop.decoded.instBytes
  io.request.bits.immediate := selected.uop.decoded.immediate

  // Allocation starts a fresh physical-slot issue lifetime even if the bounded
  // generation space eventually wraps. No uOp is copied into this scoreboard.
  when(io.allocated.valid) {
    issuedValid(io.allocated.bits.robToken.index) := false.B
  }

  when(io.request.fire) {
    val token = io.request.bits.robToken
    issuedValid(token.index) := true.B
    issuedToken(token.index) := token
  }
}
