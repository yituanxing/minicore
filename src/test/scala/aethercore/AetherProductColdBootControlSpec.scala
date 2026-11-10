package aethercore

import aethercore.soc.AetherProductColdBootControl
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AetherProductColdBootControlSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product FPGA cold boot DDR handoff"

  private def initialize(dut: AetherProductColdBootControl): Unit = {
    dut.io.clockLocked.poke(false.B)
    dut.io.ddrCalibrated.poke(false.B)
    dut.io.imageLoaded.poke(false.B)
    dut.io.loaderIdle.poke(false.B)
    dut.io.imageLoadFailed.poke(false.B)
  }

  private def neitherOwns(dut: AetherProductColdBootControl): Unit = {
    dut.io.loaderOwnsDdr.expect(false.B)
    dut.io.cpuOwnsDdr.expect(false.B)
    dut.io.cpuReleased.expect(false.B)
  }

  it should "wait for real DDR readiness, a fresh image and drained loader AXI" in {
    simulate(new AetherProductColdBootControl) { dut =>
      initialize(dut)
      neitherOwns(dut)

      dut.io.clockLocked.poke(true.B)
      dut.clock.step()
      neitherOwns(dut)

      dut.io.ddrCalibrated.poke(true.B)
      dut.clock.step()
      dut.io.loaderOwnsDdr.expect(true.B)
      dut.io.cpuReleased.expect(false.B)

      // Stale loaded=1 on entering DDR ownership is never sufficient.
      dut.io.imageLoaded.poke(true.B)
      dut.io.loaderIdle.poke(true.B)
      dut.clock.step(3)
      dut.io.loaderOwnsDdr.expect(true.B)
      dut.io.cpuReleased.expect(false.B)

      dut.io.imageLoaded.poke(false.B)
      dut.clock.step()
      dut.io.imageLoaded.poke(true.B)
      dut.io.loaderIdle.poke(false.B)
      dut.clock.step(2)
      dut.io.loaderOwnsDdr.expect(true.B)
      dut.io.cpuReleased.expect(false.B)

      // imageLoaded cannot release reset until every loader B/R returned.
      dut.io.loaderIdle.poke(true.B)
      dut.clock.step()
      neitherOwns(dut) // guaranteed AXI turnaround cycle
      dut.clock.step()
      dut.io.loaderOwnsDdr.expect(false.B)
      dut.io.cpuOwnsDdr.expect(true.B)
      dut.io.cpuReleased.expect(true.B)
      dut.io.bootFault.expect(false.B)
    }
  }

  it should "isolate the CPU immediately and latch any post-release DDR fault" in {
    simulate(new AetherProductColdBootControl) { dut =>
      initialize(dut)
      dut.io.clockLocked.poke(true.B)
      dut.clock.step()
      dut.io.ddrCalibrated.poke(true.B)
      dut.clock.step()
      dut.clock.step() // observe loaded low
      dut.io.imageLoaded.poke(true.B)
      dut.io.loaderIdle.poke(true.B)
      dut.clock.step(2) // handoff -> CPU active
      dut.io.cpuReleased.expect(true.B)

      // Before a state transition edge, AXI grant and CPU reset are cut off.
      dut.io.ddrCalibrated.poke(false.B)
      neitherOwns(dut)
      dut.clock.step()
      dut.io.bootFault.expect(true.B)
      dut.io.ddrCalibrated.poke(true.B)
      dut.io.imageLoaded.poke(false.B)
      dut.clock.step(4)
      neitherOwns(dut)
      dut.io.bootFault.expect(true.B)

      // Restart requires a full-board reset and a *new* loader handoff.
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      neitherOwns(dut)
      dut.io.bootFault.expect(false.B)
      dut.clock.step(2)
      dut.io.loaderOwnsDdr.expect(true.B)
      dut.clock.step() // new post-grant loaded-low sample
      dut.io.imageLoaded.poke(true.B)
      dut.clock.step(2)
      dut.io.cpuReleased.expect(true.B)
    }
  }

  it should "hold both DDR masters disconnected on loader failure" in {
    simulate(new AetherProductColdBootControl) { dut =>
      initialize(dut)
      dut.io.clockLocked.poke(true.B)
      dut.clock.step()
      dut.io.ddrCalibrated.poke(true.B)
      dut.clock.step()
      dut.io.loaderOwnsDdr.expect(true.B)

      dut.io.imageLoadFailed.poke(true.B)
      neitherOwns(dut)
      dut.io.bootFault.expect(true.B)
      dut.clock.step()
      dut.io.imageLoadFailed.poke(false.B)
      dut.io.loaderOwnsDdr.expect(false.B)
      dut.io.bootFault.expect(true.B)
    }
  }
}
