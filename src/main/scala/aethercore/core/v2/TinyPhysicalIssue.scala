package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.AluOp

/**
  * FPGA-oriented selective compute selector over physical ROB slots.
  *
  * Wide BackendUop data stays in physical-slot order. Architectural age is
  * represented only by tiny metadata:
  *
  *   physical slot -> local 1-bit barrier/eligible flags
  *   head rotation -> four 1-bit age-ordered flags
  *   prefix barrier -> oldest-ready age
  *   head + age -> one physical slot
  *   one final wide ExecutionRequest mux
  *
  * This deliberately avoids both the legacy wide age-reorder crossbar and the
  * first physical-slot experiment's O(N^2) all-to-all age-comparison network.
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

  private def materializeSource(
      kind: UInt,
      rs1Value: UInt,
      rs2Value: UInt,
      pc: UInt,
      immediate: UInt
  ): UInt = {
    val value = WireDefault(0.U(xlen.W))
    switch(kind) {
      is(OperandSourceKind.Zero.asUInt)      { value := 0.U }
      is(OperandSourceKind.Rs1.asUInt)       { value := rs1Value }
      is(OperandSourceKind.Rs2.asUInt)       { value := rs2Value }
      is(OperandSourceKind.Pc.asUInt)        { value := pc }
      is(OperandSourceKind.Immediate.asUInt) { value := immediate }
    }
    value
  }

  // All expensive decode/readiness work is evaluated once per physical slot.
  private val slotBarrier = Wire(Vec(Entries, Bool()))
  private val slotEligibleBase = Wire(Vec(Entries, Bool()))
  private val candidates =
    Wire(Vec(Entries, new ExecutionRequest(xlen, IndexBits, GenerationBits)))

  for (index <- 0 until Entries) {
    val entry = io.slots(index)
    val token = entry.uop.robToken
    val isHead = io.headIndex === index.U

    val isMemory =
      entry.valid && entry.uop.executionClass === ExecutionClass.Memory
    val headMemoryLaunched =
      isHead && entry.dependenciesValid && entry.operandsReady && !io.block

    slotBarrier(index) := entry.valid && (
      entry.uop.executionClass === ExecutionClass.System ||
      entry.uop.decoded.ordering =/= OrderingClass.Normal ||
      entry.uop.decoded.exception.valid ||
      (isMemory && !headMemoryLaunched)
    )

    val alreadyIssued =
      issuedValid(index) && sameRobToken(issuedToken(index), token)
    val safeClass =
      entry.uop.executionClass === ExecutionClass.Integer ||
        entry.uop.executionClass === ExecutionClass.MulDiv

    slotEligibleBase(index) :=
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
    // Keep operand materialization out of the per-slot candidate cone. The
    // selected physical slot is known a few lines below, so the 64-bit
    // Rs1/Rs2/Pc/Immediate source mux only needs to exist once.
    candidate.lhs := 0.U
    candidate.rhs := 0.U
    candidate.pc := entry.uop.decoded.pc
    candidate.instBytes := entry.uop.decoded.instBytes
    candidate.immediate := entry.uop.decoded.immediate
    candidates(index) := candidate
  }

  // Rotate only one-bit metadata into age order.
  private val ageBarrier = Wire(Vec(Entries, Bool()))
  private val ageEligibleBase = Wire(Vec(Entries, Bool()))
  private val agePhysical = Wire(Vec(Entries, UInt(IndexBits.W)))
  for (age <- 0 until Entries) {
    agePhysical(age) := (io.headIndex + age.U)(IndexBits - 1, 0)
    ageBarrier(age) := slotBarrier(agePhysical(age))
    ageEligibleBase(age) := slotEligibleBase(agePhysical(age))
  }

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    bypassOpen(age) := bypassOpen(age - 1) && !ageBarrier(age - 1)
  }

  private val ageEligible = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    ageEligible(age) := bypassOpen(age) && ageEligibleBase(age)
  }

  private val selectedValid = ageEligible.asUInt.orR
  private val selectedAge = PriorityEncoder(ageEligible.asUInt)
  private val selectedPhysical =
    (io.headIndex + selectedAge)(IndexBits - 1, 0)
  private val selectedOh = UIntToOH(selectedPhysical, Entries)
  private val selectedRequestBase = Mux1H(selectedOh, candidates)
  private val selectedRs1 = Mux1H(selectedOh, io.slots.map(_.rs1.value))
  private val selectedRs2 = Mux1H(selectedOh, io.slots.map(_.rs2.value))
  // Mux the ChiselEnum source kinds as their explicit 3-bit encodings.
  // Keeping the enum out of Mux1H avoids Verilator width-expansion warnings
  // while preserving the exact same source-selection semantics.
  private val selectedLhsSource =
    Mux1H(selectedOh, io.slots.map(_.uop.decoded.lhsSource.asUInt))
  private val selectedRhsSource =
    Mux1H(selectedOh, io.slots.map(_.uop.decoded.rhsSource.asUInt))
  private val selectedRequest = WireDefault(selectedRequestBase)
  selectedRequest.lhs := materializeSource(
    selectedLhsSource,
    selectedRs1,
    selectedRs2,
    selectedRequestBase.pc,
    selectedRequestBase.immediate
  )
  selectedRequest.rhs := materializeSource(
    selectedRhsSource,
    selectedRs1,
    selectedRs2,
    selectedRequestBase.pc,
    selectedRequestBase.immediate
  )

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
  * Compact-metadata physical-slot equivalent of TinyLoadQueueIssue.
  *
  * Like compute issue, only 1-bit barrier/eligibility metadata is rotated into
  * age order. The wide TinyMemoryRequest is muxed exactly once after the oldest
  * eligible age has been selected.
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

  private val slotBarrier = Wire(Vec(Entries, Bool()))
  private val slotEligibleBase = Wire(Vec(Entries, Bool()))
  private val candidates =
    Wire(Vec(Entries, new TinyMemoryRequest(xlen, IndexBits, GenerationBits)))

  for (index <- 0 until Entries) {
    val entry = io.slots(index)
    val token = entry.uop.robToken
    val alreadyIssued =
      issuedValid(index) && sameToken(issuedToken(index), token)

    slotBarrier(index) := entry.valid && !olderMayBeCrossed(entry)

    slotEligibleBase(index) :=
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

  private val ageBarrier = Wire(Vec(Entries, Bool()))
  private val ageEligibleBase = Wire(Vec(Entries, Bool()))
  private val agePhysical = Wire(Vec(Entries, UInt(IndexBits.W)))
  for (age <- 0 until Entries) {
    agePhysical(age) := (io.headIndex + age.U)(IndexBits - 1, 0)
    ageBarrier(age) := slotBarrier(agePhysical(age))
    ageEligibleBase(age) := slotEligibleBase(agePhysical(age))
  }

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    bypassOpen(age) := bypassOpen(age - 1) && !ageBarrier(age - 1)
  }

  private val ageEligible = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    ageEligible(age) := bypassOpen(age) && ageEligibleBase(age)
  }

  private val selectedValid = ageEligible.asUInt.orR
  private val selectedAge = PriorityEncoder(ageEligible.asUInt)
  private val selectedPhysical =
    (io.headIndex + selectedAge)(IndexBits - 1, 0)
  private val selectedRequest =
    Mux1H(UIntToOH(selectedPhysical, Entries), candidates)

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
