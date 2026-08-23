package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.common.AluOp
import aethercore.core.v2.ExecutionClass

/** One-cycle event sample for the P8.0 performance measurement contract.
  *
  * These signals are observation only. No performance event is consumed by the
  * production core, scheduler, completion network, LSU or Commit path.
  */
class V2PerformanceEvents extends Bundle {
  val commit = Bool()
  val dispatchAccepted = Bool()
  val dispatchBlocked = Bool()
  val robOccupancy = UInt(3.W)

  val selectiveCandidate = Bool()
  val integerIssue = Bool()
  val multiplyIssue = Bool()
  val divideIssue = Bool()
  val branchIssue = Bool()
  val memoryIssue = Bool()
  val systemCompletion = Bool()
  val selectiveBypassIssue = Bool()

  val headNotReady = Bool()
  val commitIdleRobNonEmpty = Bool()
  val lsuBusy = Bool()
  val memoryLaunchBlocked = Bool()
  val memoryRequest = Bool()
  val memoryResponse = Bool()
  val ptwActive = Bool()
  val systemHead = Bool()

  val completionCollision = Bool()
  val completionBackpressure = Bool()
  val lsuComputeOverlapIssue = Bool()
}

/** Accumulated P8.0 counters. All counters live only in the simulation shell. */
class V2PerformanceCounters extends Bundle {
  val cycles = UInt(64.W)
  val commits = UInt(64.W)
  val dispatchAccepted = UInt(64.W)
  val dispatchBlocked = UInt(64.W)

  val robOccupancy0 = UInt(64.W)
  val robOccupancy1 = UInt(64.W)
  val robOccupancy2 = UInt(64.W)
  val robOccupancy3 = UInt(64.W)
  val robOccupancy4 = UInt(64.W)

  val selectiveCandidate = UInt(64.W)
  val integerIssue = UInt(64.W)
  val multiplyIssue = UInt(64.W)
  val divideIssue = UInt(64.W)
  val branchIssue = UInt(64.W)
  val memoryIssue = UInt(64.W)
  val systemCompletion = UInt(64.W)
  val selectiveBypassIssue = UInt(64.W)

  val headNotReady = UInt(64.W)
  val commitIdleRobNonEmpty = UInt(64.W)
  val lsuBusy = UInt(64.W)
  val memoryLaunchBlocked = UInt(64.W)
  val memoryRequest = UInt(64.W)
  val memoryResponse = UInt(64.W)
  val ptwActive = UInt(64.W)
  val systemHead = UInt(64.W)

  val completionCollision = UInt(64.W)
  val completionBackpressure = UInt(64.W)
  val lsuComputeOverlapIssue = UInt(64.W)
}

/** Pure observation accumulator, kept separate so focused tests can freeze the
  * counter semantics without instantiating a full Linux/OpenSBI platform.
  */
class V2PerformanceCounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2PerformanceEvents)
    val counters = Output(new V2PerformanceCounters)
  })

  private val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) {
      value := value + 1.U
    }
    value
  }

  io.counters.cycles := cycles
  io.counters.commits := count(io.events.commit)
  io.counters.dispatchAccepted := count(io.events.dispatchAccepted)
  io.counters.dispatchBlocked := count(io.events.dispatchBlocked)

  io.counters.robOccupancy0 := count(io.events.robOccupancy === 0.U)
  io.counters.robOccupancy1 := count(io.events.robOccupancy === 1.U)
  io.counters.robOccupancy2 := count(io.events.robOccupancy === 2.U)
  io.counters.robOccupancy3 := count(io.events.robOccupancy === 3.U)
  io.counters.robOccupancy4 := count(io.events.robOccupancy === 4.U)

  io.counters.selectiveCandidate := count(io.events.selectiveCandidate)
  io.counters.integerIssue := count(io.events.integerIssue)
  io.counters.multiplyIssue := count(io.events.multiplyIssue)
  io.counters.divideIssue := count(io.events.divideIssue)
  io.counters.branchIssue := count(io.events.branchIssue)
  io.counters.memoryIssue := count(io.events.memoryIssue)
  io.counters.systemCompletion := count(io.events.systemCompletion)
  io.counters.selectiveBypassIssue := count(io.events.selectiveBypassIssue)

  io.counters.headNotReady := count(io.events.headNotReady)
  io.counters.commitIdleRobNonEmpty := count(io.events.commitIdleRobNonEmpty)
  io.counters.lsuBusy := count(io.events.lsuBusy)
  io.counters.memoryLaunchBlocked := count(io.events.memoryLaunchBlocked)
  io.counters.memoryRequest := count(io.events.memoryRequest)
  io.counters.memoryResponse := count(io.events.memoryResponse)
  io.counters.ptwActive := count(io.events.ptwActive)
  io.counters.systemHead := count(io.events.systemHead)

  io.counters.completionCollision := count(io.events.completionCollision)
  io.counters.completionBackpressure := count(io.events.completionBackpressure)
  io.counters.lsuComputeOverlapIssue := count(io.events.lsuComputeOverlapIssue)
}

