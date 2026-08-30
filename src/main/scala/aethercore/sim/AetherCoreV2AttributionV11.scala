package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.common.AluOp
import aethercore.core.v2.{ExecutionClass, MemoryOperationKind, OrderingClass}

/** Observation-only facts for the second P8 attribution slice.
  *
  * v1.1 deliberately does not reinterpret the frozen Top-Down/Critical-CPI
  * counters. It adds orthogonal evidence for four questions that v1 could not
  * answer safely: branch recovery cost, issue-slot opportunity, compressed
  * second-parcel frontend bubbles, and real LSU completion backpressure.
  */
class V2AttributionV11Events extends Bundle {
  val branchResolved = Bool()
  val branchTaken = Bool()
  val branchRecovery = Bool()
  val branchSquashedUops = UInt(3.W)

  val robNonEmpty = Bool()
  val issueLaunch = Bool()
  val issueRequestVisible = Bool()
  val shadowComputeReadyCount = UInt(3.W)

  val frontendSecondParcel = Bool()
  val frontendBound = Bool()

  val memoryTerminalValid = Bool()
  val memoryTerminalReady = Bool()

  // Architecture pre-decision opportunities. These are observation-only:
  // no production scheduling decision consumes them.
  val dualDomainPairable = Bool()
  val robFullDispatchPressure = Bool()
  val loadQueueFullReadyLoad = Bool()
  val criticalLoadHead = Bool()
  val criticalStoreAtomicHead = Bool()
}

class V2AttributionV11Counters extends Bundle {
  val cycles = UInt(64.W)

  val branchResolved = UInt(64.W)
  val branchTaken = UInt(64.W)
  val branchRecovery = UInt(64.W)
  val branchSquashedUops = UInt(64.W)

  val issueLaunch = UInt(64.W)
  val issueIdleLaunchable = UInt(64.W)
  val issueIdleNoLaunchable = UInt(64.W)
  val issueInactive = UInt(64.W)
  val shadowComputeReady = UInt(64.W)
  val dualComputeCandidate = UInt(64.W)

  val frontendSecondParcel = UInt(64.W)
  val frontendBoundSecondParcel = UInt(64.W)

  val memoryTerminalValid = UInt(64.W)
  val memoryTerminalFire = UInt(64.W)
  val memoryTerminalHold = UInt(64.W)

  val dualDomainPairable = UInt(64.W)
  val robFullDispatchPressure = UInt(64.W)
  val loadQueueFullReadyLoad = UInt(64.W)
  val criticalLoadHead = UInt(64.W)
  val criticalStoreAtomicHead = UInt(64.W)
}

