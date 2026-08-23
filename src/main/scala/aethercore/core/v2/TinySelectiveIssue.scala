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
  * ROB storage depth and selective scan depth are deliberately separate. The
  * full read-only scheduling projection remains visible so order/lifetime state
  * has one owner, while scanEntries bounds only the combinational oldest-ready
  * policy. A smaller scan therefore never changes Commit, Branch, Memory or
  * System ordering; younger compute simply waits until it ages into the scan.
  */
class TinySelectiveComputeIssue(
    val xlen: Int,
    val scanEntries: Int = math.min(4, TinyRobGeometry.Entries)
) extends Module {
  require(xlen == 32 || xlen == 64, s"selective issue XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  require(scanEntries > 0 && scanEntries <= Entries,
    s"selective scan depth must be within 1..$Entries, got $scanEntries")

  val io = IO(new Bundle {
    val window = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val allocated = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    // Production asserts block while a not-yet-accepted head Memory request is
    // valid, as well as during accepted recovery. Consequently, a ready age0
    // Memory with block deasserted is already owned by the blocking LSU.
    val block = Input(Bool())
    val availability = Input(new TinyComputeAvailability)
    val request = Decoupled(new ExecutionRequest(xlen, IdentityBits, GenerationBits))
  })

  // Once-only issue ownership must still cover every physical ROB lifetime even
  // when only the oldest subset participates in a given cycle's scan.
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

  // A candidate may bypass ordinary compute and Branch work. Memory is stricter:
  // exact-head launch is the only ownership transfer into the LSU, so a Memory
  // at age>0 is necessarily unlaunched and blocks younger selective compute.
  // For age0, dependencies/operands must already be ready and production block
  // must have fallen after the LSU accepted the request. System, serialization
  // and known exceptions remain unconditional architectural barriers.
  private val bypassOpen = Wire(Vec(scanEntries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until scanEntries) {
    val older = io.window(age - 1)
    val olderIsMemory = older.valid &&
      older.uop.executionClass === ExecutionClass.Memory
    val olderMemoryLaunched = if (age == 1) {
      older.dependenciesValid && older.operandsReady && !io.block
    } else {
      false.B
    }
    val olderBlocksBypass = older.valid && (
      older.uop.executionClass === ExecutionClass.System ||
      older.uop.decoded.ordering =/= OrderingClass.Normal ||
      older.uop.decoded.exception.valid ||
      (olderIsMemory && !olderMemoryLaunched)
    )
    bypassOpen(age) := bypassOpen(age - 1) && !olderBlocksBypass
  }

  private val eligible = Wire(Vec(scanEntries, Bool()))
  for (age <- 0 until scanEntries) {
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

  // Materialize operands only for the bounded scan set. Keeping the ROB8 state
  // window intact while limiting these data muxes is the main FPGA-timing point
  // of the window4 experiment.
  private def materializeSource(entry: TinySchedulingEntry, kind: OperandSourceKind.Type): UInt = {
    val value = WireDefault(0.U(xlen.W))
    switch(kind) {
      is(OperandSourceKind.Zero)      { value := 0.U }
      is(OperandSourceKind.Rs1)       { value := entry.rs1.value }
      is(OperandSourceKind.Rs2)       { value := entry.rs2.value }
      is(OperandSourceKind.Pc)        { value := entry.uop.decoded.pc }
      is(OperandSourceKind.Immediate) { value := entry.uop.decoded.immediate }
    }
    value
  }

  private val candidates = Wire(Vec(scanEntries, new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
  for (age <- 0 until scanEntries) {
    val entry = io.window(age)
    val candidate = WireDefault(0.U.asTypeOf(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
    candidate.robToken := entry.uop.robToken
    candidate.producerTag := entry.uop.producerTag
    candidate.valueRef := entry.uop.valueRef
    candidate.executionClass := entry.uop.executionClass
    candidate.aluOp := entry.uop.decoded.aluOp
    candidate.wordOp := entry.uop.decoded.wordOp
    candidate.controlFlowKind := entry.uop.decoded.controlFlow.kind
    candidate.branchType := entry.uop.decoded.controlFlow.branchType
    candidate.lhs := materializeSource(entry, entry.uop.decoded.lhsSource)
    candidate.rhs := materializeSource(entry, entry.uop.decoded.rhsSource)
    candidate.pc := entry.uop.decoded.pc
    candidate.instBytes := entry.uop.decoded.instBytes
    candidate.immediate := entry.uop.decoded.immediate
    candidates(age) := candidate
  }

  // Emit candidates youngest-to-oldest so Chisel's last-connect priority leaves
  // age0 as the winner whenever multiple entries are eligible. Only the fully
  // materialized request is muxed across the bounded scan ages.
  private val selectedValid = WireDefault(false.B)
  private val selectedRequest = WireDefault(0.U.asTypeOf(new ExecutionRequest(xlen, IdentityBits, GenerationBits)))
  for (age <- (scanEntries - 1) to 0 by -1) {
    when(eligible(age)) {
      selectedValid := true.B
      selectedRequest := candidates(age)
    }
  }

  io.request.valid := selectedValid && !io.block
  io.request.bits := selectedRequest

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
