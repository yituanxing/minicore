package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.common.AluOp
import aethercore.core.v2.ExecutionClass

object V2LinuxProofMarker {
  // Intentionally exclude CR/LF. Linux tty output may normalize line endings;
  // the performance boundary is the proof phrase itself, not its terminator.
  val Text = "RV64 USER UART IRQ OK"
}

/** One-shot recognizer for the unchanged Linux PID1 UART proof phrase. */
class V2LinuxProofMarkerRecognizer extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val byte = Input(UInt(8.W))
    val hit = Output(Bool())
  })

  private val markerByteValues =
    V2LinuxProofMarker.Text.getBytes("US-ASCII").toSeq.map(_ & 0xff)
  private val markerBytes = VecInit(markerByteValues.map(_.U(8.W)))
  private val markerLength = markerByteValues.length
  private val markerIndex = RegInit(0.U(log2Ceil(markerLength).W))
  private val alreadyHit = RegInit(false.B)

  private val byteMatches =
    io.valid && !alreadyHit && io.byte === markerBytes(markerIndex)
  private val finalByte = markerIndex === (markerLength - 1).U

  io.hit := byteMatches && finalByte

  when(io.valid && !alreadyHit) {
    when(io.byte === markerBytes(markerIndex)) {
      when(finalByte) {
        markerIndex := 0.U
        alreadyHit := true.B
      }.otherwise {
        markerIndex := markerIndex + 1.U
      }
    }.otherwise {
      // Preserve the only possible one-byte prefix overlap.
      markerIndex := Mux(io.byte === markerBytes(0), 1.U, 0.U)
    }
  }
}

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
  val selectiveBypassComputeHead = Bool()
  val selectiveBypassBranchHead = Bool()
  val selectiveBypassMemoryHead = Bool()
  val selectiveBypassOtherHead = Bool()

  val headNotReady = Bool()
  val headReadyNotIssued = Bool()
  val commitIdleRobNonEmpty = Bool()
  val computeHead = Bool()
  val branchHead = Bool()
  val memoryHead = Bool()
  val systemHead = Bool()
  val interruptHold = Bool()
  val wfiHalted = Bool()

  val lsuBusy = Bool()
  val memoryLaunchBlocked = Bool()
  val memoryRequest = Bool()
  val memoryResponse = Bool()
  val ptwActive = Bool()

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
  val selectiveBypassComputeHead = UInt(64.W)
  val selectiveBypassBranchHead = UInt(64.W)
  val selectiveBypassMemoryHead = UInt(64.W)
  val selectiveBypassOtherHead = UInt(64.W)

  val headNotReady = UInt(64.W)
  val headReadyNotIssued = UInt(64.W)
  val commitIdleRobNonEmpty = UInt(64.W)
  val computeHead = UInt(64.W)
  val branchHead = UInt(64.W)
  val memoryHead = UInt(64.W)
  val systemHead = UInt(64.W)
  val interruptHold = UInt(64.W)
  val wfiHalted = UInt(64.W)

  val lsuBusy = UInt(64.W)
  val memoryLaunchBlocked = UInt(64.W)
  val memoryRequest = UInt(64.W)
  val memoryResponse = UInt(64.W)
  val ptwActive = UInt(64.W)

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
  io.counters.selectiveBypassComputeHead := count(io.events.selectiveBypassComputeHead)
  io.counters.selectiveBypassBranchHead := count(io.events.selectiveBypassBranchHead)
  io.counters.selectiveBypassMemoryHead := count(io.events.selectiveBypassMemoryHead)
  io.counters.selectiveBypassOtherHead := count(io.events.selectiveBypassOtherHead)

  io.counters.headNotReady := count(io.events.headNotReady)
  io.counters.headReadyNotIssued := count(io.events.headReadyNotIssued)
  io.counters.commitIdleRobNonEmpty := count(io.events.commitIdleRobNonEmpty)
  io.counters.computeHead := count(io.events.computeHead)
  io.counters.branchHead := count(io.events.branchHead)
  io.counters.memoryHead := count(io.events.memoryHead)
  io.counters.systemHead := count(io.events.systemHead)
  io.counters.interruptHold := count(io.events.interruptHold)
  io.counters.wfiHalted := count(io.events.wfiHalted)

  io.counters.lsuBusy := count(io.events.lsuBusy)
  io.counters.memoryLaunchBlocked := count(io.events.memoryLaunchBlocked)
  io.counters.memoryRequest := count(io.events.memoryRequest)
  io.counters.memoryResponse := count(io.events.memoryResponse)
  io.counters.ptwActive := count(io.events.ptwActive)

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
  // R4 folds Branch into the same oldest-ready scheduler as Integer/MulDiv.
  // Preserve branchIssue as an event classification, not a separate owner.
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

  // Linux PID1 prints the proof phrase and then yields forever. Recognize the
  // phrase itself, before CR/LF, so tty line-ending normalization cannot suppress
  // the measurement boundary. exitValid remains a second boundary for terminating
  // workloads, but the wrapper emits at most one snapshot.
  private val marker = Module(new V2LinuxProofMarkerRecognizer)
  marker.io.valid := io.uartValid
  marker.io.byte := io.uartByte

  private val snapshotEmitted = RegInit(false.B)
  private val snapshotTrigger = !snapshotEmitted && (marker.io.hit || io.exitValid)

  when(snapshotTrigger) {
    snapshotEmitted := true.B
    printf(p"AETHERCORE_V2_PERF cycles=${perf.io.counters.cycles} commits=${perf.io.counters.commits} dispatch_accepted=${perf.io.counters.dispatchAccepted} dispatch_blocked=${perf.io.counters.dispatchBlocked}\n")
    printf(p"AETHERCORE_V2_PERF rob0=${perf.io.counters.robOccupancy0} rob1=${perf.io.counters.robOccupancy1} rob2=${perf.io.counters.robOccupancy2} rob3=${perf.io.counters.robOccupancy3} rob4=${perf.io.counters.robOccupancy4}\n")
    printf(p"AETHERCORE_V2_PERF issue_int=${perf.io.counters.integerIssue} issue_mul=${perf.io.counters.multiplyIssue} issue_div=${perf.io.counters.divideIssue} issue_branch=${perf.io.counters.branchIssue} issue_mem=${perf.io.counters.memoryIssue} system_completion=${perf.io.counters.systemCompletion}\n")
    printf(p"AETHERCORE_V2_PERF selective_candidate=${perf.io.counters.selectiveCandidate} selective_bypass=${perf.io.counters.selectiveBypassIssue} bypass_compute_head=${perf.io.counters.selectiveBypassComputeHead} bypass_branch_head=${perf.io.counters.selectiveBypassBranchHead} bypass_memory_head=${perf.io.counters.selectiveBypassMemoryHead} bypass_other_head=${perf.io.counters.selectiveBypassOtherHead} lsu_compute_overlap=${perf.io.counters.lsuComputeOverlapIssue}\n")
    printf(p"AETHERCORE_V2_PERF head_not_ready=${perf.io.counters.headNotReady} head_ready_not_issued=${perf.io.counters.headReadyNotIssued} commit_idle_nonempty=${perf.io.counters.commitIdleRobNonEmpty} compute_head=${perf.io.counters.computeHead} branch_head=${perf.io.counters.branchHead} memory_head=${perf.io.counters.memoryHead} system_head=${perf.io.counters.systemHead}\n")
    printf(p"AETHERCORE_V2_PERF interrupt_hold=${perf.io.counters.interruptHold} wfi_halted=${perf.io.counters.wfiHalted} lsu_busy=${perf.io.counters.lsuBusy} memory_launch_blocked=${perf.io.counters.memoryLaunchBlocked} ptw_active=${perf.io.counters.ptwActive}\n")
    printf(p"AETHERCORE_V2_PERF mem_req=${perf.io.counters.memoryRequest} mem_resp=${perf.io.counters.memoryResponse} completion_collision=${perf.io.counters.completionCollision} completion_backpressure=${perf.io.counters.completionBackpressure}\n")
  }
}
