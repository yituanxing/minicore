package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{PmpAddressMode, PmpRangeDecoder}

/** Exact combinational range reference for the PMP active-area optimization. */
class PmpRangeDecoderActiveSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "PmpRangeDecoder active and geometry"

  private def config(mode: Int): Int = mode << 3

  private def napotBounds(encoded: BigInt, addressBits: Int, paddrBits: Int): (BigInt, BigInt) = {
    val allOnes = (BigInt(1) << addressBits) - 1
    if (encoded == allOnes) {
      (BigInt(0), BigInt(1) << math.min(paddrBits, addressBits + 3))
    } else {
      var trailingOnes = 0
      while (((encoded >> trailingOnes) & BigInt(1)) == BigInt(1)) {
        trailingOnes += 1
      }
      val lowMask = (BigInt(1) << trailingOnes) - 1
      val lower = (encoded & ~lowMask) << 2
      (lower, lower + (BigInt(1) << (trailingOnes + 3)))
    }
  }

  it should "match all PA8 TOR orderings, NA4 endpoints and NAPOT encodings" in {
    simulate(new PmpRangeDecoder(32, entries = 2, paddrBits = 8)) { dut =>
      dut.io.config(0).poke(config(PmpAddressMode.Off).U)
      dut.io.config(1).poke(config(PmpAddressMode.Tor).U)

      for (previous <- 0 until 64; current <- 0 until 64) {
        dut.io.pmpAddress(0).poke(previous.U)
        dut.io.pmpAddress(1).poke(current.U)
        dut.io.ranges(0).active.expect(false.B)
        dut.io.ranges(1).active.expect((current > previous).B)
        dut.io.ranges(1).lower.expect((previous * 4).U)
        dut.io.ranges(1).upper.expect((current * 4).U)
      }

      dut.io.config(0).poke(config(PmpAddressMode.Tor).U)
      for (current <- 0 until 64) {
        dut.io.pmpAddress(0).poke(current.U)
        dut.io.ranges(0).active.expect((current != 0).B)
      }

      dut.io.config(0).poke(config(PmpAddressMode.Off).U)
      dut.io.config(1).poke(config(PmpAddressMode.Na4).U)
      for (current <- 0 until 64) {
        dut.io.pmpAddress(1).poke(current.U)
        dut.io.ranges(1).active.expect(true.B)
        dut.io.ranges(1).lower.expect((current * 4).U)
        dut.io.ranges(1).upper.expect((current * 4 + 4).U)
      }

      dut.io.config(1).poke(config(PmpAddressMode.Napot).U)
      for (current <- 0 until 64) {
        val (lower, upper) = napotBounds(BigInt(current), 6, 8)
        dut.io.pmpAddress(1).poke(current.U)
        dut.io.ranges(1).active.expect(true.B)
        dut.io.ranges(1).lower.expect(lower.U)
        dut.io.ranges(1).upper.expect(upper.U)
      }
    }
  }

  it should "retain RV64 PA56 NAPOT top-of-domain and TOR ordering" in {
    simulate(new PmpRangeDecoder(64, entries = 2, paddrBits = 56)) { dut =>
      val encodedBits = 54
      val allOnes = (BigInt(1) << encodedBits) - 1
      val cases = Seq(
        BigInt(0), BigInt(1), BigInt(2), BigInt(3),
        (BigInt(1) << 53) - 1, BigInt(1) << 53,
        (BigInt(1) << 53) + 1, allOnes - 1, allOnes
      )
      dut.io.config(0).poke(config(PmpAddressMode.Off).U)
      dut.io.config(1).poke(config(PmpAddressMode.Napot).U)
      dut.io.pmpAddress(0).poke(0.U)
      for (encoded <- cases) {
        val (lower, upper) = napotBounds(encoded, encodedBits, 56)
        dut.io.pmpAddress(1).poke(encoded.U)
        dut.io.ranges(1).active.expect(true.B)
        dut.io.ranges(1).lower.expect(lower.U)
        dut.io.ranges(1).upper.expect(upper.U)
      }

      dut.io.config(1).poke(config(PmpAddressMode.Na4).U)
      dut.io.pmpAddress(1).poke(allOnes.U)
      dut.io.ranges(1).active.expect(true.B)
      dut.io.ranges(1).lower.expect((allOnes << 2).U)
      dut.io.ranges(1).upper.expect(((allOnes << 2) + 4).U)

      dut.io.config(1).poke(config(PmpAddressMode.Tor).U)
      for ((previous, current) <- Seq(
        (BigInt(0), BigInt(0)), (allOnes, allOnes),
        (allOnes, BigInt(0)), (allOnes - 1, allOnes)
      )) {
        dut.io.pmpAddress(0).poke(previous.U)
        dut.io.pmpAddress(1).poke(current.U)
        dut.io.ranges(1).active.expect((current > previous).B)
      }
    }
  }
}
