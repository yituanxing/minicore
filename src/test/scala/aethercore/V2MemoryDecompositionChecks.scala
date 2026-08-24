package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.sim.V2MemoryDecompositionCounterBank

/** Focused contract for the measurement-only accumulator. Predicate semantics
  * remain explicit in V2MemoryDecompositionProbe and are additionally checked
  * by the Linux runtime invariants in the dedicated measurement workflow.
  */
class V2MemoryDecompositionSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private def clear(dut: V2MemoryDecompositionCounterBank): Unit = {
    dut.io.events.memoryHeadLoad.poke(false.B)
    dut.io.events.memoryHeadStore.poke(false.B)
    dut.io.events.memoryHeadAtomic.poke(false.B)
    dut.io.events.memoryIssueLoad.poke(false.B)
    dut.io.events.memoryIssueStore.poke(false.B)
    dut.io.events.memoryIssueAtomic.poke(false.B)
    dut.io.events.memoryHeadLsuBusy.poke(false.B)
    dut.io.events.memoryHeadPtwActive.poke(false.B)
    dut.io.events.readyYoungerLoad.poke(false.B)
    dut.io.events.readyYoungerLoadAge1.poke(false.B)
    dut.io.events.readyYoungerLoadAge2.poke(false.B)
    dut.io.events.readyYoungerLoadAge3.poke(false.B)
    dut.io.events.readyYoungerLoadLsuIdle.poke(false.B)
    dut.io.events.readyYoungerLoadComputeFrontier.poke(false.B)
    dut.io.events.readyYoungerLoadComputeFrontierLsuIdle.poke(false.B)
    dut.io.events.readyYoungerLoadBehindMemoryHead.poke(false.B)
    dut.io.events.readyYoungerLoadBehindMemoryHeadLsuBusy.poke(false.B)
  }

  behavior of "AetherCore v2 memory-decomposition counters"

  it should "accumulate each observation predicate independently once per cycle" in {
    simulate(new V2MemoryDecompositionCounterBank) { dut =>
      clear(dut)

      // One Load-head sample with a ready age-1 candidate at the compute frontier.
      dut.io.events.memoryHeadLoad.poke(true.B)
      dut.io.events.memoryHeadLsuBusy.poke(true.B)
      dut.io.events.readyYoungerLoad.poke(true.B)
      dut.io.events.readyYoungerLoadAge1.poke(true.B)
      dut.io.events.readyYoungerLoadComputeFrontier.poke(true.B)
      dut.clock.step()

      clear(dut)
      // One Store issue with a ready age-3 load behind an active Memory head.
      dut.io.events.memoryHeadStore.poke(true.B)
      dut.io.events.memoryIssueStore.poke(true.B)
      dut.io.events.memoryHeadLsuBusy.poke(true.B)
      dut.io.events.memoryHeadPtwActive.poke(true.B)
      dut.io.events.readyYoungerLoad.poke(true.B)
      dut.io.events.readyYoungerLoadAge3.poke(true.B)
      dut.io.events.readyYoungerLoadBehindMemoryHead.poke(true.B)
      dut.io.events.readyYoungerLoadBehindMemoryHeadLsuBusy.poke(true.B)
      dut.clock.step()

      clear(dut)
      // One Atomic issue plus an LSU-idle compute-frontier opportunity.
      dut.io.events.memoryHeadAtomic.poke(true.B)
      dut.io.events.memoryIssueAtomic.poke(true.B)
      dut.io.events.readyYoungerLoad.poke(true.B)
      dut.io.events.readyYoungerLoadAge2.poke(true.B)
      dut.io.events.readyYoungerLoadLsuIdle.poke(true.B)
      dut.io.events.readyYoungerLoadComputeFrontier.poke(true.B)
      dut.io.events.readyYoungerLoadComputeFrontierLsuIdle.poke(true.B)
      dut.clock.step()

      dut.io.counters.memoryHeadLoad.expect(1.U)
      dut.io.counters.memoryHeadStore.expect(1.U)
      dut.io.counters.memoryHeadAtomic.expect(1.U)
      dut.io.counters.memoryIssueLoad.expect(0.U)
      dut.io.counters.memoryIssueStore.expect(1.U)
      dut.io.counters.memoryIssueAtomic.expect(1.U)
      dut.io.counters.memoryHeadLsuBusy.expect(2.U)
      dut.io.counters.memoryHeadPtwActive.expect(1.U)
      dut.io.counters.readyYoungerLoad.expect(3.U)
      dut.io.counters.readyYoungerLoadAge1.expect(1.U)
      dut.io.counters.readyYoungerLoadAge2.expect(1.U)
      dut.io.counters.readyYoungerLoadAge3.expect(1.U)
      dut.io.counters.readyYoungerLoadLsuIdle.expect(1.U)
      dut.io.counters.readyYoungerLoadComputeFrontier.expect(2.U)
      dut.io.counters.readyYoungerLoadComputeFrontierLsuIdle.expect(1.U)
      dut.io.counters.readyYoungerLoadBehindMemoryHead.expect(1.U)
      dut.io.counters.readyYoungerLoadBehindMemoryHeadLsuBusy.expect(1.U)
    }
  }
}
