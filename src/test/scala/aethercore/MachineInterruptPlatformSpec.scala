package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{MachinePlicMmioMap, MachineUartRxMap}
import aethercore.sim.MachineInterruptPlatform

class MachineInterruptPlatformSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineInterruptPlatform"

  private val plicBase = BigInt("0c000000", 16)
  private val uartBase = BigInt("10000000", 16)

  private def initialize(dut: MachineInterruptPlatform): Unit = {
    dut.io.rxValid.poke(false.B)
    dut.io.rxByte.poke(0.U)
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
  }

  private def write(
      dut: MachineInterruptPlatform,
      address: BigInt,
      value: BigInt,
      mask: Int = 0xf
  ): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(value.U)
    dut.io.wmask.poke(mask.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
  }

  private def read(dut: MachineInterruptPlatform, address: BigInt): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(address.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val value = dut.io.rdata.peek().litValue
    dut.clock.step()
    dut.io.request.poke(false.B)
    value
  }

  private def push(dut: MachineInterruptPlatform, value: Int): Unit = {
    dut.io.rxReady.expect(true.B)
    dut.io.rxByte.poke(value.U)
    dut.io.rxValid.poke(true.B)
    dut.clock.step()
    dut.io.rxValid.poke(false.B)
  }

  it should "route UART RX level interrupts through architectural PLIC source one" in {
    simulate(new MachineInterruptPlatform()) { dut =>
      initialize(dut)

      write(dut, plicBase + MachinePlicMmioMap.priority(1), 3)
      write(dut, plicBase + MachinePlicMmioMap.Enable, 2)
      write(dut, plicBase + MachinePlicMmioMap.Threshold, 0)
      write(dut, uartBase + MachineUartRxMap.Control, 1)

      dut.io.externalInterrupt.expect(false.B)
      push(dut, 0x5a)
      dut.io.uartInterrupt.expect(true.B)
      dut.io.externalInterrupt.expect(true.B)
      read(dut, plicBase + MachinePlicMmioMap.Pending) shouldBe 2

      // Claiming source one suppresses repeated delivery while it is in service.
      read(dut, plicBase + MachinePlicMmioMap.ClaimComplete) shouldBe 1
      dut.io.externalInterrupt.expect(false.B)
      dut.io.uartInterrupt.expect(true.B)

      // Draining the FIFO removes the level before completion, so completion
      // does not immediately re-pend the source.
      read(dut, uartBase + MachineUartRxMap.Data) shouldBe 0x5a
      dut.io.uartInterrupt.expect(false.B)
      write(dut, plicBase + MachinePlicMmioMap.ClaimComplete, 1)
      dut.io.externalInterrupt.expect(false.B)
      read(dut, plicBase + MachinePlicMmioMap.Pending) shouldBe 0

      push(dut, 0x33)
      dut.io.externalInterrupt.expect(true.B)
      write(dut, uartBase + MachineUartRxMap.Control, 0)
      dut.io.uartInterrupt.expect(false.B)
      dut.io.externalInterrupt.expect(false.B)
    }
  }

  it should "map QEMU-virt UART source ten through the N5 Supervisor context" in {
    simulate(new MachineInterruptPlatform(
      sourceCount = 52,
      plicEnableBase = MachinePlicMmioMap.SupervisorEnable,
      plicThresholdOffset = MachinePlicMmioMap.SupervisorThreshold,
      plicClaimCompleteOffset = MachinePlicMmioMap.SupervisorClaimComplete,
      uartSourceId = 10
    )) { dut =>
      initialize(dut)

      write(dut, plicBase + MachinePlicMmioMap.priority(10), 3)
      write(dut, plicBase + MachinePlicMmioMap.SupervisorEnable, BigInt(1) << 10)
      write(dut, plicBase + MachinePlicMmioMap.SupervisorThreshold, 0)
      write(dut, uartBase + MachineUartRxMap.Control, 1)

      push(dut, 0x41)
      dut.io.uartInterrupt.expect(true.B)
      dut.io.externalInterrupt.expect(true.B)
      read(dut, plicBase + MachinePlicMmioMap.Pending) shouldBe (BigInt(1) << 10)
      read(dut, plicBase + MachinePlicMmioMap.SupervisorClaimComplete) shouldBe 10

      read(dut, uartBase + MachineUartRxMap.Data) shouldBe 0x41
      write(dut, plicBase + MachinePlicMmioMap.SupervisorClaimComplete, 10)
      dut.io.externalInterrupt.expect(false.B)
    }
  }

  it should "fault unmapped platform addresses without touching either device" in {
    simulate(new MachineInterruptPlatform()) { dut =>
      initialize(dut)

      dut.io.request.poke(true.B)
      dut.io.write.poke(false.B)
      dut.io.address.poke(BigInt("20000000", 16).U)
      dut.io.ready.expect(true.B)
      dut.io.fault.expect(true.B)
      dut.io.rdata.expect(0.U)
      dut.clock.step()
      dut.io.request.poke(false.B)

      dut.io.externalInterrupt.expect(false.B)
      dut.io.uartInterrupt.expect(false.B)
    }
  }
}
