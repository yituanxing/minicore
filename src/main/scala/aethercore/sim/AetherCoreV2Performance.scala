package aethercore.sim

import chisel3._
import chisel3.util._

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
