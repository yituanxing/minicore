package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.phy.AetherUart8N1Phy

class AetherUart8N1PhySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherUart8N1Phy"

  private def initialize(dut: AetherUart8N1Phy, divisor: Int): Unit = {
    dut.io.uartClockTick.poke(true.B)
    dut.io.baudDivisor.poke(divisor.U)
    dut.io.txValid.poke(false.B)
    dut.io.txByte.poke(0.U)
    dut.io.rxReady.poke(false.B)
    dut.io.serialRx.poke(true.B)
    dut.clock.step(4)
    dut.io.txReady.expect(true.B)
    dut.io.serialTx.expect(true.B)
    dut.io.rxValid.expect(false.B)
  }

  private def expectSerialFor(
      dut: AetherUart8N1Phy,
      level: Boolean,
      cycles: Int
  ): Unit = {
    for (_ <- 0 until cycles) {
      dut.io.serialTx.expect(level.B)
      dut.clock.step()
    }
  }

  private def driveRxFor(
      dut: AetherUart8N1Phy,
      level: Boolean,
      cycles: Int
  ): Unit = {
    dut.io.serialRx.poke(level.B)
    dut.clock.step(cycles)
  }

  it should "serialize start, eight LSB-first data bits and stop at divisor one" in {
    simulate(new AetherUart8N1Phy) { dut =>
      initialize(dut, divisor = 1)

      val value = 0xa5
      dut.io.txByte.poke(value.U)
      dut.io.txValid.poke(true.B)
      dut.io.txReady.expect(true.B)
      dut.clock.step()
      dut.io.txValid.poke(false.B)
      dut.io.txReady.expect(false.B)

      expectSerialFor(dut, level = false, cycles = 16)
      for (bit <- 0 until 8) {
        expectSerialFor(
          dut,
          level = ((value >> bit) & 1) != 0,
          cycles = 16
        )
      }
      expectSerialFor(dut, level = true, cycles = 16)

      dut.io.txReady.expect(true.B)
      dut.io.serialTx.expect(true.B)
    }
  }

  it should "scale every transmitted bit by the live divisor" in {
    simulate(new AetherUart8N1Phy) { dut =>
      initialize(dut, divisor = 2)

      // 0x01 gives a visible start(0) -> data0(1) edge after exactly 32 ticks.
      dut.io.txByte.poke(1.U)
      dut.io.txValid.poke(true.B)
      dut.clock.step()
      dut.io.txValid.poke(false.B)

      expectSerialFor(dut, level = false, cycles = 32)
      expectSerialFor(dut, level = true, cycles = 32)

      // data1 is zero, proving the next edge is also spaced by 32 ticks.
      expectSerialFor(dut, level = false, cycles = 32)
    }
  }

  it should "receive an 8N1 byte and hold it until the register block accepts it" in {
    simulate(new AetherUart8N1Phy) { dut =>
      initialize(dut, divisor = 1)
      val value = 0x5a

      // The two-flop synchronizer adds a small fixed detection delay, but once
      // the start edge is seen the PHY samples at the normal 16x UART cadence.
      driveRxFor(dut, level = false, cycles = 16)
      for (bit <- 0 until 8) {
        driveRxFor(
          dut,
          level = ((value >> bit) & 1) != 0,
          cycles = 16
        )
      }
      driveRxFor(dut, level = true, cycles = 24)

      dut.io.rxValid.expect(true.B)
      dut.io.rxByte.expect(value.U)

      // Backpressure must retain the recovered byte exactly.
      dut.clock.step(5)
      dut.io.rxValid.expect(true.B)
      dut.io.rxByte.expect(value.U)

      dut.io.rxReady.poke(true.B)
      dut.clock.step()
      dut.io.rxReady.poke(false.B)
      dut.io.rxValid.expect(false.B)
    }
  }

  it should "drop a frame whose stop bit is low" in {
    simulate(new AetherUart8N1Phy) { dut =>
      initialize(dut, divisor = 1)
      val value = 0x3c

      driveRxFor(dut, level = false, cycles = 16)
      for (bit <- 0 until 8) {
        driveRxFor(
          dut,
          level = ((value >> bit) & 1) != 0,
          cycles = 16
        )
      }
      driveRxFor(dut, level = false, cycles = 20)
      dut.io.rxValid.expect(false.B)

      // Return to idle high and prove no stale byte appears.
      driveRxFor(dut, level = true, cycles = 20)
      dut.io.rxValid.expect(false.B)
    }
  }
}