/** Small independently-testable accumulator for P8 attribution v1.1. */
class V2AttributionV11CounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2AttributionV11Events)
    val counters = Output(new V2AttributionV11Counters)
  })

  private val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  private val squashedUops = RegInit(0.U(64.W))
  when(io.events.branchRecovery) {
    squashedUops := squashedUops + io.events.branchSquashedUops
  }

  // A recovery in the current not-taken frontend is a taken branch completion
  // accepted by the ROB. Keep these relations executable so a future predictor
  // cannot silently change the meaning of the counters.
  when(io.events.branchRecovery) {
    assert(io.events.branchResolved && io.events.branchTaken)
  }
  when(io.events.branchTaken) {
    assert(io.events.branchResolved)
  }

  // "Launchable" is intentionally stricter wording than "ready". A production
  // request is visible only after the real policy/resource gates. The shadow
  // count mirrors the compute selector's once-only scoreboard and candidate
  // rules before the final global single-issue arbitration. This prevents an
  // already issued long-latency uOp from being counted as fresh opportunity.
  private val issueLaunchable =
    io.events.issueRequestVisible || io.events.shadowComputeReadyCount =/= 0.U
  private val issueLaunch = io.events.issueLaunch
  private val issueIdleLaunchable = !issueLaunch && issueLaunchable
  private val issueIdleNoLaunchable =
    !issueLaunch && !issueLaunchable && io.events.robNonEmpty
  private val issueInactive =
    !issueLaunch && !issueLaunchable && !io.events.robNonEmpty

  assert(PopCount(Cat(
    issueLaunch,
    issueIdleLaunchable,
    issueIdleNoLaunchable,
    issueInactive
  )) === 1.U)

  private val memoryTerminalFire =
    io.events.memoryTerminalValid && io.events.memoryTerminalReady
  private val memoryTerminalHold =
    io.events.memoryTerminalValid && !io.events.memoryTerminalReady
  when(io.events.memoryTerminalValid) {
    assert(PopCount(Cat(memoryTerminalFire, memoryTerminalHold)) === 1.U)
  }

  io.counters.cycles := cycles

  io.counters.branchResolved := count(io.events.branchResolved)
  io.counters.branchTaken := count(io.events.branchTaken)
  io.counters.branchRecovery := count(io.events.branchRecovery)
  io.counters.branchSquashedUops := squashedUops

  io.counters.issueLaunch := count(issueLaunch)
  io.counters.issueIdleLaunchable := count(issueIdleLaunchable)
  io.counters.issueIdleNoLaunchable := count(issueIdleNoLaunchable)
  io.counters.issueInactive := count(issueInactive)
  io.counters.shadowComputeReady := count(io.events.shadowComputeReadyCount =/= 0.U)
  io.counters.dualComputeCandidate := count(io.events.shadowComputeReadyCount >= 2.U)

  io.counters.frontendSecondParcel := count(io.events.frontendSecondParcel)
  io.counters.frontendBoundSecondParcel := count(
    io.events.frontendSecondParcel && io.events.frontendBound
  )

  io.counters.memoryTerminalValid := count(io.events.memoryTerminalValid)
  io.counters.memoryTerminalFire := count(memoryTerminalFire)
  io.counters.memoryTerminalHold := count(memoryTerminalHold)

  io.counters.dualDomainPairable := count(io.events.dualDomainPairable)
  io.counters.robFullDispatchPressure := count(io.events.robFullDispatchPressure)
  io.counters.loadQueueFullReadyLoad := count(io.events.loadQueueFullReadyLoad)
  io.counters.criticalLoadHead := count(io.events.criticalLoadHead)
  io.counters.criticalStoreAtomicHead := count(io.events.criticalStoreAtomicHead)
}

