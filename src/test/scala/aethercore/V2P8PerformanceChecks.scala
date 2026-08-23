package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.sim.V2PerformanceCounterBank

/** Freeze the simulation-only P8.0 accumulator semantics independently from
  * Linux/OpenSBI so measurement bugs cannot silently steer architecture work.
  */
trait V2P8PerformanceChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def clearEvents(dut: V2PerformanceCounterBank, occupancy: Int): Unit = {
    dut.io.events.commit.poke(false.B)
    dut.io.events.dispatchAccepted.poke(false.B)
    dut.io.events.dispatchBlocked.poke(false.B)
    dut.io.events.robOccupancy.poke(occupancy.U)

    dut.io.events.selectiveCandidate.poke(false.B)
    dut.io.events.integerIssue.poke(false.B)
    dut.io.events.multiplyIssue.poke(false.B)
    dut.io.events.divideIssue.poke(false.B)
    dut.io.events.branchIssue.poke(false.B)
    dut.io.events.memoryIssue.poke(false.B)
    dut.io.events.systemCompletion.poke(false.B)
    dut.io.events.selectiveBypassIssue.poke(false.B)

    dut.io.events.headNotReady.poke(false.B)
    dut.io.events.commitIdleRobNonEmpty.poke(false.B)
    dut.io.events.lsuBusy.poke(false.B)
    dut.io.events.memoryLaunchBlocked.poke(false.B)
    dut.io.events.memoryRequest.poke(false.B)
    dut.io.events.memoryResponse.poke(false.B)
    dut.io.events.ptwActive.poke(false.B)
    dut.io.events.systemHead.poke(false.B)

    dut.io.events.completionCollision.poke(false.B)
    dut.io.events.completionBackpressure.poke(false.B)
    dut.io.events.lsuComputeOverlapIssue.poke(false.B)
  }

  behavior of "AetherCore v2 P8.0 performance counters"

  it should "count each observation predicate once per sampled cycle and keep a ROB4 histogram" in {
    simulate(new V2PerformanceCounterBank) { dut =>
      clearEvents(dut, occupancy = 0)
      dut.io.counters.cycles.expect(0.U)

      // Cycle 1: productive Integer issue and Commit.
      dut.io.events.commit.poke(true.B)
      dut.io.events.dispatchAccepted.poke(true.B)
      dut.io.events.selectiveCandidate.poke(true.B)
      dut.io.events.integerIssue.poke(true.B)
      dut.clock.step()

      // Cycle 2: a younger compute bypass overlaps a busy LSU.
      clearEvents(dut, occupancy = 1)
      dut.io.events.dispatchBlocked.poke(true.B)
      dut.io.events.selectiveCandidate.poke(true.B)
      dut.io.events.multiplyIssue.poke(true.B)
      dut.io.events.selectiveBypassIssue.poke(true.B)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lsuComputeOverlapIssue.poke(true.B)
      dut.clock.step()

      // Cycle 3: completion pressure while the head is dependency-blocked.
      clearEvents(dut, occupancy = 2)
      dut.io.events.divideIssue.poke(true.B)
      dut.io.events.headNotReady.poke(true.B)
      dut.io.events.commitIdleRobNonEmpty.poke(true.B)
      dut.io.events.completionCollision.poke(true.B)
      dut.io.events.completionBackpressure.poke(true.B)
      dut.clock.step()

      // Cycle 4: independent Branch/PTW/memory-transport observations.
      clearEvents(dut, occupancy = 3)
      dut.io.events.branchIssue.poke(true.B)
      dut.io.events.memoryRequest.poke(true.B)
      dut.io.events.memoryResponse.poke(true.B)
      dut.io.events.ptwActive.poke(true.B)
      dut.clock.step()

      // Cycle 5: exact-head Memory/System observations use their own seams.
      clearEvents(dut, occupancy = 4)
      dut.io.events.memoryIssue.poke(true.B)
      dut.io.events.systemCompletion.poke(true.B)
      dut.io.events.memoryLaunchBlocked.poke(true.B)
      dut.io.events.systemHead.poke(true.B)
      dut.clock.step()

      dut.io.counters.cycles.expect(5.U)
      dut.io.counters.commits.expect(1.U)
      dut.io.counters.dispatchAccepted.expect(1.U)
      dut.io.counters.dispatchBlocked.expect(1.U)

      dut.io.counters.robOccupancy0.expect(1.U)
      dut.io.counters.robOccupancy1.expect(1.U)
      dut.io.counters.robOccupancy2.expect(1.U)
      dut.io.counters.robOccupancy3.expect(1.U)
      dut.io.counters.robOccupancy4.expect(1.U)

      dut.io.counters.selectiveCandidate.expect(2.U)
      dut.io.counters.integerIssue.expect(1.U)
      dut.io.counters.multiplyIssue.expect(1.U)
      dut.io.counters.divideIssue.expect(1.U)
      dut.io.counters.branchIssue.expect(1.U)
      dut.io.counters.memoryIssue.expect(1.U)
      dut.io.counters.systemCompletion.expect(1.U)
      dut.io.counters.selectiveBypassIssue.expect(1.U)

      dut.io.counters.headNotReady.expect(1.U)
      dut.io.counters.commitIdleRobNonEmpty.expect(1.U)
      dut.io.counters.lsuBusy.expect(1.U)
      dut.io.counters.memoryLaunchBlocked.expect(1.U)
      dut.io.counters.memoryRequest.expect(1.U)
      dut.io.counters.memoryResponse.expect(1.U)
      dut.io.counters.ptwActive.expect(1.U)
      dut.io.counters.systemHead.expect(1.U)

      dut.io.counters.completionCollision.expect(1.U)
      dut.io.counters.completionBackpressure.expect(1.U)
      dut.io.counters.lsuComputeOverlapIssue.expect(1.U)
    }
  }
}
