package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.{PmpAccessChecker, PmpDecodedEntry}

class PmpFullBeatExtensionGuardSpec
    extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "PmpAccessChecker widened 8-byte proof"

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
      upper: BigInt,
      execute: Boolean = true
  ): Unit = {
    range.active.poke(true.B)
    range.lower.poke(lower.U)
    range.upper.poke(upper.U)
    range.read.poke(true.B)
    range.write.poke(false.B)
    range.execute.poke(execute.B)
    range.lock.poke(false.B)
  }

  it should "reuse PMP priority while separately proving the widened 8-byte access" in {
    simulate(new PmpAccessChecker(entries = 4, paddrBits = 32)) { dut =>
      for (i <- 0 until 4) clearRange(dut.io.ranges(i))
      dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
      dut.io.address.poke("h80001000".U)
      dut.io.bytes.poke(4.U)
      dut.io.write.poke(false.B)
      dut.io.execute.poke(true.B)

      setRange(dut.io.ranges(1), BigInt("80000000", 16), BigInt("80002000", 16))
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(true.B)

      // Current 4B remains legal while an owner ending at +4 cannot cover 8B.
      setRange(dut.io.ranges(1), BigInt("80001000", 16), BigInt("80001004", 16))
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(false.B)

      // A higher-priority entry beginning only in the added upper half changes
      // ownership for the 8B access and forces the architectural straddle deny.
      setRange(dut.io.ranges(1), BigInt("80000000", 16), BigInt("80002000", 16))
      setRange(
        dut.io.ranges(0),
        BigInt("80001004", 16),
        BigInt("80001008", 16),
        execute = false
      )
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(false.B)

      // A lower-priority entry cannot supersede the already matched broad owner.
      clearRange(dut.io.ranges(0))
      setRange(
        dut.io.ranges(2),
        BigInt("80001004", 16),
        BigInt("80001008", 16),
        execute = false
      )
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(true.B)

      // Unmatched S-mode fails closed for both widths.
      for (i <- 0 until 4) clearRange(dut.io.ranges(i))
      dut.io.allow.expect(false.B)
      dut.io.allowWidened8.expect(false.B)

      // Unmatched M-mode bypasses both widths, but a range that exists only in
      // the added half makes the 8B access straddle while the 4B remains free.
      dut.io.privilege.poke(PrivilegeMode.Machine.U)
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(true.B)
      setRange(dut.io.ranges(0), BigInt("80001004", 16), BigInt("80001008", 16))
      dut.io.allow.expect(true.B)
      dut.io.allowWidened8.expect(false.B)
    }
  }
}
