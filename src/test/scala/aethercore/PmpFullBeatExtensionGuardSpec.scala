package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.{PmpDecodedEntry, PmpFullBeatExtensionGuard}

class PmpFullBeatExtensionGuardSpec
    extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "PmpFullBeatExtensionGuard"

  private def clearRange(range: PmpDecodedEntry): Unit = {
    range.active.poke(false.B)
    range.lower.poke(0.U)
    range.upper.poke(0.U)
    range.read.poke(false.B)
    range.write.poke(false.B)
    range.execute.poke(false.B)
    range.lock.poke(false.B)
  }

  private def setRange(
      range: PmpDecodedEntry,
      lower: BigInt,
      upper: BigInt
  ): Unit = {
    range.active.poke(true.B)
    range.lower.poke(lower.U)
    range.upper.poke(upper.U)
    range.read.poke(true.B)
    range.write.poke(false.B)
    range.execute.poke(true.B)
    range.lock.poke(false.B)
  }

  it should "extend only when the current PMP owner remains valid for bytes +4..+7" in {
    simulate(new PmpFullBeatExtensionGuard(entries = 4, paddrBits = 32)) { dut =>
      for (i <- 0 until 4) clearRange(dut.io.ranges(i))
      dut.io.address.poke("h80001000".U)
      dut.io.currentAllowed.poke(true.B)

      // Unmatched/allowed (Machine bypass) may extend only while the added half
      // stays unmatched.
      dut.io.currentMatched.poke(false.B)
      dut.io.currentMatchedEntry.poke(0.U)
      dut.io.allow.expect(true.B)

      setRange(dut.io.ranges(0), BigInt("80001004", 16), BigInt("80001008", 16))
      dut.io.allow.expect(false.B)

      // A matched owner covering the full beat permits widening.
      clearRange(dut.io.ranges(0))
      setRange(dut.io.ranges(1), BigInt("80000000", 16), BigInt("80002000", 16))
      dut.io.currentMatched.poke(true.B)
      dut.io.currentMatchedEntry.poke(1.U)
      dut.io.allow.expect(true.B)

      // If the current owner ends after only the architectural lower 4 bytes,
      // the widened access would straddle and must be rejected.
      setRange(dut.io.ranges(1), BigInt("80001000", 16), BigInt("80001004", 16))
      dut.io.allow.expect(false.B)

      // Restore the owner, then introduce a higher-priority entry that begins
      // only in the added upper half. A true 8-byte PMP check would select that
      // entry first and reject the straddle, so the cheap guard must also deny.
      setRange(dut.io.ranges(1), BigInt("80000000", 16), BigInt("80002000", 16))
      setRange(dut.io.ranges(0), BigInt("80001004", 16), BigInt("80001008", 16))
      dut.io.allow.expect(false.B)

      // A lower-priority upper-half range cannot supersede the already-matched
      // owner and therefore does not invalidate the proof.
      clearRange(dut.io.ranges(0))
      setRange(dut.io.ranges(2), BigInt("80001004", 16), BigInt("80001008", 16))
      dut.io.allow.expect(true.B)

      // The guard is deliberately scoped to 8-byte-aligned lower-half fetches.
      dut.io.address.poke("h80001004".U)
      dut.io.allow.expect(false.B)
    }
  }
}
