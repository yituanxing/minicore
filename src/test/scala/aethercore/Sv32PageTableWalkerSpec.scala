package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.{Sv32PageTableWalker, Sv32TranslationUnit}

class Sv32PageTableWalkerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32PageTableWalker"

  private def initialize(dut: Sv32PageTableWalker): Unit = {
    dut.io.requestValid.poke(false.B)
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

  private def initializeTranslation(dut: Sv32TranslationUnit): Unit = {
    dut.io.requestValid.poke(false.B)
    dut.io.virtualAddress.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.write.poke(false.B)
    dut.io.execute.poke(false.B)
    dut.io.satpTranslationEnabled.poke(false.B)
    dut.io.satpRootPpn.poke(0.U)
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

  private def issue(
      dut: Sv32PageTableWalker,
      va: BigInt,
      rootPpn: BigInt,
      privilege: Int,
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
      dut: Sv32PageTableWalker,
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
      dut: Sv32PageTableWalker,
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

  it should "walk two levels and translate a 4 KiB user page" in {
    simulate(new Sv32PageTableWalker) { dut =>
      initialize(dut)

      val va = BigInt("40403024", 16)
      val rootPpn = BigInt("20000", 16)
      val nextPpn = BigInt("21000", 16)
      val leafPpn = BigInt("30001", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(nextPpn)
      )
      providePte(
        dut,
        (nextPpn << 12) + (vpn0 << 2),
        pte(
          leafPpn,
          read = true,
          write = true,
          user = true,
          global = true,
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

  it should "translate an aligned 4 MiB megapage and reject a misaligned one" in {
    simulate(new Sv32PageTableWalker) { dut =>
      initialize(dut)

      val va = BigInt("81234088", 16)
      val rootPpn = BigInt("100", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff
      val ppn1 = BigInt("123", 16)
      val alignedLeafPpn = ppn1 << 10

      issue(dut, va, rootPpn, PrivilegeMode.Supervisor, execute = true)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(alignedLeafPpn, read = true, execute = true, accessed = true)
      )
      finish(
        dut,
        physicalAddress = (ppn1 << 22) | (vpn0 << 12) | (va & 0xfff),
        leafLevel = 1
      )

      issue(dut, va, rootPpn, PrivilegeMode.Supervisor, execute = true)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(alignedLeafPpn | 1, read = true, execute = true, accessed = true)
      )
      finish(dut, pageFault = true)
    }
  }

  it should "enforce U SUM MXR and Svade A D permission boundaries" in {
    simulate(new Sv32PageTableWalker) { dut =>
      initialize(dut)

      val va = BigInt("40800044", 16)
      val rootPpn = BigInt("180", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val pteAddress = (rootPpn << 12) + (vpn1 << 2)
      val megapagePpn = BigInt("140", 16) << 10

      issue(dut, va, rootPpn, PrivilegeMode.User, mxr = false)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, execute = true, user = true, accessed = true)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.User, mxr = true)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, execute = true, user = true, accessed = true)
      )
      finish(
        dut,
        physicalAddress = (BigInt("140", 16) << 22) |
          (((va >> 12) & 0x3ff) << 12) | (va & 0xfff),
        leafLevel = 1
      )

      issue(dut, va, rootPpn, PrivilegeMode.Supervisor, sum = false)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, read = true, user = true, accessed = true)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.Supervisor, sum = true)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, read = true, user = true, accessed = true)
      )
      finish(
        dut,
        physicalAddress = (BigInt("140", 16) << 22) |
          (((va >> 12) & 0x3ff) << 12) | (va & 0xfff),
        leafLevel = 1
      )

      issue(dut, va, rootPpn, PrivilegeMode.Supervisor, execute = true, sum = true)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, read = true, execute = true, user = true, accessed = true)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        pteAddress,
        pte(megapagePpn, read = true, user = true, accessed = false)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.User, write = true)
      providePte(
        dut,
        pteAddress,
        pte(
          megapagePpn,
          read = true,
          write = true,
          user = true,
          accessed = true,
          dirty = false
        )
      )
      finish(dut, pageFault = true)
    }
  }

  it should "reject invalid PTE encodings and a level-zero pointer" in {
    simulate(new Sv32PageTableWalker) { dut =>
      initialize(dut)

      val va = BigInt("40403024", 16)
      val rootPpn = BigInt("20000", 16)
      val nextPpn = BigInt("21000", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(BigInt("100", 16), valid = false, read = true)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(BigInt("100", 16), write = true)
      )
      finish(dut, pageFault = true)

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        pte(nextPpn)
      )
      providePte(
        dut,
        (nextPpn << 12) + (vpn0 << 2),
        pte(BigInt("22000", 16))
      )
      finish(dut, pageFault = true)
    }
  }

  it should "convert an implicit PTE memory failure into an access fault" in {
    simulate(new Sv32PageTableWalker) { dut =>
      initialize(dut)

      val va = BigInt("40403024", 16)
      val rootPpn = BigInt("20000", 16)
      val vpn1 = (va >> 22) & 0x3ff

      issue(dut, va, rootPpn, PrivilegeMode.User)
      providePte(
        dut,
        (rootPpn << 12) + (vpn1 << 2),
        value = 0,
        fault = true
      )
      finish(dut, accessFault = true)
    }
  }

  it should "bypass translation in Bare mode and preserve the full RV32 address" in {
    simulate(new Sv32TranslationUnit) { dut =>
      initializeTranslation(dut)
      val va = BigInt("fedcba98", 16)

      dut.io.virtualAddress.poke(va.U)
      dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
      dut.io.satpTranslationEnabled.poke(false.B)
      dut.io.requestValid.poke(true.B)
      dut.io.requestReady.expect(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)

      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(true.B)
      dut.io.physicalAddress.expect(va.U)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)

      dut.io.responseReady.poke(true.B)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)
      dut.io.requestReady.expect(true.B)
    }
  }

  it should "bypass Sv32 while executing in Machine mode" in {
    simulate(new Sv32TranslationUnit) { dut =>
      initializeTranslation(dut)
      val va = BigInt("81234560", 16)

      dut.io.virtualAddress.poke(va.U)
      dut.io.privilege.poke(PrivilegeMode.Machine.U)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(BigInt("3fffff", 16).U)
      dut.io.requestValid.poke(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)

      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(true.B)
      dut.io.physicalAddress.expect(va.U)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
    }
  }

  it should "route an active U-mode Sv32 request through the qualified walker" in {
    simulate(new Sv32TranslationUnit) { dut =>
      initializeTranslation(dut)

      val va = BigInt("40403024", 16)
      val rootPpn = BigInt("20000", 16)
      val nextPpn = BigInt("21000", 16)
      val leafPpn = BigInt("30001", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff

      dut.io.virtualAddress.poke(va.U)
      dut.io.privilege.poke(PrivilegeMode.User.U)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(rootPpn.U)
      dut.io.requestValid.poke(true.B)
      dut.io.requestReady.expect(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((rootPpn << 12) + (vpn1 << 2)).U)
      dut.io.pteData.poke(pte(nextPpn).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(((nextPpn << 12) + (vpn0 << 2)).U)
      dut.io.pteData.poke(
        pte(
          leafPpn,
          read = true,
          user = true,
          accessed = true
        ).U
      )
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.responseValid.expect(true.B)
      dut.io.physicalAddress.expect(((leafPpn << 12) | (va & 0xfff)).U)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.io.responseReady.poke(true.B)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)
      dut.io.requestReady.expect(true.B)
    }
  }
}
