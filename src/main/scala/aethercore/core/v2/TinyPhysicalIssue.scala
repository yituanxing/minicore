package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

/**
  * FPGA-oriented selective compute selector over physical ROB slots.
  *
  * Architectural age is represented only by the two-bit modulo-4 distance
  * (slot-head). The several-hundred-bit BackendUop never passes through a
  * physical->age reorder crossbar; only the final materialized request is muxed.
  */
class TinyPhysicalSelectiveComputeIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64)

  private val Entries = TinyRobGeometry.Entries
  private val IndexBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val slots = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val headIndex = Input(UInt(IndexBits.W))
    val allocated = Flipped(Valid(new BackendUop(xlen, IndexBits, GenerationBits)))
    val block = Input(Bool())
    val availability = Input(new TinyComputeAvailability)
    val request = Decoupled(new ExecutionRequest(xlen, IndexBits, GenerationBits))
  })

  private val issuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val issuedToken = Reg(Vec(Entries, new RobToken(IndexBits, GenerationBits)))

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

  private val age = Wire(Vec(Entries, UInt(IndexBits.W)))
  for (index <- 0 until Entries) {
    age(index) := (index.U(IndexBits.W) - io.headIndex)(IndexBits - 1, 0)
  }

  private def olderBlocksBypass(entry: TinySchedulingEntry, entryAge: UInt): Bool = {
    val olderIsMemory = entry.valid &&
      entry.uop.executionClass === ExecutionClass.Memory
    val olderMemoryLaunched =
      entryAge === 0.U &&
        entry.dependenciesValid && entry.operandsReady && !io.block
    entry.valid && (
      entry.uop.executionClass === ExecutionClass.System ||
      entry.uop.decoded.ordering =/= OrderingClass.Normal ||
      entry.uop.decoded.exception.valid ||
      (olderIsMemory && !olderMemoryLaunched)
    )
  }

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  for (candidate <- 0 until Entries) {
    val blockers = (0 until Entries).map { older =>
      val isOlder = age(older) < age(candidate)
      isOlder && olderBlocksBypass(io.slots(older), age(older))
    }
    bypassOpen(candidate) := !blockers.reduce(_ || _)
  }

  private val eligible = Wire(Vec(Entries, Bool()))
  private val candidates =
    Wire(Vec(Entries, new ExecutionRequest(xlen, IndexBits, GenerationBits)))

  private def materializeSource(
      entry: TinySchedulingEntry,
      kind: OperandSourceKind.Type
  ): UInt = {
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

  for (index <- 0 until Entries) {
    val entry = io.slots(index)
    val token = entry.uop.robToken
    val alreadyIssued =
      issuedValid(index) && sameRobToken(issuedToken(index), token)
    val safeClass =
      entry.uop.executionClass === ExecutionClass.Integer ||
        entry.uop.executionClass === ExecutionClass.MulDiv

    eligible(index) := bypassOpen(index) &&
      entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !entry.uop.decoded.exception.valid &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      safeClass &&
      resourceReady(entry) &&
      !alreadyIssued

    val candidate =
      WireDefault(0.U.asTypeOf(new ExecutionRequest(xlen, IndexBits, GenerationBits)))
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
    candidates(index) := candidate
  }

  // Iterate youngest age to oldest age so the final connect leaves age0 as the
  // winner. Age compares are only two bits; the sole wide mux is the final
  // ExecutionRequest selection.
  private val selectedValid = WireDefault(false.B)
  private val selectedRequest =
    WireDefault(0.U.asTypeOf(new ExecutionRequest(xlen, IndexBits, GenerationBits)))
  for (wantedAge <- (Entries - 1) to 0 by -1) {
    for (index <- 0 until Entries) {
      when(eligible(index) && age(index) === wantedAge.U) {
        selectedValid := true.B
        selectedRequest := candidates(index)
      }
    }
  }

  io.request.valid := selectedValid && !io.block
  io.request.bits := selectedRequest

  when(io.allocated.valid) {
    issuedValid(io.allocated.bits.robToken.index) := false.B
  }
  when(io.request.fire) {
    val token = io.request.bits.robToken
    issuedValid(token.index) := true.B
    issuedToken(token.index) := token
  }
}

/**
  * Physical-slot equivalent of TinyLoadQueueIssue.
  *
  * Bypass legality is unchanged; only the representation of age changes from a
  * wide reordered window to the two-bit modulo-4 physical-slot distance.
  */
class TinyPhysicalLoadQueueIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64)

  private val Entries = TinyRobGeometry.Entries
  private val IndexBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val Slots = 2

  val io = IO(new Bundle {
    val slots = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val headIndex = Input(UInt(IndexBits.W))
    val allocated = Flipped(Valid(new BackendUop(xlen, IndexBits, GenerationBits)))
    val block = Input(Bool())
    val available = Input(Bool())
    val bypassable = Input(Vec(Slots, Valid(new RobToken(IndexBits, GenerationBits))))
    val request = Decoupled(new TinyMemoryRequest(xlen, IndexBits, GenerationBits))
    val preHead = Output(Bool())
  })

  private val issuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val issuedToken = Reg(Vec(Entries, new RobToken(IndexBits, GenerationBits)))

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private def tokenBypassable(token: RobToken): Bool =
    io.bypassable.map(slot => slot.valid && sameToken(slot.bits, token)).reduce(_ || _)

  private def ordinaryNormalLoad(entry: TinySchedulingEntry): Bool =
    entry.valid &&
      entry.uop.executionClass === ExecutionClass.Memory &&
      entry.uop.decoded.memory.kind === MemoryOperationKind.Load &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid

  private def olderMayBeCrossed(entry: TinySchedulingEntry): Bool = {
    val pureCompute = entry.valid &&
      (entry.uop.executionClass === ExecutionClass.Integer ||
        entry.uop.executionClass === ExecutionClass.MulDiv) &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid

    val safeOlderLoad =
      ordinaryNormalLoad(entry) &&
        (entry.complete || tokenBypassable(entry.uop.robToken))

    val completedNormalBranch = entry.valid &&
      entry.complete &&
      entry.uop.executionClass === ExecutionClass.Branch &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid

    pureCompute || safeOlderLoad || completedNormalBranch
  }

  private val age = Wire(Vec(Entries, UInt(IndexBits.W)))
  for (index <- 0 until Entries) {
    age(index) := (index.U(IndexBits.W) - io.headIndex)(IndexBits - 1, 0)
  }

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  for (candidate <- 0 until Entries) {
    val blockers = (0 until Entries).map { older =>
      (age(older) < age(candidate)) &&
        io.slots(older).valid &&
        !olderMayBeCrossed(io.slots(older))
    }
    bypassOpen(candidate) := !blockers.reduce(_ || _)
  }

  private val eligible = Wire(Vec(Entries, Bool()))
  private val candidates =
    Wire(Vec(Entries, new TinyMemoryRequest(xlen, IndexBits, GenerationBits)))

  for (index <- 0 until Entries) {
    val entry = io.slots(index)
    val token = entry.uop.robToken
    val alreadyIssued =
      issuedValid(index) && sameToken(issuedToken(index), token)

    eligible(index) := bypassOpen(index) &&
      ordinaryNormalLoad(entry) &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !alreadyIssued

    val candidate =
      WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IndexBits, GenerationBits)))
    candidate.robToken := token
    candidate.producerTag := entry.uop.producerTag
    candidate.valueRef := entry.uop.valueRef
    candidate.kind := entry.uop.decoded.memory.kind
    candidate.size := entry.uop.decoded.memory.size
    candidate.unsigned := entry.uop.decoded.memory.unsigned
    candidate.atomicOp := entry.uop.decoded.memory.atomicOp
    candidate.base := entry.rs1.value
    candidate.offset := entry.uop.decoded.immediate
    candidate.storeData := entry.rs2.value
    candidate.rawInst := entry.uop.decoded.rawInst
    candidates(index) := candidate
  }

  private val selectedValid = WireDefault(false.B)
  private val selectedAge = WireDefault(0.U(IndexBits.W))
  private val selectedRequest =
    WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IndexBits, GenerationBits)))

  for (wantedAge <- (Entries - 1) to 0 by -1) {
    for (index <- 0 until Entries) {
      when(eligible(index) && age(index) === wantedAge.U) {
        selectedValid := true.B
        selectedAge := age(index)
        selectedRequest := candidates(index)
      }
    }
  }

  io.request.valid := selectedValid && io.available && !io.block
  io.request.bits := selectedRequest
  io.preHead := io.request.valid && selectedAge =/= 0.U

  when(io.allocated.valid) {
    issuedValid(io.allocated.bits.robToken.index) := false.B
  }
  when(io.request.fire) {
    val token = io.request.bits.robToken
    issuedValid(token.index) := true.B
    issuedToken(token.index) := token
  }
}
