package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.MachinePlic

class MachinePlicSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachinePlic"

  private def initialize(dut: MachinePlic): Unit = {
    dut.io.sources.poke(0.U)
    dut.io.priorityWriteEnable.poke(false.B)
    dut.io.priorityWriteId.poke(0.U)
    dut.io.priorityWriteData.poke(0.U)
    dut.io.enableWrite.poke(false.B)
    dut.io.enableWriteData.poke(0.U)
    dut.io.thresholdWrite.poke(false.B)
    dut.io.thresholdWriteData.poke(0.U)
    dut.io.claimRead.poke(false.B)
    dut.io.completeWrite.poke(false.B)
    dut.io.completeId.poke(0.U)
  }

  private def writePriority(
      dut: MachinePlic,
      sourceId: Int,
      priority: Int
  ): Unit = {
    dut.io.priorityWriteId.poke(sourceId.U)
    dut.io.priorityWriteData.poke(priority.U)
    dut.io.priorityWriteEnable.poke(true.B)
    dut.clock.step()
    dut.io.priorityWriteEnable.poke(false.B)
  }

  private def writeEnable(dut: MachinePlic, mask: Int): Unit = {
    dut.io.enableWriteData.poke(mask.U)
    dut.io.enableWrite.poke(true.B)
    dut.clock.step()
    dut.io.enableWrite.poke(false.B)
  }

  private def writeThreshold(dut: MachinePlic, value: Int): Unit = {
    dut.io.thresholdWriteData.poke(value.U)
    dut.io.thresholdWrite.poke(true.B)
    dut.clock.step()
    dut.io.thresholdWrite.poke(false.B)
  }

  private def claim(dut: MachinePlic): BigInt = {
    val sourceId = dut.io.claim.peek().litValue
    dut.io.claimRead.poke(true.B)
    dut.clock.step()
    dut.io.claimRead.poke(false.B)
    sourceId
  }

  private def complete(dut: MachinePlic, sourceId: Int): Unit = {
    dut.io.completeId.poke(sourceId.U)
    dut.io.completeWrite.poke(true.B)
    dut.clock.step()
    dut.io.completeWrite.poke(false.B)
  }

  it should "arbitrate, claim and complete level-sensitive sources precisely" in {
    simulate(new MachinePlic(sourceCount = 4, priorityBits = 3)) { dut =>
      initialize(dut)

      dut.io.interrupt.expect(false.B)
      dut.io.claim.expect(0.U)
      dut.io.pending.expect(0.U)
      dut.io.inService.expect(0.U)

      writePriority(dut, sourceId = 1, priority = 1)
      writePriority(dut, sourceId = 2, priority = 3)
      writePriority(dut, sourceId = 3, priority = 3)
      writePriority(dut, sourceId = 4, priority = 2)
      writeEnable(dut, mask = 0xf)

      dut.io.sources.poke("b0111".U)
      dut.io.pending.expect("b0111".U)
      dut.io.interrupt.expect(true.B)
      dut.io.claim.expect(2.U)

      claim(dut) shouldBe 2
      dut.io.inService.expect("b0010".U)
      dut.io.pending.expect("b0101".U)
      dut.io.claim.expect(3.U)

      claim(dut) shouldBe 3
      dut.io.inService.expect("b0110".U)
      dut.io.claim.expect(1.U)

      // Completing an invalid source is ignored and cannot corrupt service
      // state.
      complete(dut, sourceId = 0)
      dut.io.inService.expect("b0110".U)

      // Source 2 is still asserted. Completion therefore makes it pending
      // again immediately, and its higher priority wins over source 1.
      complete(dut, sourceId = 2)
      dut.io.inService.expect("b0100".U)
      dut.io.pending.expect("b0011".U)
      dut.io.claim.expect(2.U)

      writeThreshold(dut, value = 3)
      dut.io.interrupt.expect(false.B)
      dut.io.claim.expect(0.U)

      writePriority(dut, sourceId = 1, priority = 4)
      dut.io.interrupt.expect(true.B)
      dut.io.claim.expect(1.U)

      // Deasserting the only source above threshold removes the request
      // without requiring a claim.
      dut.io.sources.poke("b0110".U)
      dut.io.interrupt.expect(false.B)
      dut.io.claim.expect(0.U)

      writeThreshold(dut, value = 0)
      dut.io.claim.expect(2.U)

      // Disabled sources never participate in arbitration even while pending.
      writeEnable(dut, mask = 0x4)
      dut.io.claim.expect(0.U)
      dut.io.interrupt.expect(false.B)

      // Complete source 3 while its level remains high. It must become pending
      // again and assert the context interrupt because it is still enabled.
      complete(dut, sourceId = 3)
      dut.io.inService.expect(0.U)
      dut.io.pending.expect("b0110".U)
      dut.io.claim.expect(3.U)
      dut.io.interrupt.expect(true.B)
    }
  }
}
