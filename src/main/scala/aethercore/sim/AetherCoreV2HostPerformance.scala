package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.common.AluOp
import aethercore.core.v2.ExecutionClass

/**
  * P8 Linux measurement top that keeps the frozen counter semantics but exposes
  * the accumulated values as explicit top-level simulation outputs.
  *
  * The prior printf-only sink was optimized away by the generated simulator.
  * Making the counters part of the host-visible interface prevents that DCE
  * without adding any signal to the production TinyPagedCore/TinyMemoryBackend
  * interfaces or feeding observation back into architectural behavior.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisible extends AetherCoreV2OpenSbiRV64SimTop {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val perf = Module(new V2PerformanceCounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2PerformanceEvents))

  private val selectiveValid = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.valid)
  private val selectiveReady = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.ready)
  private val selectiveBits = BoringUtils.tapAndRead(core.backend.selectiveIssue.io.request.bits)
  private val head = BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow(0))

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
  // P8.2 R4 removed the independent head-only Branch scheduler. Preserve the
  // historical branchIssue counter by classifying accepted requests from the
  // one unified selective owner rather than broadening its meaning to all
  // speculative execution launches.
  private val branchFire = selectiveFire &&
    selectiveBits.executionClass === ExecutionClass.Branch
  private val lsuRequestFire = lsuRequestValid && lsuRequestReady
  private val systemCompletionFire = systemCompletionValid && systemCompletionReady
  private val operation = selectiveBits.aluOp

  private val multiplyOperation =
    operation === AluOp.Mul || operation === AluOp.Mulh ||
      operation === AluOp.Mulhsu || operation === AluOp.Mulhu
  private val divideOperation =
    operation === AluOp.Div || operation === AluOp.Divu ||
      operation === AluOp.Rem || operation === AluOp.Remu

  private val headLive = head.valid && !head.complete
  private val headClass = head.uop.executionClass
  private val headIsCompute =
    headClass === ExecutionClass.Integer || headClass === ExecutionClass.MulDiv
  private val headIsBranch = headClass === ExecutionClass.Branch
  private val headIsMemory = headClass === ExecutionClass.Memory
  private val headIsSystem = headClass === ExecutionClass.System

  private val selectedDiffersFromHead =
    selectiveBits.robToken.index =/= head.uop.robToken.index ||
      selectiveBits.robToken.generation =/= head.uop.robToken.generation
  private val selectiveBypass = selectiveFire && head.valid && selectedDiffersFromHead
  private val selectiveHeadFire = selectiveFire && head.valid && !selectedDiffersFromHead

  private val headSchedulable = headLive && !head.uop.decoded.exception.valid &&
    head.operandsReady && (headIsCompute || headIsBranch || headIsMemory)
  private val headLaunchFire =
    ((headIsCompute || headIsBranch) && selectiveHeadFire) ||
      (headIsMemory && lsuRequestFire)

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
  events.selectiveBypassIssue := selectiveBypass
  events.selectiveBypassComputeHead := selectiveBypass && headIsCompute
  events.selectiveBypassBranchHead := selectiveBypass && headIsBranch
  events.selectiveBypassMemoryHead := selectiveBypass && headIsMemory
  events.selectiveBypassOtherHead := selectiveBypass && !(headIsCompute || headIsBranch || headIsMemory)

  events.headNotReady := headLive &&
    !head.uop.decoded.exception.valid && !head.operandsReady
  events.headReadyNotIssued := headSchedulable && !headLaunchFire
  events.commitIdleRobNonEmpty := core.io.occupancy =/= 0.U && !core.io.commit.valid
  events.computeHead := headLive && headIsCompute
  events.branchHead := headLive && headIsBranch
  events.memoryHead := headLive && headIsMemory
  events.systemHead := headLive && headIsSystem
  events.interruptHold := core.io.interruptHold
  events.wfiHalted := core.io.halted

  events.lsuBusy := core.io.lsuBusy
  events.memoryLaunchBlocked := lsuRequestValid && !lsuRequestReady
  events.memoryRequest := core.io.memoryRequest.fire
  events.memoryResponse := core.io.memoryResponse.fire
  events.ptwActive := io.ptwValid

  events.completionCollision := completionValidCount > 1.U
  events.completionBackpressure := completionBackpressured
  events.lsuComputeOverlapIssue := selectiveFire && core.io.lsuBusy

  perf.io.events := events

  // Explicit host-visible sinks. These are simulation-only outputs on this
  // measured top; production module interfaces remain unchanged.
  val ioPerfCycles = IO(Output(UInt(64.W)))
  val ioPerfCommits = IO(Output(UInt(64.W)))
  val ioPerfDispatchAccepted = IO(Output(UInt(64.W)))
  val ioPerfDispatchBlocked = IO(Output(UInt(64.W)))
  val ioPerfRob0 = IO(Output(UInt(64.W)))
  val ioPerfRob1 = IO(Output(UInt(64.W)))
  val ioPerfRob2 = IO(Output(UInt(64.W)))
  val ioPerfRob3 = IO(Output(UInt(64.W)))
  val ioPerfRob4 = IO(Output(UInt(64.W)))
  val ioPerfIssueInt = IO(Output(UInt(64.W)))
  val ioPerfIssueMul = IO(Output(UInt(64.W)))
  val ioPerfIssueDiv = IO(Output(UInt(64.W)))
  val ioPerfIssueBranch = IO(Output(UInt(64.W)))
  val ioPerfIssueMem = IO(Output(UInt(64.W)))
  val ioPerfSystemCompletion = IO(Output(UInt(64.W)))
  val ioPerfSelectiveCandidate = IO(Output(UInt(64.W)))
  val ioPerfSelectiveBypass = IO(Output(UInt(64.W)))
  val ioPerfBypassComputeHead = IO(Output(UInt(64.W)))
  val ioPerfBypassBranchHead = IO(Output(UInt(64.W)))
  val ioPerfBypassMemoryHead = IO(Output(UInt(64.W)))
  val ioPerfBypassOtherHead = IO(Output(UInt(64.W)))
  val ioPerfLsuComputeOverlap = IO(Output(UInt(64.W)))
  val ioPerfHeadNotReady = IO(Output(UInt(64.W)))
  val ioPerfHeadReadyNotIssued = IO(Output(UInt(64.W)))
  val ioPerfCommitIdleNonempty = IO(Output(UInt(64.W)))
  val ioPerfComputeHead = IO(Output(UInt(64.W)))
  val ioPerfBranchHead = IO(Output(UInt(64.W)))
  val ioPerfMemoryHead = IO(Output(UInt(64.W)))
  val ioPerfSystemHead = IO(Output(UInt(64.W)))
  val ioPerfInterruptHold = IO(Output(UInt(64.W)))
  val ioPerfWfiHalted = IO(Output(UInt(64.W)))
  val ioPerfLsuBusy = IO(Output(UInt(64.W)))
  val ioPerfMemoryLaunchBlocked = IO(Output(UInt(64.W)))
  val ioPerfMemReq = IO(Output(UInt(64.W)))
  val ioPerfMemResp = IO(Output(UInt(64.W)))
  val ioPerfPtwActive = IO(Output(UInt(64.W)))
  val ioPerfCompletionCollision = IO(Output(UInt(64.W)))
  val ioPerfCompletionBackpressure = IO(Output(UInt(64.W)))

  ioPerfCycles := perf.io.counters.cycles
  ioPerfCommits := perf.io.counters.commits
  ioPerfDispatchAccepted := perf.io.counters.dispatchAccepted
  ioPerfDispatchBlocked := perf.io.counters.dispatchBlocked
  ioPerfRob0 := perf.io.counters.robOccupancy0
  ioPerfRob1 := perf.io.counters.robOccupancy1
  ioPerfRob2 := perf.io.counters.robOccupancy2
  ioPerfRob3 := perf.io.counters.robOccupancy3
  ioPerfRob4 := perf.io.counters.robOccupancy4
  ioPerfIssueInt := perf.io.counters.integerIssue
  ioPerfIssueMul := perf.io.counters.multiplyIssue
  ioPerfIssueDiv := perf.io.counters.divideIssue
  ioPerfIssueBranch := perf.io.counters.branchIssue
  ioPerfIssueMem := perf.io.counters.memoryIssue
  ioPerfSystemCompletion := perf.io.counters.systemCompletion
  ioPerfSelectiveCandidate := perf.io.counters.selectiveCandidate
  ioPerfSelectiveBypass := perf.io.counters.selectiveBypassIssue
  ioPerfBypassComputeHead := perf.io.counters.selectiveBypassComputeHead
  ioPerfBypassBranchHead := perf.io.counters.selectiveBypassBranchHead
  ioPerfBypassMemoryHead := perf.io.counters.selectiveBypassMemoryHead
  ioPerfBypassOtherHead := perf.io.counters.selectiveBypassOtherHead
  ioPerfLsuComputeOverlap := perf.io.counters.lsuComputeOverlapIssue
  ioPerfHeadNotReady := perf.io.counters.headNotReady
  ioPerfHeadReadyNotIssued := perf.io.counters.headReadyNotIssued
  ioPerfCommitIdleNonempty := perf.io.counters.commitIdleRobNonEmpty
  ioPerfComputeHead := perf.io.counters.computeHead
  ioPerfBranchHead := perf.io.counters.branchHead
  ioPerfMemoryHead := perf.io.counters.memoryHead
  ioPerfSystemHead := perf.io.counters.systemHead
  ioPerfInterruptHold := perf.io.counters.interruptHold
  ioPerfWfiHalted := perf.io.counters.wfiHalted
  ioPerfLsuBusy := perf.io.counters.lsuBusy
  ioPerfMemoryLaunchBlocked := perf.io.counters.memoryLaunchBlocked
  ioPerfMemReq := perf.io.counters.memoryRequest
  ioPerfMemResp := perf.io.counters.memoryResponse
  ioPerfPtwActive := perf.io.counters.ptwActive
  ioPerfCompletionCollision := perf.io.counters.completionCollision
  ioPerfCompletionBackpressure := perf.io.counters.completionBackpressure
}
