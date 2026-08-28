package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.peripheral.AetherAclintMtimer

class AetherAclintMtimerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherAclintMtimer"

  private def initialize(dut: AetherAclintMtimer): Unit = {
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.selectMtimecmp.poke(false.B)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.complete.poke(false.B)
  }

  private def write(
      dut: AetherAclintMtimer,
      selectMtimecmp: Boolean,
      value: BigInt,
      mask: BigInt = 0xff
  ): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.selectMtimecmp.poke(selectMtimecmp.B)
    dut.io.wdata.poke(value.U)
    dut.io.wmask.poke(mask.U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.complete.poke(false.B)
  }

  private def read(dut: AetherAclintMtimer, selectMtimecmp: Boolean): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.selectMtimecmp.poke(selectMtimecmp.B)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val value = dut.io.rdata.peek().litValue
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.complete.poke(false.B)
    value
  }

  it should "own MTIME progression and MTIMECMP interrupt generation" in {
    simulate(new AetherAclintMtimer()) { dut =>
      initialize(dut)

      dut.io.mtimecmp.expect("hffffffffffffffff".U)
      dut.io.interrupt.expect(false.B)

      // Program a deterministic timer origin and a near compare point.
      write(dut, selectMtimecmp = false, value = 0)
      write(dut, selectMtimecmp = true, value = 3)

      dut.io.mtime.expect(1.U)
      dut.io.interrupt.expect(false.B)
      dut.clock.step()
      dut.io.mtime.expect(2.U)
      dut.io.interrupt.expect(false.B)
      dut.clock.step()
      dut.io.mtime.expect(3.U)
      dut.io.interrupt.expect(true.B)

      // Moving MTIMECMP into the future deasserts the level interrupt.
      write(dut, selectMtimecmp = true, value = 100)
      dut.io.interrupt.expect(false.B)
    }
  }

  it should "preserve byte masks and mutate state only on terminal acceptance" in {
    simulate(new AetherAclintMtimer()) { dut =>
      initialize(dut)

      write(
        dut,
        selectMtimecmp = true,
        value = BigInt("1122334455667788", 16)
      )
      read(dut, selectMtimecmp = true) shouldBe BigInt("1122334455667788", 16)

      write(
        dut,
        selectMtimecmp = true,
        value = BigInt("00000000000000aa", 16),
        mask = 0x01
      )
      read(dut, selectMtimecmp = true) shouldBe BigInt("11223344556677aa", 16)

      // A presented write without the exact terminal response acceptance pulse
      // must not alter the timer register.
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.selectMtimecmp.poke(true.B)
      dut.io.wdata.poke(0.U)
      dut.io.wmask.poke("hff".U)
      dut.io.complete.poke(false.B)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      read(dut, selectMtimecmp = true) shouldBe BigInt("11223344556677aa", 16)
    }
  }
}
