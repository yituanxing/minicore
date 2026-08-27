package aethercore.sim

import chisel3._
import chisel3.util.experimental.BoringUtils

/** Observation-only counterfactual Load-issue opportunities.
  *
  * Each source is computed inside the qualified LoadQ selector from the exact
  * once-only issue scoreboard and current resource/block state. None of these
  * signals feeds production policy.
  */
class V2LoadOpportunityEvents extends Bundle {
  val capacityBlocked = Bool()
  val completedBranchBarrier = Bool()
  val completedStoreBarrier = Bool()
}

class V2LoadOpportunityCounters extends Bundle {
  val cycles = UInt(64.W)
  val capacityBlocked = UInt(64.W)
  val completedBranchBarrier = UInt(64.W)
  val completedStoreBarrier = UInt(64.W)
}

class V2LoadOpportunityCounterBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new V2LoadOpportunityEvents)
    val counters = Output(new V2LoadOpportunityCounters)
  })

  private val cycles = RegInit(0.U(64.W))
  cycles := cycles + 1.U

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  io.counters.cycles := cycles
  io.counters.capacityBlocked := count(io.events.capacityBlocked)
  io.counters.completedBranchBarrier := count(io.events.completedBranchBarrier)
  io.counters.completedStoreBarrier := count(io.events.completedStoreBarrier)
}

/** P8 opportunity attribution stacked on the pure #191 architecture candidate.
  *
  * This wrapper changes only simulation-visible observation state. The three
  * booleans originate from TinyLoadQueueIssue counterfactual policy mirrors;
  * actual request.valid/request.bits remain untouched.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV12
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11 {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private val bank = Module(new V2LoadOpportunityCounterBank)
  private val events = WireInit(0.U.asTypeOf(new V2LoadOpportunityEvents))

  events.capacityBlocked :=
    BoringUtils.tapAndRead(core.backend.loadIssue.io.capacityBlockedOpportunity)
  events.completedBranchBarrier :=
    BoringUtils.tapAndRead(core.backend.loadIssue.io.completedBranchBarrierOpportunity)
  events.completedStoreBarrier :=
    BoringUtils.tapAndRead(core.backend.loadIssue.io.completedStoreBarrierOpportunity)

  bank.io.events := events

  val ioV12Cycles = IO(Output(UInt(64.W)))
  val ioV12LoadqCapacityBlocked = IO(Output(UInt(64.W)))
  val ioV12CompletedBranchBarrier = IO(Output(UInt(64.W)))
  val ioV12CompletedStoreBarrier = IO(Output(UInt(64.W)))

  ioV12Cycles := bank.io.counters.cycles
  ioV12LoadqCapacityBlocked := bank.io.counters.capacityBlocked
  ioV12CompletedBranchBarrier := bank.io.counters.completedBranchBarrier
  ioV12CompletedStoreBarrier := bank.io.counters.completedStoreBarrier
}
