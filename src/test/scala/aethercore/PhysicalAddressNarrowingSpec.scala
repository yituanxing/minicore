package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.PhysicalAddressNarrowing

class PhysicalAddressNarrowingHarness(sourceBits: Int, paddrBits: Int) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(sourceBits.W))
    val physicalAddress = Output(UInt(paddrBits.W))
    val outOfRange = Output(Bool())
  })

  val (physicalAddress, outOfRange) = PhysicalAddressNarrowing(io.address, paddrBits)
  io.physicalAddress := physicalAddress
  io.outOfRange := outOfRange
}

class PhysicalAddressNarrowingSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "PhysicalAddressNarrowing"

  it should "preserve an in-range RV64 address on a PA56 platform" in {
    simulate(new PhysicalAddressNarrowingHarness(sourceBits = 64, paddrBits = 56)) { dut =>
      dut.io.address.poke(BigInt("00abcdef12345678", 16).U)
      dut.io.physicalAddress.expect(BigInt("abcdef12345678", 16).U)
      dut.io.outOfRange.expect(false.B)
    }
  }

  it should "flag any discarded RV64 high bit instead of silently aliasing PA56" in {
    simulate(new PhysicalAddressNarrowingHarness(sourceBits = 64, paddrBits = 56)) { dut =>
      dut.io.address.poke(BigInt("0100000080002000", 16).U)
      dut.io.physicalAddress.expect(BigInt("00000080002000", 16).U)
      dut.io.outOfRange.expect(true.B)
    }
  }

  it should "zero-extend RV32 into a wider PA34 domain without a range fault" in {
    simulate(new PhysicalAddressNarrowingHarness(sourceBits = 32, paddrBits = 34)) { dut =>
      dut.io.address.poke(BigInt("f0002000", 16).U)
      dut.io.physicalAddress.expect(BigInt("f0002000", 16).U)
      dut.io.outOfRange.expect(false.B)
    }
  }

  it should "preserve equal architectural and physical widths" in {
    simulate(new PhysicalAddressNarrowingHarness(sourceBits = 64, paddrBits = 64)) { dut =>
      dut.io.address.poke(BigInt("fedcba9876543210", 16).U)
      dut.io.physicalAddress.expect(BigInt("fedcba9876543210", 16).U)
      dut.io.outOfRange.expect(false.B)
    }
  }
}
