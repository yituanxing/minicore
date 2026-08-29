package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.{PmpAddressMode, PmpChecker, PmpConstants}

class PmpCheckerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "PmpChecker"

  private def config(
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      mode: Int = PmpAddressMode.Off,
      lock: Boolean = false
  ): Int = {
    (if (read) 1 << PmpConstants.ConfigRead else 0) |
      (if (write) 1 << PmpConstants.ConfigWrite else 0) |
      (if (execute) 1 << PmpConstants.ConfigExecute else 0) |
      (mode << PmpConstants.ConfigAddressLow) |
      (if (lock) 1 << PmpConstants.ConfigLock else 0)
  }

  private def initialize(dut: PmpChecker): Unit = {
    dut.io.privilege.poke(PrivilegeMode.User.U)
    dut.io.address.poke(0.U)
    dut.io.bytes.poke(4.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    for (entry <- 0 until PmpConstants.MaxEntries) {
      dut.io.config(entry).poke(0.U)
      dut.io.pmpAddress(entry).poke(0.U)
    }
  }

  private def check(
      dut: PmpChecker,
      address: BigInt,
      bytes: Int,
      write: Boolean = false,
      execute: Boolean = false,
      allow: Boolean,
      matched: Boolean = true,
      entry: Int = 0
  ): Unit = {
    dut.io.address.poke(address.U)
    dut.io.bytes.poke(bytes.U)
    dut.io.write.poke(write.B)
    dut.io.execute.poke(execute.B)
    dut.io.allow.expect(allow.B)
    dut.io.matched.expect(matched.B)
    if (matched) dut.io.matchedEntry.expect(entry.U)
  }

  it should "isolate kernel, user text, user data and MMIO with prioritized TOR entries" in {
    simulate(new PmpChecker(32)) { dut =>
      initialize(dut)

      // [0, 0x80001000): no U permission, including kernel and all low MMIO.
      dut.io.pmpAddress(0).poke((BigInt("80001000", 16) >> 2).U)
      dut.io.config(0).poke(config(mode = PmpAddressMode.Tor).U)

      // [0x80001000, 0x80002000): user read/execute text.
      dut.io.pmpAddress(1).poke((BigInt("80002000", 16) >> 2).U)
      dut.io.config(1).poke(
        config(read = true, execute = true, mode = PmpAddressMode.Tor).U
      )

      // [0x80002000, 0x80003000): user read/write data and stack.
      dut.io.pmpAddress(2).poke((BigInt("80003000", 16) >> 2).U)
      dut.io.config(2).poke(
        config(read = true, write = true, mode = PmpAddressMode.Tor).U
      )

      check(dut, BigInt("80000000", 16), 4, execute = true, allow = false, entry = 0)
      check(dut, BigInt("10000000", 16), 1, write = true, allow = false, entry = 0)
      check(dut, BigInt("80001000", 16), 4, execute = true, allow = true, entry = 1)
      check(dut, BigInt("80001000", 16), 4, write = true, allow = false, entry = 1)
      check(dut, BigInt("80002000", 16), 4, write = true, allow = true, entry = 2)
      check(dut, BigInt("80002000", 16), 4, execute = true, allow = false, entry = 2)

      // The first overlapping entry owns a straddling access and rejects it.
      check(dut, BigInt("80001ffe", 16), 4, allow = false, entry = 1)

      // No TOR entry covers addresses above the user data page, so U defaults deny.
      check(
        dut,
        BigInt("90000000", 16),
        4,
        allow = false,
        matched = false
      )
    }
  }

  it should "bypass unlocked entries in Machine mode but enforce locked entries" in {
    simulate(new PmpChecker(32)) { dut =>
      initialize(dut)
      dut.io.privilege.poke(PrivilegeMode.Machine.U)
      dut.io.pmpAddress(0).poke((BigInt("80001000", 16) >> 2).U)
      dut.io.config(0).poke(config(mode = PmpAddressMode.Tor).U)

      check(dut, BigInt("10000000", 16), 1, write = true, allow = true, entry = 0)

      dut.io.config(0).poke(config(mode = PmpAddressMode.Tor, lock = true).U)
      check(dut, BigInt("10000000", 16), 1, write = true, allow = false, entry = 0)

      check(
        dut,
        BigInt("90000000", 16),
        4,
        execute = true,
        allow = true,
        matched = false
      )
    }
  }

  it should "decode NA4 and NAPOT regions" in {
    simulate(new PmpChecker(32)) { dut =>
      initialize(dut)

      dut.io.pmpAddress(0).poke((BigInt("00001000", 16) >> 2).U)
      dut.io.config(0).poke(
        config(read = true, mode = PmpAddressMode.Na4).U
      )
      check(dut, BigInt("00001000", 16), 4, allow = true, entry = 0)
      check(
        dut,
        BigInt("00001004", 16),
        1,
        allow = false,
        matched = false
      )

      // 16-byte NAPOT: pmpaddr = base/4 | (size/8 - 1).
      dut.io.pmpAddress(1).poke(((BigInt("00002000", 16) >> 2) | 1).U)
      dut.io.config(1).poke(
        config(read = true, mode = PmpAddressMode.Napot).U
      )
      check(dut, BigInt("00002000", 16), 1, allow = true, entry = 1)
      check(dut, BigInt("0000200f", 16), 1, allow = true, entry = 1)
      check(
        dut,
        BigInt("00002010", 16),
        1,
        allow = false,
        matched = false
      )
    }
  }

  it should "preserve every NAPOT encoding boundary in a small physical domain" in {
    simulate(new PmpChecker(32, paddrBits = 8)) { dut =>
      initialize(dut)

      val encodedBits = 6
      val encodedMask = (BigInt(1) << encodedBits) - 1
      val physicalLimit = BigInt(1) << 8

      for (encoded <- BigInt(0) to encodedMask) {
        for (entry <- 0 until PmpConstants.MaxEntries) {
          dut.io.config(entry).poke(0.U)
          dut.io.pmpAddress(entry).poke(0.U)
        }

        dut.io.pmpAddress(0).poke(encoded.U)
        dut.io.config(0).poke(
          config(read = true, mode = PmpAddressMode.Napot).U
        )

        val (base, upper) =
          if (encoded == encodedMask) {
            (BigInt(0), physicalLimit)
          } else {
            var trailingOnes = 0
            while (((encoded >> trailingOnes) & 1) == 1) {
              trailingOnes += 1
            }
            val lowMask = (BigInt(1) << trailingOnes) - 1
            val base = (encoded & (encodedMask ^ lowMask)) << 2
            val size = BigInt(1) << (trailingOnes + 3)
            (base, base + size)
          }

        try {
          check(dut, base, 1, allow = true, entry = 0)
        } catch {
          case e: Throwable =>
            fail(s"NAPOT encoded=$encoded base=$base upper=$upper lower-bound failed", e)
        }
        try {
          check(dut, upper - 1, 1, allow = true, entry = 0)
        } catch {
          case e: Throwable =>
            fail(s"NAPOT encoded=$encoded base=$base upper=$upper upper-bound failed", e)
        }

        if (base > 0) {
          try {
            check(dut, base - 1, 1, allow = false, matched = false)
          } catch {
            case e: Throwable =>
              fail(s"NAPOT encoded=$encoded base=$base upper=$upper below-range failed", e)
          }
        }
        if (upper < physicalLimit) {
          try {
            check(dut, upper, 1, allow = false, matched = false)
          } catch {
            case e: Throwable =>
              fail(s"NAPOT encoded=$encoded base=$base upper=$upper above-range failed", e)
          }
        }
      }
    }
  }

  it should "keep lowest-numbered match priority across the full PMP16 bank" in {
    simulate(new PmpChecker(32)) { dut =>
      initialize(dut)

      val encoded = (BigInt("00003000", 16) >> 2) | 1
      dut.io.pmpAddress(4).poke(encoded.U)
      dut.io.pmpAddress(12).poke(encoded.U)
      dut.io.config(4).poke(
        config(read = true, mode = PmpAddressMode.Napot).U
      )
      dut.io.config(12).poke(
        config(mode = PmpAddressMode.Napot).U
      )

      check(dut, BigInt("00003004", 16), 4, allow = true, entry = 4)

      dut.io.config(4).poke(0.U)
      check(dut, BigInt("00003004", 16), 4, allow = false, entry = 12)
    }
  }

  it should "match RV32 PMP regions in a 34-bit physical address domain" in {
    simulate(new PmpChecker(32, paddrBits = 34)) { dut =>
      initialize(dut)

      val highBase = BigInt("100000000", 16)
      val highTop = BigInt("100001000", 16)

      // An OFF entry may still provide the lower TOR bound for the next entry.
      dut.io.pmpAddress(0).poke((highBase >> 2).U)
      dut.io.pmpAddress(1).poke((highTop >> 2).U)
      dut.io.config(1).poke(
        config(read = true, mode = PmpAddressMode.Tor).U
      )

      check(dut, highBase, 4, allow = true, entry = 1)
      check(dut, highTop - 4, 4, allow = true, entry = 1)
      check(dut, highTop - 2, 4, allow = false, entry = 1)
      check(dut, BigInt("fffffff0", 16), 4, allow = false, matched = false)

      // 31 trailing one bits encode a 2^34-byte NAPOT range exactly. This is
      // the portable way to cover the complete RV32 PA34 physical domain.
      for (entry <- 0 until PmpConstants.MaxEntries) {
        dut.io.config(entry).poke(0.U)
        dut.io.pmpAddress(entry).poke(0.U)
      }
      dut.io.pmpAddress(0).poke(BigInt("7fffffff", 16).U)
      dut.io.config(0).poke(
        config(read = true, mode = PmpAddressMode.Napot).U
      )
      check(dut, BigInt("3ffffffff", 16), 1, allow = true, entry = 0)
    }
  }
}
