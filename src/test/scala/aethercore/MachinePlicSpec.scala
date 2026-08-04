package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.MachinePlic

class MachinePlicSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachinePlic"

  private def idle(dut: MachinePlic): Unit = {
    dut.io.sources.poke(0.U)
    dut.io.enable.poke(0.U)
    dut.io.threshold.poke(0.U)
    dut.io.claim.poke(false.B)
    dut.io.complete.valid.poke(false.B)
    dut.io.complete.bits.poke(0.U)
    for (priority <- dut.io.priorities) {
      priority.poke(0.U)
    }
  }

  private def pulseSource(dut: MachinePlic, bit: Int): Unit = {
    dut.io.sources.poke((BigInt(1) << bit).U)
    dut.clock.step()
    dut.io.sources.poke(0.U)
    dut.clock.step()
  }

  it should "latch an edge and consume it exactly once on claim" in {
    simulate(new MachinePlic(4, 3)) { dut =>
      idle(dut)
      dut.io.enable.poke("b0001".U)
      dut.io.priorities(0).poke(3.U)
      pulseSource(dut, 0)

      dut.io.pending.expect("b0001".U)
      dut.io.interrupt.expect(true.B)
      dut.io.claimId.expect(1.U)

      dut.io.claim.poke(true.B)
      dut.clock.step()
      dut.io.claim.poke(false.B)
      dut.io.pending.expect(0.U)
      dut.io.interrupt.expect(false.B)
      dut.io.claimId.expect(0.U)
    }
  }

  it should "select highest priority and break ties toward the lower source ID" in {
    simulate(new MachinePlic(4, 3)) { dut =>
      idle(dut)
      dut.io.enable.poke("b1111".U)
      dut.io.priorities(0).poke(2.U)
      dut.io.priorities(1).poke(5.U)
      dut.io.priorities(2).poke(5.U)
      dut.io.priorities(3).poke(1.U)

      dut.io.sources.poke("b1111".U)
      dut.clock.step()
      dut.io.sources.poke(0.U)
      dut.clock.step()

      dut.io.claimId.expect(2.U)
      dut.io.claim.poke(true.B)
      dut.clock.step()
      dut.io.claim.poke(false.B)
      dut.io.claimId.expect(3.U)
    }
  }

  it should "respect enable bits, zero priority and threshold" in {
    simulate(new MachinePlic(3, 3)) { dut =>
      idle(dut)
      dut.io.enable.poke("b101".U)
      dut.io.priorities(0).poke(0.U)
      dut.io.priorities(1).poke(7.U)
      dut.io.priorities(2).poke(4.U)
      dut.io.threshold.poke(4.U)
      dut.io.sources.poke("b111".U)
      dut.clock.step()
      dut.io.sources.poke(0.U)
      dut.clock.step()

      dut.io.pending.expect("b111".U)
      dut.io.interrupt.expect(false.B)
      dut.io.claimId.expect(0.U)

      dut.io.threshold.poke(3.U)
      dut.io.interrupt.expect(true.B)
      dut.io.claimId.expect(3.U)
    }
  }

  it should "not lose a new source edge at a simultaneous claim boundary" in {
    simulate(new MachinePlic(2, 2)) { dut =>
      idle(dut)
      dut.io.enable.poke("b11".U)
      dut.io.priorities(0).poke(2.U)
      dut.io.priorities(1).poke(1.U)
      pulseSource(dut, 0)
      dut.io.claimId.expect(1.U)

      dut.io.claim.poke(true.B)
      dut.io.sources.poke("b10".U)
      dut.clock.step()
      dut.io.claim.poke(false.B)
      dut.io.sources.poke(0.U)
      dut.io.pending.expect("b10".U)
      dut.io.claimId.expect(2.U)
    }
  }
}
