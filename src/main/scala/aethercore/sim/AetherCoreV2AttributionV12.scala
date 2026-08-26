package aethercore.sim

import chisel3._
import chisel3.util.experimental.BoringUtils
import aethercore.core.v2.TinyRobGeometry

/**
  * Observation-only P8 attribution for the next bounded memory-concurrency
  * decision. It asks one narrow causal question before any LoadQ3 RTL exists:
  * while both replay-safe Load slots are occupied, how often is another Load
  * already legal/ready and blocked only by the two-slot capacity gate?
  *
  * No signal feeds back into production scheduling, issue or memory lifetime.
  */
class AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV12
    extends AetherCoreV2MeasuredOpenSbiRV64SimTopHostVisibleAttributionV11 {
  override def desiredName: String = "AetherCoreV2OpenSbiRV64SimTop"

  private def count(event: Bool): UInt = {
    val value = RegInit(0.U(64.W))
    when(event) { value := value + 1.U }
    value
  }

  private val loadqFull = BoringUtils.tapAndRead(core.backend.loadUnit.io.full)
  private val candidateValid = BoringUtils.tapAndRead(core.backend.loadIssue.io.candidateValid)
  private val capacityBlocked = BoringUtils.tapAndRead(core.backend.loadIssue.io.capacityBlocked)
  private val capacityOnlyBlocked =
    BoringUtils.tapAndRead(core.backend.loadIssue.io.capacityOnlyBlocked)
  private val robFull = core.io.occupancy === TinyRobGeometry.Entries.U

  // Keep the observation seam executable. A capacity-blocked candidate must be
  // a real post-policy candidate and the dual Load unit must actually be full.
  when(capacityBlocked) {
    assert(candidateValid)
    assert(loadqFull)
  }
  when(capacityOnlyBlocked) {
    assert(capacityBlocked)
  }

  val ioV12LoadqFull = IO(Output(UInt(64.W)))
  val ioV12LoadCandidate = IO(Output(UInt(64.W)))
  val ioV12LoadCapacityBlocked = IO(Output(UInt(64.W)))
  val ioV12LoadCapacityOnlyBlocked = IO(Output(UInt(64.W)))
  val ioV12LoadCapacityOnlyBlockedRobFull = IO(Output(UInt(64.W)))

  ioV12LoadqFull := count(loadqFull)
  ioV12LoadCandidate := count(candidateValid)
  ioV12LoadCapacityBlocked := count(capacityBlocked)
  ioV12LoadCapacityOnlyBlocked := count(capacityOnlyBlocked)
  ioV12LoadCapacityOnlyBlockedRobFull := count(capacityOnlyBlocked && robFull)
}
