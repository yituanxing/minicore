package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.sim.{V2LinuxProofMarker, V2LinuxProofMarkerRecognizer, V2PerformanceCounterBank}

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
    dut.io.events.selectiveBypassComputeHead.poke(false.B)
    dut.io.events.selectiveBypassBranchHead.poke(false.B)
    dut.io.events.selectiveBypassMemoryHead.poke(false.B)
    dut.io.events.selectiveBypassOtherHead.poke(false.B)

    dut.io.events.headNotReady.poke(false.B)
    dut.io.events.headReadyNotIssued.poke(false.B)
    dut.io.events.commitIdleRobNonEmpty.poke(false.B)
    dut.io.events.computeHead.poke(false.B)
    dut.io.events.branchHead.poke(false.B)
    dut.io.events.memoryHead.poke(false.B)
    dut.io.events.systemHead.poke(false.B)
    dut.io.events.interruptHold.poke(false.B)
    dut.io.events.wfiHalted.poke(false.B)

    dut.io.events.lsuBusy.poke(false.B)
    dut.io.events.memoryLaunchBlocked.poke(false.B)
    dut.io.events.memoryRequest.poke(false.B)
    dut.io.events.memoryResponse.poke(false.B)
    dut.io.events.ptwActive.poke(false.B)

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
      dut.io.events.computeHead.poke(true.B)
      dut.clock.step()

      // Cycle 2: a younger compute bypass overlaps a busy LSU/Memory head.
      clearEvents(dut, occupancy = 1)
      dut.io.events.dispatchBlocked.poke(true.B)
      dut.io.events.selectiveCandidate.poke(true.B)
      dut.io.events.multiplyIssue.poke(true.B)
      dut.io.events.selectiveBypassIssue.poke(true.B)
      dut.io.events.selectiveBypassMemoryHead.poke(true.B)
      dut.io.events.memoryHead.poke(true.B)
      dut.io.events.lsuBusy.poke(true.B)
      dut.io.events.lsuComputeOverlapIssue.poke(true.B)
      dut.clock.step()

      // Cycle 3: completion pressure while a compute head is dependency-blocked.
      clearEvents(dut, occupancy = 2)
      dut.io.events.divideIssue.poke(true.B)
      dut.io.events.headNotReady.poke(true.B)
      dut.io.events.commitIdleRobNonEmpty.poke(true.B)
      dut.io.events.computeHead.poke(true.B)
      dut.io.events.completionCollision.poke(true.B)
      dut.io.events.completionBackpressure.poke(true.B)
      dut.clock.step()

      // Cycle 4: independent Branch/PTW/interrupt observations.
      clearEvents(dut, occupancy = 3)
      dut.io.events.branchIssue.poke(true.B)
      dut.io.events.branchHead.poke(true.B)
      dut.io.events.headReadyNotIssued.poke(true.B)
      dut.io.events.memoryRequest.poke(true.B)
      dut.io.events.memoryResponse.poke(true.B)
      dut.io.events.ptwActive.poke(true.B)
      dut.io.events.interruptHold.poke(true.B)
      dut.clock.step()

      // Cycle 5: exact-head Memory/System/WFI observations use their own seams.
      clearEvents(dut, occupancy = 4)
      dut.io.events.memoryIssue.poke(true.B)
      dut.io.events.systemCompletion.poke(true.B)
      dut.io.events.memoryLaunchBlocked.poke(true.B)
      dut.io.events.systemHead.poke(true.B)
      dut.io.events.wfiHalted.poke(true.B)
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
      dut.io.counters.selectiveBypassComputeHead.expect(0.U)
      dut.io.counters.selectiveBypassBranchHead.expect(0.U)
      dut.io.counters.selectiveBypassMemoryHead.expect(1.U)
      dut.io.counters.selectiveBypassOtherHead.expect(0.U)

      dut.io.counters.headNotReady.expect(1.U)
      dut.io.counters.headReadyNotIssued.expect(1.U)
      dut.io.counters.commitIdleRobNonEmpty.expect(1.U)
      dut.io.counters.computeHead.expect(2.U)
      dut.io.counters.branchHead.expect(1.U)
      dut.io.counters.memoryHead.expect(1.U)
      dut.io.counters.systemHead.expect(1.U)
      dut.io.counters.interruptHold.expect(1.U)
      dut.io.counters.wfiHalted.expect(1.U)

      dut.io.counters.lsuBusy.expect(1.U)
      dut.io.counters.memoryLaunchBlocked.expect(1.U)
      dut.io.counters.memoryRequest.expect(1.U)
      dut.io.counters.memoryResponse.expect(1.U)
      dut.io.counters.ptwActive.expect(1.U)

      dut.io.counters.completionCollision.expect(1.U)
      dut.io.counters.completionBackpressure.expect(1.U)
      dut.io.counters.lsuComputeOverlapIssue.expect(1.U)
    }
  }

  behavior of "AetherCore v2 Linux performance marker"

  it should "fire once at the proof phrase before either LF or CRLF termination" in {
    simulate(new V2LinuxProofMarkerRecognizer) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.byte.poke(0.U)
      dut.io.hit.expect(false.B)
      dut.clock.step()

      def send(value: Int, hit: Boolean = false): Unit = {
        dut.io.valid.poke(true.B)
        dut.io.byte.poke(value.U)
        dut.io.hit.expect(hit.B)
        dut.clock.step()
      }

      send('X'.toInt)
      val bytes = V2LinuxProofMarker.Text.getBytes("US-ASCII").map(_ & 0xff)
      bytes.zipWithIndex.foreach { case (value, index) =>
        send(value, hit = index == bytes.length - 1)
      }

      // Line termination is deliberately outside the marker contract.
      send('\r'.toInt)
      send('\n'.toInt)

      // The recognizer is one-shot even if the phrase appears again.
      bytes.foreach(value => send(value))
    }
  }
}
