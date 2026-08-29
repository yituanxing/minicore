package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.soc.peripheral.{AetherUart16550, AetherUart16550Map}

class AetherUart16550Spec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherUart16550"

  private def initialize(dut: AetherUart16550): Unit = {
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.offset.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.wmask.poke(0.U)
    dut.io.complete.poke(false.B)
    dut.io.rxValid.poke(false.B)
    dut.io.rxByte.poke(0.U)
    dut.io.txReady.poke(true.B)
  }

  private def write(dut: AetherUart16550, offset: Int, value: BigInt): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.offset.poke(offset.U)
    dut.io.wdata.poke(value.U)
    dut.io.wmask.poke("hff".U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.complete.poke(false.B)
  }

  private def read(dut: AetherUart16550, offset: Int): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.offset.poke(offset.U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val value = dut.io.rdata.peek().litValue
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.complete.poke(false.B)
    value
  }

  private def pushRx(dut: AetherUart16550, value: Int): Unit = {
    dut.io.rxReady.expect(true.B)
    dut.io.rxByte.poke(value.U)
    dut.io.rxValid.poke(true.B)
    dut.clock.step()
    dut.io.rxValid.poke(false.B)
  }

  it should "reset to the board baud divisor and expose live DLL/DLM programming" in {
    simulate(new AetherUart16550(dataBits = 64, rxDepth = 4, resetDivisor = 2)) { dut =>
      initialize(dut)

      dut.io.baudDivisor.expect(2.U)
      read(dut, AetherUart16550Map.LineControl) shouldBe 0x03
      write(dut, AetherUart16550Map.LineControl, 0x80)
      read(dut, AetherUart16550Map.Data) shouldBe 2
      read(dut, AetherUart16550Map.InterruptEnable) shouldBe 0

      write(dut, AetherUart16550Map.Data, 0x34)
      write(dut, AetherUart16550Map.InterruptEnable, 0x12)
      dut.io.baudDivisor.expect("h1234".U)
      read(dut, AetherUart16550Map.Data) shouldBe 0x34
      read(dut, AetherUart16550Map.InterruptEnable) shouldBe 0x12
    }
  }

  it should "preserve the Linux ns16550 DLAB, TX and RX semantics" in {
    simulate(new AetherUart16550(dataBits = 64, rxDepth = 4)) { dut =>
      initialize(dut)

      // DLAB redirects offsets 0/1 to the divisor latches and must suppress TX.
      write(dut, AetherUart16550Map.LineControl, 0x80)
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.offset.poke(AetherUart16550Map.Data.U)
      dut.io.wdata.poke(0x12.U)
      dut.io.complete.poke(true.B)
      dut.io.txValid.expect(false.B)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.complete.poke(false.B)
      read(dut, AetherUart16550Map.Data) shouldBe 0x12

      // Clear DLAB and a data-register write becomes the external TX pulse.
      write(dut, AetherUart16550Map.LineControl, 0)
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.offset.poke(AetherUart16550Map.Data.U)
      dut.io.wdata.poke(0x41.U)
      dut.io.complete.poke(true.B)
      dut.io.txValid.expect(true.B)
      dut.io.txByte.expect(0x41.U)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.complete.poke(false.B)

      // Enable RX interrupt, enqueue one byte, then consume it exactly on the
      // terminal read-response acceptance pulse.
      write(dut, AetherUart16550Map.InterruptEnable, 1)
      pushRx(dut, 0x42)
      dut.io.rxInterrupt.expect(true.B)
      dut.io.interrupt.expect(true.B)
      (read(dut, AetherUart16550Map.LineStatus) & 1) shouldBe 1
      read(dut, AetherUart16550Map.Data) shouldBe 0x42
      dut.io.rxInterrupt.expect(false.B)
      dut.io.interrupt.expect(false.B)
    }
  }

  it should "backpressure TX writes while the physical serializer is busy" in {
    simulate(new AetherUart16550(dataBits = 64, rxDepth = 4)) { dut =>
      initialize(dut)

      write(dut, AetherUart16550Map.InterruptEnable, 2)
      dut.io.interrupt.expect(true.B)

      dut.io.txReady.poke(false.B)
      dut.io.interrupt.expect(false.B)
      (read(dut, AetherUart16550Map.LineStatus) & 0x60) shouldBe 0

      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.offset.poke(AetherUart16550Map.Data.U)
      dut.io.wdata.poke(0x5a.U)
      dut.io.wmask.poke("hff".U)
      dut.io.complete.poke(true.B)
      dut.io.ready.expect(false.B)
      dut.io.txValid.expect(false.B)
      dut.clock.step()
      dut.io.txValid.expect(false.B)

      dut.io.txReady.poke(true.B)
      dut.io.ready.expect(true.B)
      dut.io.txValid.expect(true.B)
      dut.io.txByte.expect(0x5a.U)
      dut.clock.step()
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.complete.poke(false.B)

      (read(dut, AetherUart16550Map.LineStatus) & 0x60) shouldBe 0x60
      dut.io.interrupt.expect(true.B)
    }
  }

  it should "retain THRE and interrupt-identification behavior" in {
    simulate(new AetherUart16550(dataBits = 64, rxDepth = 4)) { dut =>
      initialize(dut)

      write(dut, AetherUart16550Map.InterruptEnable, 2)
      dut.io.interrupt.expect(true.B)
      read(dut, AetherUart16550Map.InterruptIdentification) shouldBe 2

      pushRx(dut, 0x55)
      write(dut, AetherUart16550Map.InterruptEnable, 3)
      dut.io.rxInterrupt.expect(true.B)
      read(dut, AetherUart16550Map.InterruptIdentification) shouldBe 4
    }
  }
}
