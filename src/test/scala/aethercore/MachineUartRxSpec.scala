package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{MachineUartRx, MachineUartRxMap}

class MachineUartRxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineUartRx"

  private def initialize(dut: MachineUartRx): Unit = {
    dut.io.rxValid.poke(false.B)
    dut.io.rxByte.poke(0.U)
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
  }

  private def write(
      dut: MachineUartRx,
      address: Int,
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

  private def read(dut: MachineUartRx, address: Int): BigInt = {
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

  private def push(dut: MachineUartRx, value: Int): Unit = {
    dut.io.rxReady.expect(true.B)
    dut.io.rxByte.poke(value.U)
    dut.io.rxValid.poke(true.B)
    dut.clock.step()
    dut.io.rxValid.poke(false.B)
  }

  private def expectReadFault(dut: MachineUartRx, address: Int): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.address.poke(address.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(true.B)
    dut.io.rdata.expect(0.U)
    dut.clock.step()
    dut.io.request.poke(false.B)
  }

  it should "raise a level interrupt and preserve FIFO byte order" in {
    simulate(new MachineUartRx(depth = 4)) { dut =>
      initialize(dut)

      write(dut, MachineUartRxMap.Control, 1)
      dut.io.interruptEnable.expect(true.B)
      dut.io.interrupt.expect(false.B)

      push(dut, 0x41)
      push(dut, 0x42)
      push(dut, 0x43)

      dut.io.count.expect(3.U)
      dut.io.interrupt.expect(true.B)
      val status = read(dut, MachineUartRxMap.Status)
      (status & 1) shouldBe 1
      ((status >> 8) & 0xff) shouldBe 3

      read(dut, MachineUartRxMap.Data) shouldBe 0x41
      dut.io.count.expect(2.U)
      dut.io.interrupt.expect(true.B)
      read(dut, MachineUartRxMap.Data) shouldBe 0x42
      read(dut, MachineUartRxMap.Data) shouldBe 0x43

      dut.io.count.expect(0.U)
      dut.io.interrupt.expect(false.B)
      read(dut, MachineUartRxMap.Data) shouldBe 0
    }
  }

  it should "record overrun, allow simultaneous pop and push, and honor control masks" in {
    simulate(new MachineUartRx(depth = 4)) { dut =>
      initialize(dut)
      write(dut, MachineUartRxMap.Control, 1, mask = 0x1)

      push(dut, 0x10)
      push(dut, 0x11)
      push(dut, 0x12)
      push(dut, 0x13)
      dut.io.count.expect(4.U)
      dut.io.rxReady.expect(false.B)

      dut.io.rxByte.poke(0x20.U)
      dut.io.rxValid.poke(true.B)
      dut.clock.step()
      dut.io.rxValid.poke(false.B)
      dut.io.overrun.expect(true.B)
      dut.io.count.expect(4.U)

      // A data read frees one slot in the same cycle that a new byte arrives.
      dut.io.request.poke(true.B)
      dut.io.write.poke(false.B)
      dut.io.address.poke(MachineUartRxMap.Data.U)
      dut.io.rxByte.poke(0x21.U)
      dut.io.rxValid.poke(true.B)
      dut.io.rxReady.expect(true.B)
      dut.io.rdata.expect(0x10.U)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.rxValid.poke(false.B)
      dut.io.count.expect(4.U)

      read(dut, MachineUartRxMap.Data) shouldBe 0x11
      read(dut, MachineUartRxMap.Data) shouldBe 0x12
      read(dut, MachineUartRxMap.Data) shouldBe 0x13
      read(dut, MachineUartRxMap.Data) shouldBe 0x21

      write(dut, MachineUartRxMap.Status, 2, mask = 0x1)
      dut.io.overrun.expect(false.B)

      // A high-byte write must preserve the low interrupt-enable bit.
      write(dut, MachineUartRxMap.Control, 0xff00, mask = 0x2)
      dut.io.interruptEnable.expect(true.B)
      write(dut, MachineUartRxMap.Control, 0, mask = 0x1)
      dut.io.interruptEnable.expect(false.B)

      expectReadFault(dut, MachineUartRxMap.Data + 2)
      expectReadFault(dut, 0xc)

      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.address.poke(MachineUartRxMap.Data.U)
      dut.io.wdata.poke(0x55.U)
      dut.io.wmask.poke(0xf.U)
      dut.io.fault.expect(true.B)
      dut.clock.step()
      dut.io.request.poke(false.B)
    }
  }
}