/**
  * v1.1 host-visible attribution layered on top of the exact v1 implementation.
  *
  * No production module gains an input or scheduling decision. All production
  * internals are observed with BoringUtils taps, just like the v1 attribution
  * seam. The only state added here is a simulation-only mirror of
  * TinySelectiveComputeIssue's once-only issue scoreboard.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttribution {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val bank = Module(new V2AttributionV11CounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2AttributionV11Events))

  // Chisel visibility intentionally forbids directly reaching into the nested
  // ROB from this host wrapper. Use observation taps so instrumentation does not
  // widen any production interface merely to expose a counter fact.
  private val acceptedCompletionValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.valid)
  private val acceptedCompletionBranchValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.bits.branchValid)
  private val acceptedCompletionBranchTaken =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.bits.branchTaken)
  private val acceptedRecoveryValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.acceptedRecovery.valid)

  private val observedOccupancy = BoringUtils.tapAndRead(core.io.occupancy)
  private val observedFrontendPc = BoringUtils.tapAndRead(core.io.frontendPc)

  events.branchResolved := acceptedCompletionValid && acceptedCompletionBranchValid
  events.branchTaken := events.branchResolved && acceptedCompletionBranchTaken
  events.branchRecovery := acceptedRecoveryValid
  events.branchSquashedUops := Mux(
    events.branchRecovery,
    observedOccupancy - 1.U,
    0.U
  )
  when(events.branchRecovery) {
    assert(observedOccupancy >= 1.U)
  }

  private val branchRequestValid = BoringUtils.tapAndRead(core.backend.branchIssue.io.request.valid)
  private val branchRequestReady = BoringUtils.tapAndRead(core.backend.branchIssue.io.request.ready)
  private val computeRequestValid = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.valid)
  private val computeRequestReady = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.ready)
  private val computeRequestTokenIndex =
    BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.bits.robToken.index)
  private val computeRequestTokenGeneration =
    BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.bits.robToken.generation)
  private val lsuRequestValid = BoringUtils.tapAndRead(core.backend.lsu.io.request.valid)
  private val lsuRequestReady = BoringUtils.tapAndRead(core.backend.lsu.io.request.ready)

  private val branchFire = branchRequestValid && branchRequestReady
  private val computeFire = computeRequestValid && computeRequestReady
  private val lsuFire = lsuRequestValid && lsuRequestReady

  events.robNonEmpty := observedOccupancy =/= 0.U
  events.issueLaunch := branchFire || computeFire || lsuFire
  events.issueRequestVisible := branchRequestValid || computeRequestValid || lsuRequestValid

  // Mirror only the production compute selector's once-only lifetime bit. The
  // ROB/dependency/uOp data themselves remain read-only views from production.
  private val Entries = core.backend.dependencyBackend.io.schedulingWindow.length
  private val window = VecInit((0 until Entries).map { age =>
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow(age))
  })
  private val allocatedValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.allocated.valid)
  private val allocatedTokenIndex =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.allocated.bits.robToken.index)
  private val selectiveBlock = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.block)
  private val GenerationBits = window(0).uop.robToken.generation.getWidth
  private val shadowIssuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val shadowIssuedGeneration = Reg(Vec(Entries, UInt(GenerationBits.W)))

  when(allocatedValid) {
    shadowIssuedValid(allocatedTokenIndex) := false.B
  }
  when(computeFire) {
    shadowIssuedValid(computeRequestTokenIndex) := true.B
    shadowIssuedGeneration(computeRequestTokenIndex) := computeRequestTokenGeneration
  }

  private def isMultiply(op: AluOp.Type): Bool =
    op === AluOp.Mul || op === AluOp.Mulh || op === AluOp.Mulhsu || op === AluOp.Mulhu

  private def isDivide(op: AluOp.Type): Bool =
    op === AluOp.Div || op === AluOp.Divu || op === AluOp.Rem || op === AluOp.Remu

  private val bypassOpen = Wire(Vec(Entries, Bool()))
  bypassOpen(0) := true.B
  for (age <- 1 until Entries) {
    val older = window(age - 1)
    val olderIsMemory = older.valid && older.uop.executionClass === ExecutionClass.Memory
    val olderMemoryLaunched = if (age == 1) {
      older.dependenciesValid && older.operandsReady && !selectiveBlock
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

  private val availability = BoringUtils.tapAndRead(core.backend.execution.io.computeAvailability)
  private val shadowEligible = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    val entry = window(age)
    val token = entry.uop.robToken
    val alreadyIssued = shadowIssuedValid(token.index) &&
      shadowIssuedGeneration(token.index) === token.generation
    val safeClass = entry.uop.executionClass === ExecutionClass.Integer ||
      entry.uop.executionClass === ExecutionClass.MulDiv
    val op = entry.uop.decoded.aluOp
    val resourceReady =
      (entry.uop.executionClass === ExecutionClass.Integer && availability.integer) ||
      (entry.uop.executionClass === ExecutionClass.MulDiv && isMultiply(op) && availability.multiply) ||
      (entry.uop.executionClass === ExecutionClass.MulDiv && isDivide(op) && availability.divide)

    // selectiveBlock is an architectural/resource barrier, not an issue-width
    // opportunity. A wider issue machine would not reclaim a blocked cycle.
    shadowEligible(age) := !selectiveBlock && bypassOpen(age) &&
      entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !entry.uop.decoded.exception.valid &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      safeClass &&
      resourceReady &&
      !alreadyIssued
  }
  events.shadowComputeReadyCount := PopCount(shadowEligible)

  // Pre-decision shadow for allowing one ordinary Load and one Compute launch
  // in the same cycle. Production currently gives Load issue priority by
  // including loadIssue.request.valid in selectiveIssue.block. Recompute only
  // the compute side with that single issue-width conflict removed; branch,
  // recovery and exact-head Store/Atomic remain architectural/resource barriers.
  private val acceptedPrivilegedRecoveryValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.acceptedPrivilegedRecovery.valid)
  private val loadIssueRequestValid =
    BoringUtils.tapAndRead(core.backend.loadIssue.io.request.valid)
  private val loadUnitFull =
    BoringUtils.tapAndRead(core.backend.loadUnit.io.full)

  private val nonLoadComputeBlock =
    branchRequestValid ||
      acceptedRecoveryValid ||
      acceptedPrivilegedRecoveryValid ||
      lsuRequestValid

  private val shadowComputeIgnoringLoad = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    val entry = window(age)
    val token = entry.uop.robToken
    val alreadyIssued = shadowIssuedValid(token.index) &&
      shadowIssuedGeneration(token.index) === token.generation
    val safeClass = entry.uop.executionClass === ExecutionClass.Integer ||
      entry.uop.executionClass === ExecutionClass.MulDiv
    val op = entry.uop.decoded.aluOp
    val resourceReady =
      (entry.uop.executionClass === ExecutionClass.Integer && availability.integer) ||
      (entry.uop.executionClass === ExecutionClass.MulDiv && isMultiply(op) && availability.multiply) ||
      (entry.uop.executionClass === ExecutionClass.MulDiv && isDivide(op) && availability.divide)

    shadowComputeIgnoringLoad(age) := !nonLoadComputeBlock && bypassOpen(age) &&
      entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      !entry.uop.decoded.exception.valid &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      safeClass &&
      resourceReady &&
      !alreadyIssued
  }
  events.dualDomainPairable :=
    loadIssueRequestValid && shadowComputeIgnoringLoad.asUInt.orR

  // Capacity-pressure proxies. They intentionally do not predict speedup by
  // themselves; the host-side gate treats them as a reason to consider a
  // larger structure only when the affected cycles are material.
  private val anyReadyOrdinaryLoad = window.map { entry =>
    entry.valid &&
      !entry.complete &&
      entry.dependenciesValid &&
      entry.operandsReady &&
      entry.uop.executionClass === ExecutionClass.Memory &&
      entry.uop.decoded.memory.kind === MemoryOperationKind.Load &&
      entry.uop.decoded.ordering === OrderingClass.Normal &&
      !entry.uop.decoded.exception.valid
  }.reduce(_ || _)
  events.loadQueueFullReadyLoad := loadUnitFull && anyReadyOrdinaryLoad

  private val fetchRequestValid = BoringUtils.tapAndRead(core.fetch.io.requestValid)
  private val secondParcel = core.parcel match {
    case Some(parcelController) =>
      val parcelRequestAddress =
        BoringUtils.tapAndRead(parcelController.io.parcelRequestAddress)
      fetchRequestValid && parcelRequestAddress === (observedFrontendPc + 2.U)
    case None =>
      // The RV64 Linux P8 profile currently has no compressed parcel
      // controller. Keep the counter explicit and configuration-safe rather
      // than pretending every frontend has an RVC second-parcel path.
      false.B
  }
  events.frontendSecondParcel := secondParcel

  private val dispatchValid = BoringUtils.tapAndRead(core.backend.io.dispatch.valid)
  private val dispatchReady = BoringUtils.tapAndRead(core.backend.io.dispatch.ready)
  events.frontendBound := !dispatchValid && dispatchReady
  events.robFullDispatchPressure :=
    dispatchValid && !dispatchReady && observedOccupancy === Entries.U

  private val observedCommitValid = BoringUtils.tapAndRead(core.io.commit.valid)
  private val headEntry = window(0)
  private val criticalHead = !observedCommitValid && headEntry.valid
  events.criticalLoadHead :=
    criticalHead &&
      headEntry.uop.executionClass === ExecutionClass.Memory &&
      headEntry.uop.decoded.memory.kind === MemoryOperationKind.Load
  events.criticalStoreAtomicHead :=
    criticalHead &&
      headEntry.uop.executionClass === ExecutionClass.Memory &&
      headEntry.uop.decoded.memory.kind =/= MemoryOperationKind.Load

  private val terminalValid = BoringUtils.tapAndRead(core.backend.lsu.io.completion.valid)
  private val terminalReady = BoringUtils.tapAndRead(core.backend.lsu.io.completion.ready)
  events.memoryTerminalValid := terminalValid
  events.memoryTerminalReady := terminalReady

  bank.io.events := events

  val ioV11Cycles = IO(Output(UInt(64.W)))
  val ioV11BranchResolved = IO(Output(UInt(64.W)))
  val ioV11BranchTaken = IO(Output(UInt(64.W)))
  val ioV11BranchRecovery = IO(Output(UInt(64.W)))
  val ioV11BranchSquashedUops = IO(Output(UInt(64.W)))

  val ioV11IssueLaunch = IO(Output(UInt(64.W)))
  val ioV11IssueIdleLaunchable = IO(Output(UInt(64.W)))
  val ioV11IssueIdleNoLaunchable = IO(Output(UInt(64.W)))
  val ioV11IssueInactive = IO(Output(UInt(64.W)))
  val ioV11ShadowComputeReady = IO(Output(UInt(64.W)))
  val ioV11DualComputeCandidate = IO(Output(UInt(64.W)))

  val ioV11FrontendSecondParcel = IO(Output(UInt(64.W)))
  val ioV11FrontendBoundSecondParcel = IO(Output(UInt(64.W)))

  val ioV11MemoryTerminalValid = IO(Output(UInt(64.W)))
  val ioV11MemoryTerminalFire = IO(Output(UInt(64.W)))
  val ioV11MemoryTerminalHold = IO(Output(UInt(64.W)))

  val ioArchDualDomainPairable = IO(Output(UInt(64.W)))
  val ioArchRobFullDispatchPressure = IO(Output(UInt(64.W)))
  val ioArchLoadQueueFullReadyLoad = IO(Output(UInt(64.W)))
  val ioArchCriticalLoadHead = IO(Output(UInt(64.W)))
  val ioArchCriticalStoreAtomicHead = IO(Output(UInt(64.W)))

  ioV11Cycles := bank.io.counters.cycles
  ioV11BranchResolved := bank.io.counters.branchResolved
  ioV11BranchTaken := bank.io.counters.branchTaken
  ioV11BranchRecovery := bank.io.counters.branchRecovery
  ioV11BranchSquashedUops := bank.io.counters.branchSquashedUops

  ioV11IssueLaunch := bank.io.counters.issueLaunch
  ioV11IssueIdleLaunchable := bank.io.counters.issueIdleLaunchable
  ioV11IssueIdleNoLaunchable := bank.io.counters.issueIdleNoLaunchable
  ioV11IssueInactive := bank.io.counters.issueInactive
  ioV11ShadowComputeReady := bank.io.counters.shadowComputeReady
  ioV11DualComputeCandidate := bank.io.counters.dualComputeCandidate

  ioV11FrontendSecondParcel := bank.io.counters.frontendSecondParcel
  ioV11FrontendBoundSecondParcel := bank.io.counters.frontendBoundSecondParcel

  ioV11MemoryTerminalValid := bank.io.counters.memoryTerminalValid
  ioV11MemoryTerminalFire := bank.io.counters.memoryTerminalFire
  ioV11MemoryTerminalHold := bank.io.counters.memoryTerminalHold

  ioArchDualDomainPairable := bank.io.counters.dualDomainPairable
  ioArchRobFullDispatchPressure := bank.io.counters.robFullDispatchPressure
  ioArchLoadQueueFullReadyLoad := bank.io.counters.loadQueueFullReadyLoad
  ioArchCriticalLoadHead := bank.io.counters.criticalLoadHead
  ioArchCriticalStoreAtomicHead := bank.io.counters.criticalStoreAtomicHead
}
