package aethercore.core.v2

import chisel3._
import chisel3.util._

/**
  * ROB4 selector for the bounded two-slot replay-safe Load experiment.
  *
  * The selector retains all #187 barriers except one evidence-driven widening:
  * an older ordinary Load may be crossed only after the dual Load unit has
  * already externalized that exact lifetime from a replay-safe PMA region (or
  * after the older Load has completed). An unresolved/unsafe Load therefore
  * remains a hard barrier, as do Store/Atomic, Branch, System, ordering and
  * known-exception boundaries.
  */
class TinyLoadQueueIssue(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64)

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val Slots = 2

  val io = IO(new Bundle {
    val window = Input(Vec(Entries, new TinySchedulingEntry(xlen)))
    val allocated = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val block = Input(Bool())
    val available = Input(Bool())
    val bypassable = Input(Vec(Slots, Valid(new RobToken(IdentityBits, GenerationBits))))
    val request = Decoupled(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits))
    val preHead = Output(Bool())

    // Observation-only counter seams. These report counterfactual issue
    // opportunities without changing the qualified selector policy.
    val capacityBlockedOpportunity = Output(Bool())
    val completedBranchBarrierOpportunity = Output(Bool())
    val completedStoreBarrierOpportunity = Output(Bool())
  })

  private val issuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val issuedToken = Reg(Vec(Entries, new RobToken(IdentityBits, GenerationBits)))

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

    val safeOlderLoad = ordinaryNormalLoad(entry) &&
      (entry.complete || tokenBypassable(entry.uop.robToken))

    pureCompute || safeOlderLoad
  }

  private def completedNormalBranch(entry: TinySchedulingEntry): Bool =
    entry.valid &&
      entry.complete &&
      entry.uop.executionClass === ExecutionClass.Branch &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid

  private def completedNormalStore(entry: TinySchedulingEntry): Bool =
    entry.valid &&
      entry.complete &&
      entry.uop.executionClass === ExecutionClass.Memory &&
      entry.uop.decoded.memory.kind === MemoryOperationKind.Store &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  private val branchRelaxedBypassOpen = Wire(Vec(Entries, Bool()))
  private val storeRelaxedBypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  branchRelaxedBypassOpen(0) := true.B
  storeRelaxedBypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    val older = io.window(age - 1)
    bypassOpen(age) := bypassOpen(age - 1) && olderMayBeCrossed(older)
    branchRelaxedBypassOpen(age) :=
      branchRelaxedBypassOpen(age - 1) &&
        (olderMayBeCrossed(older) || completedNormalBranch(older))
    storeRelaxedBypassOpen(age) :=
      storeRelaxedBypassOpen(age - 1) &&
        (olderMayBeCrossed(older) || completedNormalStore(older))
  }

  private val eligible = Wire(Vec(Entries, Bool()))
  private val branchRelaxedEligible = Wire(Vec(Entries, Bool()))
  private val storeRelaxedEligible = Wire(Vec(Entries, Bool()))
  private val candidates = Wire(Vec(Entries, new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))

  for (age <- 0 until Entries) {
    val entry = io.window(age)
    val token = entry.uop.robToken
    val alreadyIssued = issuedValid(token.index) && sameToken(issuedToken(token.index), token)

    val candidateReady =
      ordinaryNormalLoad(entry) &&
        !entry.complete &&
        entry.dependenciesValid &&
        entry.operandsReady &&
        !alreadyIssued

    eligible(age) := bypassOpen(age) && candidateReady
    branchRelaxedEligible(age) := branchRelaxedBypassOpen(age) && candidateReady
    storeRelaxedEligible(age) := storeRelaxedBypassOpen(age) && candidateReady

    val candidate = WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))
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
    candidates(age) := candidate
  }

  // Oldest eligible Load wins.
  private val selectedValid = WireDefault(false.B)
  private val selectedAge = WireDefault(0.U(log2Ceil(Entries).W))
  private val selectedRequest = WireDefault(0.U.asTypeOf(new TinyMemoryRequest(xlen, IdentityBits, GenerationBits)))
  for (age <- (Entries - 1) to 0 by -1) {
    when(eligible(age)) {
      selectedValid := true.B
      selectedAge := age.U
      selectedRequest := candidates(age)
    }
  }

  val branchRelaxedSelectedValid = branchRelaxedEligible.asUInt.orR
  val storeRelaxedSelectedValid = storeRelaxedEligible.asUInt.orR

  io.request.valid := selectedValid && io.available && !io.block
  io.request.bits := selectedRequest
  io.preHead := io.request.valid && selectedAge =/= 0.U

  // Count only opportunities that are blocked by exactly the named condition
  // under the current one-launch/resource constraints.
  io.capacityBlockedOpportunity := selectedValid && !io.available && !io.block
  io.completedBranchBarrierOpportunity :=
    !selectedValid && branchRelaxedSelectedValid && io.available && !io.block
  io.completedStoreBarrierOpportunity :=
    !selectedValid && storeRelaxedSelectedValid && io.available && !io.block

  when(io.allocated.valid) {
    issuedValid(io.allocated.bits.robToken.index) := false.B
  }

  when(io.request.fire) {
    val token = io.request.bits.robToken
    issuedValid(token.index) := true.B
    issuedToken(token.index) := token
  }
}
