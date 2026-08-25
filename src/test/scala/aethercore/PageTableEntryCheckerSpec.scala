package aethercore

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import aethercore.core.PageTableEntryChecker
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class PageTableEntryCheckerSpec extends AnyFlatSpec with ChiselSim {
  behavior of "PageTableEntryChecker"

  private def pte(
      ppn: BigInt,
      valid: Boolean = true,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      user: Boolean = false,
      global: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false,
      reservedHigh: BigInt = 0
  ): BigInt = {
    (reservedHigh << 54) |
      (ppn << 10) |
      (if (valid) BigInt(1) else BigInt(0)) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (user) BigInt(1) << 4 else BigInt(0)) |
      (if (global) BigInt(1) << 5 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))
  }

  private def defaults(dut: PageTableEntryChecker): Unit = {
    dut.io.pte.poke(0.U)
    dut.io.level.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
  }

  it should "own Sv32 encoding, SUM, MXR, Svade and superpage policy" in {
    simulate(new PageTableEntryChecker(PageTableGeometry.Sv32)) { dut =>
      defaults(dut)

      val alignedMegapagePpn = BigInt("140", 16) << 10
      dut.io.level.poke(1.U)
      dut.io.pte.poke(pte(alignedMegapagePpn, read = true, accessed = true).U)
      dut.io.invalidEncoding.expect(false.B)
      dut.io.leaf.expect(true.B)
      dut.io.leafAccessFault.expect(false.B)

      dut.io.pte.poke(pte(alignedMegapagePpn | 1, read = true, accessed = true).U)
      dut.io.leafAccessFault.expect(true.B)

      dut.io.level.poke(0.U)
      dut.io.pte.poke(pte(1, write = true, accessed = true, dirty = true).U)
      dut.io.invalidEncoding.expect(true.B)

      dut.io.pte.poke(pte(1, user = true).U)
      dut.io.leaf.expect(false.B)
      dut.io.invalidEncoding.expect(true.B)

      dut.io.pte.poke(pte(1, read = true, user = true, accessed = true).U)
      dut.io.sum.poke(false.B)
      dut.io.leafAccessFault.expect(true.B)
      dut.io.sum.poke(true.B)
      dut.io.leafAccessFault.expect(false.B)

      dut.io.execute.poke(true.B)
      dut.io.pte.poke(pte(1, execute = true, user = true, accessed = true).U)
      dut.io.leafAccessFault.expect(true.B)

      dut.io.execute.poke(false.B)
      dut.io.sum.poke(false.B)
      dut.io.privilege.poke(PrivilegeMode.User.U)
      dut.io.pte.poke(pte(1, execute = true, user = true, accessed = true).U)
      dut.io.mxr.poke(false.B)
      dut.io.leafAccessFault.expect(true.B)
      dut.io.mxr.poke(true.B)
      dut.io.leafAccessFault.expect(false.B)

      dut.io.mxr.poke(false.B)
      dut.io.write.poke(true.B)
      dut.io.pte.poke(pte(1, read = true, write = true, user = true, accessed = true, dirty = false).U)
      dut.io.leafAccessFault.expect(true.B)
      dut.io.pte.poke(pte(1, read = true, write = true, user = true, accessed = true, dirty = true).U)
      dut.io.leafAccessFault.expect(false.B)
    }
  }

  it should "apply the same geometry-driven policy to Sv39 PTEs" in {
    simulate(new PageTableEntryChecker(PageTableGeometry.Sv39)) { dut =>
      defaults(dut)

      val level2AlignedPpn = BigInt(3) << 18
      dut.io.level.poke(2.U)
      dut.io.pte.poke(pte(level2AlignedPpn, read = true, accessed = true).U)
      dut.io.invalidEncoding.expect(false.B)
      dut.io.leafAccessFault.expect(false.B)

      dut.io.pte.poke(pte(level2AlignedPpn | 1, read = true, accessed = true).U)
      dut.io.leafAccessFault.expect(true.B)

      dut.io.pte.poke(pte(level2AlignedPpn, read = true, accessed = true, reservedHigh = 1).U)
      dut.io.invalidEncoding.expect(true.B)
    }
  }
}
