package aethercore.sim

import chisel3._
import chisel3.util._
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
  // rules before the final global single-issue block. This prevents an already
  // issued long-latency uOp from being counted as fresh opportunity.
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
}

/**
  * v1.1 host-visible attribution layered on top of the exact v1 implementation.
  *
  * No production module gains an input or scheduling decision. The only state
  * added here is a simulation-only mirror of TinySelectiveComputeIssue's issued
  * generation scoreboard, used to avoid false "ready work" claims for uOps that
  * have already launched and are merely waiting for completion.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttribution {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val bank = Module(new V2AttributionV11CounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2AttributionV11Events))

  private val acceptedCompletion = core.backend.dependencyBackend.rob.io.acceptedCompletion
  events.branchResolved := acceptedCompletion.valid && acceptedCompletion.bits.branchValid
  events.branchTaken := events.branchResolved && acceptedCompletion.bits.branchTaken
  events.branchRecovery := core.backend.dependencyBackend.io.acceptedRecovery.valid
  events.branchSquashedUops := Mux(
    events.branchRecovery,
    core.io.occupancy - 1.U,
    0.U
  )
  when(events.branchRecovery) {
    assert(core.io.occupancy >= 1.U)
  }

  private val branchRequest = core.backend.branchIssue.io.request
  private val computeRequest = core.backend.selectiveIssue.io.request
  private val lsuRequest = core.backend.lsu.io.request
  events.robNonEmpty := core.io.occupancy =/= 0.U
  events.issueLaunch := branchRequest.fire || computeRequest.fire || lsuRequest.fire
  events.issueRequestVisible := branchRequest.valid || computeRequest.valid || lsuRequest.valid

  // Mirror only the production compute selector's once-only lifetime bit. The
  // ROB/dependency/uOp data themselves remain read-only views from production.
  private val window = core.backend.dependencyBackend.io.schedulingWindow
  private val Entries = window.length
  private val GenerationBits = window(0).uop.robToken.generation.getWidth
  private val shadowIssuedValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  private val shadowIssuedGeneration = Reg(Vec(Entries, UInt(GenerationBits.W)))

  when(core.backend.dependencyBackend.io.allocated.valid) {
    shadowIssuedValid(core.backend.dependencyBackend.io.allocated.bits.robToken.index) := false.B
  }
  when(computeRequest.fire) {
    shadowIssuedValid(computeRequest.bits.robToken.index) := true.B
    shadowIssuedGeneration(computeRequest.bits.robToken.index) := computeRequest.bits.robToken.generation
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
      older.dependenciesValid && older.operandsReady && !core.backend.selectiveIssue.io.block
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

  private val availability = core.backend.execution.io.computeAvailability
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

    shadowEligible(age) := bypassOpen(age) &&
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

  private val secondParcel = core.fetch.io.requestValid &&
    core.parcel.get.io.parcelRequestAddress === (core.io.frontendPc + 2.U)
  events.frontendSecondParcel := secondParcel
  events.frontendBound := !core.backend.io.dispatch.valid && core.backend.io.dispatch.ready

  private val terminal = core.backend.lsu.io.completion
  events.memoryTerminalValid := terminal.valid
  events.memoryTerminalReady := terminal.ready

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
}
