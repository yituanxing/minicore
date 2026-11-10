package aethercore

import aethercore.soc.peripheral.{AetherAlteraPioGpio, AetherOcTinySpi, AetherOcTinySpiMap}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Short, pin-observable checks for the Linux spi-oc-tiny/gpio-altera SD1 seam.
  * These are real Chisel RTL simulations, not host SD-card model tests or a
  * substitute for a Product Linux/mmc_spi boot.
  */
class ProductSd1SpiGpioSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SD1 SPI and chip-select peripherals"

  private def initSpi(dut: AetherOcTinySpi): Unit = {
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.offset.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.complete.poke(false.B)
    dut.io.miso.poke(false.B)
    dut.clock.step(3)
  }

  private def writeSpi(dut: AetherOcTinySpi, address: Int, value: Int): Unit = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(true.B)
    dut.io.offset.poke(address.U)
    dut.io.wdata.poke(value.U)
    dut.io.complete.poke(true.B)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    dut.clock.step()
    dut.io.request.poke(false.B)
    dut.io.write.poke(false.B)
    dut.io.complete.poke(false.B)
  }

  private def readSpi(dut: AetherOcTinySpi, address: Int): BigInt = {
    dut.io.request.poke(true.B)
    dut.io.write.poke(false.B)
    dut.io.offset.poke(address.U)
    dut.io.ready.expect(true.B)
    dut.io.fault.expect(false.B)
    val data = dut.io.rdata.peek().litValue
    dut.io.request.poke(false.B)
    data
  }

  it should "transfer a complete MSB-first SPI byte through real SCLK/MOSI/MISO pins" in {
    simulate(new AetherOcTinySpi) { dut =>
      initSpi(dut)
      dut.io.sclk.expect(false.B) // mode 0 idle clock
      readSpi(dut, AetherOcTinySpiMap.Status) shouldBe 3

      // A presented but uncommitted MMIO write must not start a transfer.
      dut.io.request.poke(true.B)
      dut.io.write.poke(true.B)
      dut.io.offset.poke(AetherOcTinySpiMap.TxData.U)
      dut.io.wdata.poke(0xa5.U)
      dut.io.complete.poke(false.B)
      dut.clock.step(3)
      dut.io.sclk.expect(false.B)
      readSpi(dut, AetherOcTinySpiMap.Status) shouldBe 3
      dut.io.write.poke(false.B)
      dut.io.complete.poke(false.B)

      // TXR is the Linux driver-visible readiness bit. Enabling the TXR
      // interrupt while it is ready must assert the level immediately.
      writeSpi(dut, AetherOcTinySpiMap.Status, 2)
      dut.io.interrupt.expect(true.B)
      writeSpi(dut, AetherOcTinySpiMap.TxData, 0xa5)
      readSpi(dut, AetherOcTinySpiMap.Status) shouldBe 1
      dut.io.interrupt.expect(false.B)

      val slaveResponse = 0x3c // SD slave response intentionally differs from TX.
      var previousClock = dut.io.sclk.peek().litToBoolean
      var risingEdges = 0
      var sampledMosi = 0
      var finished = false
      for (_ <- 0 until 64 if !finished) {
        // A pin-level SPI slave returns an independent byte, proving RXDATA
        // comes from sampled MISO and not the queued TXDATA register.
        val outgoing = dut.io.mosi.peek().litToBoolean
        val clockNow = dut.io.sclk.peek().litToBoolean
        if (!previousClock && clockNow) {
          risingEdges += 1
          sampledMosi = (sampledMosi << 1) | (if (outgoing) 1 else 0)
        }
        val incoming = ((slaveResponse >> (7 - math.min(risingEdges, 7))) & 1) != 0
        dut.io.miso.poke(incoming.B)
        previousClock = clockNow
        dut.clock.step()
        finished = risingEdges == 8 &&
          readSpi(dut, AetherOcTinySpiMap.Status) == 3
      }
      risingEdges shouldBe 8
      sampledMosi shouldBe 0xa5
      readSpi(dut, AetherOcTinySpiMap.RxData) shouldBe slaveResponse
      readSpi(dut, AetherOcTinySpiMap.Status) shouldBe 3
      dut.io.interrupt.expect(true.B)

      writeSpi(dut, AetherOcTinySpiMap.Status, 0)
      dut.io.interrupt.expect(false.B)
    }
  }

  it should "hold SD chip select inactive until gpio-altera drives its output" in {
    simulate(new AetherAlteraPioGpio) { dut =>
      dut.io.request.poke(false.B)
      dut.io.write.poke(false.B)
      dut.io.offset.poke(0.U)
      dut.io.wdata.poke(0.U)
      dut.io.complete.poke(false.B)
      dut.clock.step(2)
      dut.io.pin.expect(true.B) // CS_N pull-up while input/undriven

      def write(address: Int, value: Int, commit: Boolean = true): Unit = {
        dut.io.request.poke(true.B)
        dut.io.write.poke(true.B)
        dut.io.offset.poke(address.U)
        dut.io.wdata.poke(value.U)
        dut.io.complete.poke(commit.B)
        dut.io.ready.expect(true.B)
        dut.io.fault.expect(false.B)
        dut.clock.step()
        dut.io.request.poke(false.B)
        dut.io.write.poke(false.B)
        dut.io.complete.poke(false.B)
      }
      def read(address: Int): BigInt = {
        dut.io.request.poke(true.B)
        dut.io.offset.poke(address.U)
        val result = dut.io.rdata.peek().litValue
        dut.io.request.poke(false.B)
        result
      }

      write(0, 0, commit = false)
      write(4, 1, commit = false)
      read(4) shouldBe 0
      dut.io.pin.expect(true.B)

      // Linux may program CS data while still configured as an input.
      write(0, 0)
      dut.io.pin.expect(true.B)
      write(4, 1) // output enable makes CS_N assert low
      dut.io.pin.expect(false.B)
      read(0) shouldBe 0
      read(4) shouldBe 1

      write(0, 1) // deassert physical SD card CS_N
      dut.io.pin.expect(true.B)
      read(0) shouldBe 1
      write(0, 0)
      write(4, 0) // returning to input must release CS_N high
      dut.io.pin.expect(true.B)
      read(0) shouldBe 1
      read(4) shouldBe 0
    }
  }
}
