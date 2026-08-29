package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.phy.AetherFractionalTickGenerator

class AetherFractionalTickGeneratorSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherFractionalTickGenerator"

  private def countTicks(
      dut: AetherFractionalTickGenerator,
      cycles: Int
  ): Int = {
    var ticks = 0
    for (_ <- 0 until cycles) {
      if (dut.io.tick.peek().litToBoolean) {
        ticks += 1
      }
      dut.clock.step()
    }
    ticks
  }

  it should "generate an exact divide-by-two 10 MHz enable from a 20 MHz board clock" in {
    simulate(new AetherFractionalTickGenerator(
      sourceFrequencyHz = 20,
      targetFrequencyHz = 10
    )) { dut =>
      countTicks(dut, cycles = 200) shouldBe 100
    }
  }

  it should "preserve a non-integer long-term frequency ratio" in {
    simulate(new AetherFractionalTickGenerator(
      sourceFrequencyHz = 20,
      targetFrequencyHz = 3
    )) { dut =>
      countTicks(dut, cycles = 200) shouldBe 30
    }
  }

  it should "never emit two ticks in one source cycle" in {
    simulate(new AetherFractionalTickGenerator(
      sourceFrequencyHz = 25,
      targetFrequencyHz = 9
    )) { dut =>
      countTicks(dut, cycles = 250) shouldBe 90
    }
  }
}