/** Linux/OpenSBI simulation top with P8.0 observation attached around the
  * already-qualified #151 production core.
  *
  * The inherited production core and platform wiring are untouched. Cross-
  * hierarchy state is read through Chisel read-only probes, so no performance
  * ports are added to TinyPagedCore/TinyMemoryBackend and no observed value can
  * feed back into issue, completion, LSU or Commit decisions. Keep the generated
  * module name stable so existing Verilator runners use the same host API.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTop extends AetherCoreV2OpenSbiRV64SimTop {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val perf = Module(new V2PerformanceCounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2PerformanceEvents))

  // Chisel visibility intentionally forbids using grandchild IO directly in
  // expressions at this module. tapAndRead creates read-only probe paths through
  // the hierarchy without changing the production module interfaces.
  private val selectiveValid = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.valid)
  private val selectiveReady = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.ready)
  private val selectiveBits = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.bits)
  private val head = BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow(0))

  private val branchValid = BoringUtils.tapAndRead(core.backend.branchIssue.io.request.valid)
  private val branchReady = BoringUtils.tapAndRead(core.backend.branchIssue.io.request.ready)
  private val lsuRequestValid = BoringUtils.tapAndRead(core.backend.lsu.io.request.valid)
  private val lsuRequestReady = BoringUtils.tapAndRead(core.backend.lsu.io.request.ready)

  private val systemCompletionValid = BoringUtils.tapAndRead(core.backend.system.io.completion.valid)
  private val systemCompletionReady = BoringUtils.tapAndRead(core.backend.system.io.completion.ready)
  private val lsuCompletionValid = BoringUtils.tapAndRead(core.backend.lsu.io.completion.valid)
  private val lsuCompletionReady = BoringUtils.tapAndRead(core.backend.lsu.io.completion.ready)
  private val executionCompletionValid = BoringUtils.tapAndRead(core.backend.execution.io.response.valid)
  private val executionCompletionReady = BoringUtils.tapAndRead(core.backend.execution.io.response.ready)

  private val dispatchValid = BoringUtils.tapAndRead(core.backend.io.dispatch.valid)
  private val dispatchReady = BoringUtils.tapAndRead(core.backend.io.dispatch.ready)

  private val selectiveFire = selectiveValid && selectiveReady
  private val branchFire = branchValid && branchReady
  private val lsuRequestFire = lsuRequestValid && lsuRequestReady
  private val systemCompletionFire = systemCompletionValid && systemCompletionReady
  private val operation = selectiveBits.aluOp

  private val multiplyOperation =
    operation === AluOp.Mul || operation === AluOp.Mulh ||
      operation === AluOp.Mulhsu || operation === AluOp.Mulhu
  private val divideOperation =
    operation === AluOp.Div || operation === AluOp.Divu ||
      operation === AluOp.Rem || operation === AluOp.Remu

  private val selectedDiffersFromHead =
    selectiveBits.robToken.index =/= head.uop.robToken.index ||
      selectiveBits.robToken.generation =/= head.uop.robToken.generation

  private val completionValidCount = PopCount(Cat(
    systemCompletionValid,
    lsuCompletionValid,
    executionCompletionValid
  ))
  private val completionBackpressured =
    (systemCompletionValid && !systemCompletionReady) ||
      (lsuCompletionValid && !lsuCompletionReady) ||
      (executionCompletionValid && !executionCompletionReady)

  events.commit := core.io.commit.valid
  events.dispatchAccepted := dispatchValid && dispatchReady
  events.dispatchBlocked := dispatchValid && !dispatchReady
  events.robOccupancy := core.io.occupancy

  events.selectiveCandidate := selectiveValid
  events.integerIssue := selectiveFire && selectiveBits.executionClass === ExecutionClass.Integer
  events.multiplyIssue := selectiveFire && selectiveBits.executionClass === ExecutionClass.MulDiv && multiplyOperation
  events.divideIssue := selectiveFire && selectiveBits.executionClass === ExecutionClass.MulDiv && divideOperation
  events.branchIssue := branchFire
  events.memoryIssue := lsuRequestFire
  events.systemCompletion := systemCompletionFire
  events.selectiveBypassIssue := selectiveFire && head.valid && selectedDiffersFromHead

  events.headNotReady := head.valid && !head.complete &&
    !head.uop.decoded.exception.valid && !head.operandsReady
  events.commitIdleRobNonEmpty := core.io.occupancy =/= 0.U && !core.io.commit.valid
  events.lsuBusy := core.io.lsuBusy
  events.memoryLaunchBlocked := lsuRequestValid && !lsuRequestReady
  events.memoryRequest := core.io.memoryRequest.fire
  events.memoryResponse := core.io.memoryResponse.fire
  events.ptwActive := io.ptwValid
  events.systemHead := head.valid && !head.complete &&
    head.uop.executionClass === ExecutionClass.System

  events.completionCollision := completionValidCount > 1.U
  events.completionBackpressure := completionBackpressured
  events.lsuComputeOverlapIssue := selectiveFire && core.io.lsuBusy

  perf.io.events := events

  // The runner already observes exitValid. Emit one machine-readable snapshot
  // at the same architectural exit event without adding a new host-side API.
  when(io.exitValid) {
    printf(p"AETHERCORE_V2_PERF cycles=${perf.io.counters.cycles} commits=${perf.io.counters.commits} dispatch_accepted=${perf.io.counters.dispatchAccepted} dispatch_blocked=${perf.io.counters.dispatchBlocked}\n")
    printf(p"AETHERCORE_V2_PERF rob0=${perf.io.counters.robOccupancy0} rob1=${perf.io.counters.robOccupancy1} rob2=${perf.io.counters.robOccupancy2} rob3=${perf.io.counters.robOccupancy3} rob4=${perf.io.counters.robOccupancy4}\n")
    printf(p"AETHERCORE_V2_PERF issue_int=${perf.io.counters.integerIssue} issue_mul=${perf.io.counters.multiplyIssue} issue_div=${perf.io.counters.divideIssue} issue_branch=${perf.io.counters.branchIssue} issue_mem=${perf.io.counters.memoryIssue} system_completion=${perf.io.counters.systemCompletion}\n")
    printf(p"AETHERCORE_V2_PERF selective_candidate=${perf.io.counters.selectiveCandidate} selective_bypass=${perf.io.counters.selectiveBypassIssue} lsu_compute_overlap=${perf.io.counters.lsuComputeOverlapIssue}\n")
    printf(p"AETHERCORE_V2_PERF head_not_ready=${perf.io.counters.headNotReady} commit_idle_nonempty=${perf.io.counters.commitIdleRobNonEmpty} lsu_busy=${perf.io.counters.lsuBusy} memory_launch_blocked=${perf.io.counters.memoryLaunchBlocked}\n")
    printf(p"AETHERCORE_V2_PERF mem_req=${perf.io.counters.memoryRequest} mem_resp=${perf.io.counters.memoryResponse} ptw_active=${perf.io.counters.ptwActive} system_head=${perf.io.counters.systemHead} completion_collision=${perf.io.counters.completionCollision} completion_backpressure=${perf.io.counters.completionBackpressure}\n")
  }
}
