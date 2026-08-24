package aethercore

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import aethercore.core.TranslationUnit
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Translation-side safety proof for conservative pre-head Loads. */
trait V2P8PreHeadTranslationChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      accessed: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

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
    dut.io.allowWalk.get.poke(false.B)
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)
    dut.io.responseReady.poke(false.B)
  }

  behavior of "AetherCore v2 pre-head translation walk control"

  it should "hold a cold miss without PTW traffic and release it when walking becomes allowed" in {
    simulate(new TranslationUnit(PageTableGeometry.Sv39, tlbEntries = 4, withWalkControl = true)) { dut =>
      initialize(dut)
      val va = BigInt("0000002040302024", 16)
      val root = BigInt("20000", 16)
      val vpn2 = (va >> 30) & 0x1ff
      val rootPte = (root << 12) + (vpn2 << 3)

      dut.io.virtualAddress.poke(va.U)
      dut.io.satpRootPpn.poke(root.U)
      dut.io.requestValid.poke(true.B)
      dut.io.responseReady.poke(true.B)

      // Cold miss is purely observational while a speculative walk is denied.
      dut.io.requestReady.expect(false.B)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(false.B)
      dut.clock.step(2)
      dut.io.requestReady.expect(false.B)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(false.B)

      // Exact-head permission releases the same held request into the existing walker.
      dut.io.allowWalk.get.poke(true.B)
      dut.io.requestReady.expect(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)
      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(rootPte.U)
    }
  }

  it should "allow a cached hit even while new walks are denied" in {
    simulate(new TranslationUnit(PageTableGeometry.Sv39, tlbEntries = 4, withWalkControl = true)) { dut =>
      initialize(dut)
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

      dut.io.allowWalk.get.poke(true.B)
      dut.io.virtualAddress.poke(va.U)
      dut.io.satpRootPpn.poke(root.U)
      dut.io.requestValid.poke(true.B)
      dut.io.responseReady.poke(true.B)
      dut.io.requestReady.expect(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)

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
      dut.io.physicalAddress.expect(pa.U)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)

      // Same translated page: walk permission is irrelevant to a cached hit.
      dut.io.allowWalk.get.poke(false.B)
      dut.io.virtualAddress.poke(secondVa.U)
      dut.io.satpRootPpn.poke(root.U)
      dut.io.requestValid.poke(true.B)
      dut.io.responseReady.poke(true.B)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(true.B)
      dut.io.requestReady.expect(true.B)
      dut.io.physicalAddress.expect((pa + 0x100).U)
    }
  }
}
