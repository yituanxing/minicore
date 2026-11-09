package aethercore

import aethercore.core.{PmpConstants, PmpDecodedEntry, PmpRangeDecoder, PmpSerialRangePredecoder}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/** Exact per-entry oracle against the existing 16-lane PMP decoder. */
class PmpSerialRangePredecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "FPGA serial live-CSR PMP range predecoder"

  private class Harness extends Module {
    val io = IO(new Bundle {
      val config = Input(Vec(16, UInt(8.W)))
      val pmpAddress = Input(Vec(16, UInt(54.W)))
      val refresh = Input(Bool())
      val busy = Output(Bool())
      val ready = Output(Bool())
      val cached = Output(Vec(16, new PmpDecodedEntry(56)))
      val reference = Output(Vec(16, new PmpDecodedEntry(56)))
    })
    val serial = Module(new PmpSerialRangePredecoder(64, 16, 56))
    val reference = Module(new PmpRangeDecoder(64, 16, 56))
    serial.io.config := io.config
    serial.io.pmpAddress := io.pmpAddress
    serial.io.refresh := io.refresh
    reference.io.config := io.config
    reference.io.pmpAddress := io.pmpAddress
    io.busy := serial.io.busy
    io.ready := serial.io.ready
    io.cached := serial.io.ranges
    io.reference := reference.io.ranges
  }

  it should "rebuild the full PA56 table exactly after each serialized refresh" in {
    val random = new Random(0x504d50534cL)
    val addrMax = (BigInt(1) << 54) - 1
    simulate(new Harness) { dut =>
      dut.io.refresh.poke(false.B)
      for (iteration <- 0 until 20) {
        val previousLower = if (iteration != 0)
          Some(dut.io.cached(0).lower.peek()) else None
        for (index <- 0 until PmpConstants.MaxEntries) {
          val mode = (iteration + index) % 4
          val config = (mode << 3) | (if (index % 3 == 0) 0x80 else 0) |
            (index % 8)
          val address = if (iteration % 5 == 0 && index == 15) addrMax
            else if (iteration % 5 == 1 && index % 2 == 0) BigInt(0)
            else BigInt(54, random)
          dut.io.config(index).poke(config.U)
          dut.io.pmpAddress(index).poke(address.U)
        }

        if (iteration != 0) {
          // New external CSR values must never leak into the cached ranges
          // until the owner explicitly triggers a complete refresh.
          dut.io.ready.expect(true.B)
          dut.io.cached(0).lower.expect(previousLower.get)
          // The client gates fetch and its PTE handshake on busy/ready.
          dut.io.refresh.poke(true.B)
          dut.io.busy.expect(true.B)
          dut.io.ready.expect(false.B)
          dut.clock.step()
          dut.io.refresh.poke(false.B)
        }
        dut.io.busy.expect(true.B)
        dut.io.ready.expect(false.B)
        dut.clock.step(16)
        dut.io.busy.expect(false.B)
        dut.io.ready.expect(true.B)

        for (index <- 0 until PmpConstants.MaxEntries) {
          val got = dut.io.cached(index)
          val expected = dut.io.reference(index)
          withClue(s"iteration=$iteration entry=$index: ") {
            got.active.expect(expected.active.peek())
            got.lower.expect(expected.lower.peek())
            got.upper.expect(expected.upper.peek())
            got.read.expect(expected.read.peek())
            got.write.expect(expected.write.peek())
            got.execute.expect(expected.execute.peek())
            got.lock.expect(expected.lock.peek())
          }
        }
      }
    }
  }
}
