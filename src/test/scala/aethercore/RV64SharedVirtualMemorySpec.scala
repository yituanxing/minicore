package aethercore

import aethercore.common.PrivilegeMode
import aethercore.config.PageTableGeometry
import aethercore.core.{PageTableWalker, TranslationTlb}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RV64SharedVirtualMemorySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "shared RV64 virtual-memory machinery"

  private def initializeWalker(dut: PageTableWalker): Unit = {
    dut.io.requestValid.poke(false.B)
    dut.io.kill.poke(false.B)
    dut.io.virtualAddress.poke(0.U)
    dut.io.rootPpn.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)
    dut.io.responseReady.poke(false.B)
  }

  private def pte(
      ppn: BigInt,
      valid: Boolean = true,
      read: Boolean = false,
      write: Boolean = false,
      execute: Boolean = false,
      user: Boolean = false,
      global: Boolean = false,
      accessed: Boolean = false,
      dirty: Boolean = false
  ): BigInt = {
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

  private def vpn(geometry: PageTableGeometry, va: BigInt, level: Int): BigInt = {
    val mask = (BigInt(1) << geometry.vpnBitsPerLevel) - 1
    (va >> (geometry.pageOffsetBits + level * geometry.vpnBitsPerLevel)) & mask
  }

  private def pteAddress(
      geometry: PageTableGeometry,
      tablePpn: BigInt,
      va: BigInt,
      level: Int
  ): BigInt = {
    (tablePpn << geometry.pageOffsetBits) + vpn(geometry, va, level) * geometry.pteBytes
  }

  private def issue(
      dut: PageTableWalker,
      va: BigInt,
      rootPpn: BigInt,
      privilege: Int = PrivilegeMode.Supervisor,
      write: Boolean = false,
      execute: Boolean = false,
      sum: Boolean = false,
      mxr: Boolean = false
  ): Unit = {
    dut.io.requestReady.expect(true.B)
    dut.io.virtualAddress.poke(va.U)
    dut.io.rootPpn.poke(rootPpn.U)
    dut.io.privilege.poke(privilege.U)
    dut.io.write.poke(write.B)
    dut.io.execute.poke(execute.B)
    dut.io.sum.poke(sum.B)
    dut.io.mxr.poke(mxr.B)
    dut.io.requestValid.poke(true.B)
    dut.clock.step()
    dut.io.requestValid.poke(false.B)
  }

  private def providePte(
      dut: PageTableWalker,
      expectedAddress: BigInt,
      value: BigInt,
      fault: Boolean = false
  ): Unit = {
    dut.io.pteValid.expect(true.B)
    dut.io.pteAddress.expect(expectedAddress.U)
    dut.io.pteData.poke(value.U)
    dut.io.pteFault.poke(fault.B)
    dut.io.pteReady.poke(true.B)
    dut.clock.step()
    dut.io.pteReady.poke(false.B)
    dut.io.pteFault.poke(false.B)
  }

  private def finish(
      dut: PageTableWalker,
      physicalAddress: BigInt = 0,
      pageFault: Boolean = false,
      accessFault: Boolean = false,
      leafLevel: Int = 0,
      global: Boolean = false
  ): Unit = {
    dut.io.responseValid.expect(true.B)
    dut.io.physicalAddress.expect(physicalAddress.U)
    dut.io.pageFault.expect(pageFault.B)
    dut.io.accessFault.expect(accessFault.B)
    dut.io.leafLevel.expect(leafLevel.U)
    dut.io.global.expect(global.B)
    dut.io.responseReady.poke(true.B)
    dut.clock.step()
    dut.io.responseReady.poke(false.B)
    dut.io.requestReady.expect(true.B)
  }

  it should "walk all three Sv39 levels with 64-bit PTEs and inherit a non-leaf global mapping" in {
    val geometry = PageTableGeometry.Sv39
    simulate(new PageTableWalker(geometry)) { dut =>
      initializeWalker(dut)

      val va = BigInt("1234567024", 16)
      val rootPpn = BigInt("10000", 16)
      val level1Ppn = BigInt("11000", 16)
      val level0Ppn = BigInt("12000", 16)
      val leafPpn = BigInt("2345678", 16)

      issue(dut, va, rootPpn, privilege = PrivilegeMode.User, write = true)
      providePte(
        dut,
        pteAddress(geometry, rootPpn, va, 2),
        pte(level1Ppn, global = true)
      )
      providePte(
        dut,
        pteAddress(geometry, level1Ppn, va, 1),
        pte(level0Ppn)
      )
      providePte(
        dut,
        pteAddress(geometry, level0Ppn, va, 0),
        pte(
          leafPpn,
          read = true,
          write = true,
          user = true,
          accessed = true,
          dirty = true
        )
      )

      finish(
        dut,
        physicalAddress = (leafPpn << 12) | (va & 0xfff),
        leafLevel = 0,
        global = true
      )
    }
  }

  it should "reject reserved U A D state in an Sv39 non-leaf PTE" in {
    val geometry = PageTableGeometry.Sv39
    simulate(new PageTableWalker(geometry)) { dut =>
      initializeWalker(dut)

      val va = BigInt("1234567024", 16)
      val rootPpn = BigInt("10000", 16)
      issue(dut, va, rootPpn)
      providePte(
        dut,
        pteAddress(geometry, rootPpn, va, 2),
        pte(BigInt("11000", 16), accessed = true)
      )
      finish(dut, pageFault = true)
    }
  }

  it should "translate an aligned Sv39 1 GiB gigapage and reject a misaligned one" in {
    val geometry = PageTableGeometry.Sv39
    simulate(new PageTableWalker(geometry)) { dut =>
      initializeWalker(dut)

      val va = BigInt("1234567024", 16)
      val rootPpn = BigInt("10000", 16)
      val alignedLeafPpn = BigInt("12345", 16) << 18

      issue(dut, va, rootPpn, execute = true)
      providePte(
        dut,
        pteAddress(geometry, rootPpn, va, 2),
        pte(alignedLeafPpn, read = true, execute = true, accessed = true)
      )
      finish(
        dut,
        physicalAddress = (alignedLeafPpn << 12) | (va & ((BigInt(1) << 30) - 1)),
        leafLevel = 2
      )

      issue(dut, va, rootPpn, execute = true)
      providePte(
        dut,
        pteAddress(geometry, rootPpn, va, 2),
        pte(alignedLeafPpn | 1, read = true, execute = true, accessed = true)
      )
      finish(dut, pageFault = true)
    }
  }

  it should "reject a non-canonical Sv39 virtual address before issuing a PTE request" in {
    simulate(new PageTableWalker(PageTableGeometry.Sv39)) { dut =>
      initializeWalker(dut)
      issue(dut, BigInt(1) << 39, BigInt("10000", 16))
      dut.io.pteValid.expect(false.B)
      finish(dut, pageFault = true)
    }
  }

  it should "translate an Sv48 level-3 512 GiB terapage and enforce Sv48 canonical addresses" in {
    val geometry = PageTableGeometry.Sv48
    simulate(new PageTableWalker(geometry)) { dut =>
      initializeWalker(dut)

      val va = BigInt("123456789010", 16)
      val rootPpn = BigInt("10000", 16)
      val alignedLeafPpn = BigInt("1234", 16) << 27

      issue(dut, va, rootPpn, execute = true)
      providePte(
        dut,
        pteAddress(geometry, rootPpn, va, 3),
        pte(alignedLeafPpn, read = true, execute = true, accessed = true)
      )
      finish(
        dut,
        physicalAddress = (alignedLeafPpn << 12) | (va & ((BigInt(1) << 39) - 1)),
        leafLevel = 3
      )

      issue(dut, BigInt(1) << 48, rootPpn)
      dut.io.pteValid.expect(false.B)
      finish(dut, pageFault = true)
    }
  }

  private def initializeTlb(dut: TranslationTlb): Unit = {
    dut.io.lookupValid.poke(false.B)
    dut.io.virtualAddress.poke(0.U)
    dut.io.rootPpn.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.refillValid.poke(false.B)
    dut.io.refillVirtualAddress.poke(0.U)
    dut.io.refillPhysicalAddress.poke(0.U)
    dut.io.refillRootPpn.poke(0.U)
    dut.io.refillPrivilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.refillWrite.poke(false.B)
    dut.io.refillExecute.poke(false.B)
    dut.io.refillSum.poke(false.B)
    dut.io.refillMxr.poke(false.B)
    dut.io.refillLeafLevel.poke(0.U)
    dut.io.refillGlobal.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def refillTlb(
      dut: TranslationTlb,
      va: BigInt,
      pa: BigInt,
      rootPpn: BigInt,
      leafLevel: Int,
      execute: Boolean = false,
      global: Boolean = false
  ): Unit = {
    dut.io.refillVirtualAddress.poke(va.U)
    dut.io.refillPhysicalAddress.poke(pa.U)
    dut.io.refillRootPpn.poke(rootPpn.U)
    dut.io.refillExecute.poke(execute.B)
    dut.io.refillLeafLevel.poke(leafLevel.U)
    dut.io.refillGlobal.poke(global.B)
    dut.io.refillValid.poke(true.B)
    dut.clock.step()
    dut.io.refillValid.poke(false.B)
  }

  private def lookupTlb(
      dut: TranslationTlb,
      va: BigInt,
      rootPpn: BigInt,
      execute: Boolean = false
  ): Unit = {
    dut.io.virtualAddress.poke(va.U)
    dut.io.rootPpn.poke(rootPpn.U)
    dut.io.execute.poke(execute.B)
    dut.io.lookupValid.poke(true.B)
  }

  it should "store only in-range PA32 translations in the compact Sv39 TLB" in {
    val geometry = PageTableGeometry.Sv39
    simulate(new TranslationTlb(geometry, entries = 4, implementedPaddrBits = 32)) { dut =>
      initializeTlb(dut)
      val root = BigInt("10000", 16)
      val lowVa = BigInt("0000001234500456", 16)
      val lowPaBase = BigInt("81234000", 16)

      refillTlb(dut, lowVa, lowPaBase, root, leafLevel = 0)
      lookupTlb(dut, lowVa, root)
      dut.io.hit.expect(true.B)
      dut.io.physicalAddress.expect((lowPaBase | (lowVa & 0xfff)).U)

      dut.io.lookupValid.poke(false.B)
      val highVa = lowVa + BigInt("2000", 16)
      val highPa = (BigInt(1) << 32) | BigInt("81236000", 16)
      refillTlb(dut, highVa, highPa, root, leafLevel = 0)
      lookupTlb(dut, highVa, root)

      // High architectural PAs must stay misses rather than truncating to PA32
      // and aliasing an otherwise valid low physical translation.
      dut.io.hit.expect(false.B)
    }
  }

  it should "reconstruct Sv39 gigapage and Sv48 terapage offsets from one shared TLB" in {
    val sv39 = PageTableGeometry.Sv39
    simulate(new TranslationTlb(sv39, entries = 4)) { dut =>
      initializeTlb(dut)
      val root = BigInt("10000", 16)
      val va = BigInt("1234001020", 16)
      val pa = BigInt("12345", 16) << 30

      refillTlb(dut, va, pa, root, leafLevel = 2, execute = true, global = true)
      val secondVa = (va & ~((BigInt(1) << 30) - 1)) | BigInt("2abcde", 16)
      lookupTlb(dut, secondVa, root, execute = true)
      dut.io.hit.expect(true.B)
      dut.io.physicalAddress.expect((pa | (secondVa & ((BigInt(1) << 30) - 1))).U)
      dut.io.leafLevel.expect(2.U)
      dut.io.global.expect(true.B)

      lookupTlb(dut, secondVa + (BigInt(1) << 30), root, execute = true)
      dut.io.hit.expect(false.B)
    }

    val sv48 = PageTableGeometry.Sv48
    simulate(new TranslationTlb(sv48, entries = 4)) { dut =>
      initializeTlb(dut)
      val root = BigInt("20000", 16)
      val va = BigInt("123456789010", 16)
      val pa = BigInt("1234", 16) << 39

      refillTlb(dut, va, pa, root, leafLevel = 3, execute = true)
      val secondVa = (va & ~((BigInt(1) << 39) - 1)) | BigInt("12345678", 16)
      lookupTlb(dut, secondVa, root, execute = true)
      dut.io.hit.expect(true.B)
      dut.io.physicalAddress.expect((pa | (secondVa & ((BigInt(1) << 39) - 1))).U)
      dut.io.leafLevel.expect(3.U)
    }
  }
}
