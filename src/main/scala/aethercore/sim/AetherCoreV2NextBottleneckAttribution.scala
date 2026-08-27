package aethercore.sim

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import aethercore.core.v2.{ControlFlowKind, RobToken}

/** Observation-only evidence for choosing the next post-#194 architecture move.
  *
  * Branch recovery is partitioned by decoded control-flow kind so BHT vs
  * BTB/RAS can be chosen from measured cause rather than aggregate recovery.
  * Store barriers are partitioned into completed-only opportunity and the
  * additional upper bound that requires crossing at least one incomplete Store.
  */
class V2NextBottleneckEvents extends Bundle {
  val conditionalResolved = Bool()
  val conditionalRecovery = Bool()
  val directResolved = Bool()
  val directRecovery = Bool()
  val indirectResolved = Bool()
  val indirectRecovery = Bool()
  val completedStoreBarrier = Bool()
  val incompleteStoreBarrier = Bool()
}

class V2NextBottleneckCounters extends Bundle {
  val cycles = UInt(64.W)
  val conditionalResolved = UInt(64.W)
  val conditionalRecovery = UInt(64.W)
  val directResolved = UInt(64.W)
  val directRecovery = UInt(64.W)
  val indirectResolved = UInt(64.W)
  val indirectRecovery = UInt(64.W)
  val completedStoreBarrier = UInt(64.W)
  val incompleteStoreBarrier = UInt(64.W)
}

class V2NextBottleneckCounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2NextBottleneckEvents)
    val counters = Output(new V2NextBottleneckCounters)
  })

  private val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  io.counters.cycles := cycles
  io.counters.conditionalResolved := count(io.events.conditionalResolved)
  io.counters.conditionalRecovery := count(io.events.conditionalRecovery)
  io.counters.directResolved := count(io.events.directResolved)
  io.counters.directRecovery := count(io.events.directRecovery)
  io.counters.indirectResolved := count(io.events.indirectResolved)
  io.counters.indirectRecovery := count(io.events.indirectRecovery)
  io.counters.completedStoreBarrier := count(io.events.completedStoreBarrier)
  io.counters.incompleteStoreBarrier := count(io.events.incompleteStoreBarrier)
}

/** V13 observation wrapper stacked on the #194 architecture candidate. */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV13
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11 {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val bank = Module(new V2NextBottleneckCounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2NextBottleneckEvents))

  private val acceptedCompletionValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.valid)
  private val acceptedCompletionBranchValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.bits.branchValid)
  private val acceptedCompletionToken =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.rob.io.acceptedCompletion.bits.robToken)
  private val acceptedRecoveryValid =
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.acceptedRecovery.valid)

  private val Entries = core.backend.dependencyBackend.io.schedulingWindow.length
  private val window = VecInit((0 until Entries).map { age =>
    BoringUtils.tapAndRead(core.backend.dependencyBackend.io.schedulingWindow(age))
  })

  private def sameToken(lhs: RobToken, rhs: RobToken): Bool =
    lhs.index === rhs.index && lhs.generation === rhs.generation

  private val completionMatches = Wire(Vec(Entries, Bool()))
  for (age <- 0 until Entries) {
    completionMatches(age) :=
      window(age).valid && sameToken(window(age).uop.robToken, acceptedCompletionToken)
  }

  private val completionKind = WireDefault(ControlFlowKind.None)
  for (age <- 0 until Entries) {
    when(completionMatches(age)) {
      completionKind := window(age).uop.decoded.controlFlow.kind
    }
  }

  private val branchResolved = acceptedCompletionValid && acceptedCompletionBranchValid
  when(branchResolved) {
    assert(PopCount(completionMatches) === 1.U,
      "V13 branch attribution must find exactly one live ROB lifetime")
  }
  when(acceptedRecoveryValid) {
    assert(branchResolved,
      "V13 recovery must coincide with an accepted branch completion")
  }

  events.conditionalResolved :=
    branchResolved && completionKind === ControlFlowKind.Conditional
  events.conditionalRecovery :=
    acceptedRecoveryValid && completionKind === ControlFlowKind.Conditional
  events.directResolved :=
    branchResolved && completionKind === ControlFlowKind.DirectJump
  events.directRecovery :=
    acceptedRecoveryValid && completionKind === ControlFlowKind.DirectJump
  events.indirectResolved :=
    branchResolved && completionKind === ControlFlowKind.IndirectJump
  events.indirectRecovery :=
    acceptedRecoveryValid && completionKind === ControlFlowKind.IndirectJump

  when(branchResolved) {
    assert(PopCount(Cat(
      events.conditionalResolved,
      events.directResolved,
      events.indirectResolved
    )) === 1.U, "V13 resolved branch must have one control-flow kind")
  }
  when(acceptedRecoveryValid) {
    assert(PopCount(Cat(
      events.conditionalRecovery,
      events.directRecovery,
      events.indirectRecovery
    )) === 1.U, "V13 recovery must have one control-flow kind")
  }

  events.completedStoreBarrier :=
    BoringUtils.tapAndRead(core.backend.loadIssue.io.completedStoreBarrierOpportunity)
  events.incompleteStoreBarrier :=
    BoringUtils.tapAndRead(core.backend.loadIssue.io.incompleteStoreBarrierOpportunity)

  bank.io.events := events

  val ioV13Cycles = IO(Output(UInt(64.W)))
  val ioV13ConditionalResolved = IO(Output(UInt(64.W)))
  val ioV13ConditionalRecovery = IO(Output(UInt(64.W)))
  val ioV13DirectResolved = IO(Output(UInt(64.W)))
  val ioV13DirectRecovery = IO(Output(UInt(64.W)))
  val ioV13IndirectResolved = IO(Output(UInt(64.W)))
  val ioV13IndirectRecovery = IO(Output(UInt(64.W)))
  val ioV13CompletedStoreBarrier = IO(Output(UInt(64.W)))
  val ioV13IncompleteStoreBarrier = IO(Output(UInt(64.W)))

  ioV13Cycles := bank.io.counters.cycles
  ioV13ConditionalResolved := bank.io.counters.conditionalResolved
  ioV13ConditionalRecovery := bank.io.counters.conditionalRecovery
  ioV13DirectResolved := bank.io.counters.directResolved
  ioV13DirectRecovery := bank.io.counters.directRecovery
  ioV13IndirectResolved := bank.io.counters.indirectResolved
  ioV13IndirectRecovery := bank.io.counters.indirectRecovery
  ioV13CompletedStoreBarrier := bank.io.counters.completedStoreBarrier
  ioV13IncompleteStoreBarrier := bank.io.counters.incompleteStoreBarrier
}
