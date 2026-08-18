package aethercore

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import aethercore.core.TranslationUnit
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TranslationUnitSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "TranslationUnit"

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (write) BigInt(1) << 2 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0)) |
      (if (dirty) BigInt(1) << 7 else BigInt(0))

  private def initialize(dut: TranslationUnit): Unit = {
    dut.io.requestValid.poke(false.B)
    dut.io.kill.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.virtualAddress.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    dut.io.satpTranslationEnabled.poke(true.B)
    dut.io.satpRootPpn.poke(0.U)
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)
    dut.io.responseReady.poke(false.B)
  }

  private def request(dut: TranslationUnit, va: BigInt, root: BigInt): Unit = {
    dut.io.virtualAddress.poke(va.U)
    dut.io.satpRootPpn.poke(root.U)
    dut.io.requestValid.poke(true.B)
    dut.io.requestReady.expect(true.B)
    dut.clock.step()
    dut.io.requestValid.poke(false.B)
  }

  it should "compose a complete Sv39 walk, refill the TLB and hit the same page" in {
    simulate(new TranslationUnit(PageTableGeometry.Sv39, tlbEntries = 4)) { dut =>
      initialize(dut)
      // Keep bit 38 clear so the positive Sv39 VA is canonical.
      val va = BigInt("0000002040302024", 16)
      val secondVa = va + 0x100
      val root = BigInt("20000", 16)
      val level1 = BigInt("21000", 16)
      val level0 = BigInt("22000", 16)
      val leaf = BigInt("12345", 16)
      val vpn2 = (va >> 30) & 0x1ff
      val vpn1 = (va >> 21) & 0x1ff
      val vpn0 = (va >> 12) & 0x1ff
      val rootPte = (root << 12) + (vpn2 << 3)
      val level1Pte = (level1 << 12) + (vpn1 << 3)
      val leafPte = (level0 << 12) + (vpn0 << 3)
      val pa = (leaf << 12) | (va & 0xfff)

      request(dut, va, root)
      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(rootPte.U)
      dut.io.pteData.poke(pte(level1).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(level1Pte.U)
      dut.io.pteData.poke(pte(level0).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(leafPte.U)
      dut.io.pteData.poke(pte(leaf, read = true, accessed = true).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.responseValid.expect(true.B)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.io.physicalAddress.expect(pa.U)
      dut.io.responseReady.poke(true.B)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)

      request(dut, secondVa, root)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(true.B)
      dut.io.physicalAddress.expect((pa + 0x100).U)
    }
  }

  it should "compose an Sv48 level-3 terapage without a mode-specific state machine" in {
    simulate(new TranslationUnit(PageTableGeometry.Sv48, tlbEntries = 4)) { dut =>
      initialize(dut)
      val va = BigInt("0000123456789000", 16)
      val root = BigInt("20000", 16)
      val leaf = BigInt("10000000", 16)
      val vpn3 = (va >> 39) & 0x1ff
      val rootPte = (root << 12) + (vpn3 << 3)
      val terapageMask = (BigInt(1) << 39) - 1
      val pa = ((leaf << 12) & ~terapageMask) | (va & terapageMask)

      request(dut, va, root)
      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(rootPte.U)
      dut.io.pteData.poke(pte(leaf, read = true, accessed = true).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.responseValid.expect(true.B)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.io.leafLevel.expect(3.U)
      dut.io.physicalAddress.expect(pa.U)
    }
  }

  it should "fault a bare RV64 address above PA56 instead of aliasing its low bits" in {
    simulate(new TranslationUnit(PageTableGeometry.Sv48, tlbEntries = 4)) { dut =>
      initialize(dut)
      val address = BigInt("0100000080002000", 16)
      dut.io.satpTranslationEnabled.poke(false.B)

      request(dut, address, 0)
      dut.io.responseValid.expect(true.B)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(true.B)
      dut.io.physicalAddress.expect(BigInt("00000080002000", 16).U)
    }
  }
}
