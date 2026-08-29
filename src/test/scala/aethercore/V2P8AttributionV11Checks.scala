package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.sim.V2AttributionV11CounterBank

/** Freeze the v1.1 causal identities independently from the Linux workload. */
trait V2P8AttributionV11Checks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def clear(dut: V2AttributionV11CounterBank): Unit = {
    dut.io.events.branchResolved.poke(false.B)
    dut.io.events.branchTaken.poke(false.B)
    dut.io.events.branchRecovery.poke(false.B)
    dut.io.events.branchSquashedUops.poke(0.U)

    dut.io.events.robNonEmpty.poke(false.B)
    dut.io.events.issueLaunch.poke(false.B)
    dut.io.events.issueRequestVisible.poke(false.B)
    dut.io.events.shadowComputeReadyCount.poke(0.U)

    dut.io.events.frontendSecondParcel.poke(false.B)
    dut.io.events.frontendBound.poke(false.B)

    dut.io.events.memoryTerminalValid.poke(false.B)
    dut.io.events.memoryTerminalReady.poke(false.B)
  }

  behavior of "AetherCore v2 P8 attribution v1.1 counters"

  it should "separate recovery, launch opportunity, second parcels and terminal holds" in {
    simulate(new V2AttributionV11CounterBank) { dut =>
      clear(dut)

      // Cycle 1: a taken branch recovers three younger uOps while a real launch
      // happens. The same synthetic cycle also checks the terminal-fire bucket.
      dut.io.events.branchResolved.poke(true.B)
      dut.io.events.branchTaken.poke(true.B)
      dut.io.events.branchRecovery.poke(true.B)
      dut.io.events.branchSquashedUops.poke(3.U)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.issueLaunch.poke(true.B)
      dut.io.events.issueRequestVisible.poke(true.B)
      dut.io.events.frontendSecondParcel.poke(true.B)
      dut.io.events.frontendBound.poke(true.B)
      dut.io.events.memoryTerminalValid.poke(true.B)
      dut.io.events.memoryTerminalReady.poke(true.B)
      dut.clock.step()

      // Cycle 2: resolved not-taken branch; production has no visible request,
      // but the shadow selector sees two fresh compute candidates. This is the
      // conservative dual-compute opportunity signal.
      clear(dut)
      dut.io.events.branchResolved.poke(true.B)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.shadowComputeReadyCount.poke(2.U)
      dut.io.events.memoryTerminalValid.poke(true.B)
      dut.io.events.memoryTerminalReady.poke(false.B)
      dut.clock.step()

      // Cycle 3: ROB contains work, but neither production nor shadow policy can
      // launch anything. Do not relabel this as dependency wait yet; it remains
      // the exact and weaker "no launchable request" observation.
      clear(dut)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.clock.step()

      // Cycle 4: no live ROB work and no launch opportunity.
      clear(dut)
      dut.clock.step()

      // Cycle 5: visible production request launches normally.
      clear(dut)
      dut.io.events.robNonEmpty.poke(true.B)
      dut.io.events.issueLaunch.poke(true.B)
      dut.io.events.issueRequestVisible.poke(true.B)
      dut.clock.step()

      dut.io.counters.cycles.expect(5.U)

      dut.io.counters.branchResolved.expect(2.U)
      dut.io.counters.branchTaken.expect(1.U)
      dut.io.counters.branchRecovery.expect(1.U)
      dut.io.counters.branchSquashedUops.expect(3.U)

      // Exact issue-slot partition: 2 + 1 + 1 + 1 == 5.
      dut.io.counters.issueLaunch.expect(2.U)
      dut.io.counters.issueIdleLaunchable.expect(1.U)
      dut.io.counters.issueIdleNoLaunchable.expect(1.U)
      dut.io.counters.issueInactive.expect(1.U)
      dut.io.counters.shadowComputeReady.expect(1.U)
      dut.io.counters.dualComputeCandidate.expect(1.U)

      dut.io.counters.frontendSecondParcel.expect(1.U)
      dut.io.counters.frontendBoundSecondParcel.expect(1.U)

      // Terminal valid is independently and exactly fire + hold.
      dut.io.counters.memoryTerminalValid.expect(2.U)
      dut.io.counters.memoryTerminalFire.expect(1.U)
      dut.io.counters.memoryTerminalHold.expect(1.U)
    }
  }
}
