package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.Sv32TranslationUnit

class Sv32TranslationUnitTlbSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32TranslationUnit TLB integration"

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

  private def initialize(dut: Sv32TranslationUnit): Unit = {
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

  private def request(dut: Sv32TranslationUnit, va: BigInt, root: BigInt): Unit = {
    dut.io.virtualAddress.poke(va.U)
    dut.io.satpRootPpn.poke(root.U)
    dut.io.requestValid.poke(true.B)
    dut.io.requestReady.expect(true.B)
    dut.clock.step()
    dut.io.requestValid.poke(false.B)
  }

  it should "refill after a successful walk, hit without PTW traffic, then miss after flush" in {
    simulate(new Sv32TranslationUnit(tlbEntries = 4)) { dut =>
      initialize(dut)
      val va = BigInt("40403024", 16)
      val secondVa = va + 0x100
      val root = BigInt("20000", 16)
      val next = BigInt("21000", 16)
      val leaf = BigInt("100001", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff
      val rootPte = (root << 12) + (vpn1 << 2)
      val leafPte = (next << 12) + (vpn0 << 2)
      val pa = (leaf << 12) | (va & 0xfff)

      request(dut, va, root)
      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(rootPte.U)
      dut.io.pteData.poke(pte(next).U)
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
      dut.io.responseReady.poke(true.B)
      dut.clock.step() // consume response and refill TLB exactly once
      dut.io.responseReady.poke(false.B)

      request(dut, secondVa, root)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(true.B)
      dut.io.physicalAddress.expect((pa + 0x100).U)
      dut.io.responseReady.poke(true.B)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      request(dut, va, root)
      dut.io.pteValid.expect(true.B)
      dut.io.pteAddress.expect(rootPte.U)
    }
  }

  it should "discard an in-flight walk when a flush arrives" in {
    simulate(new Sv32TranslationUnit(tlbEntries = 4)) { dut =>
      initialize(dut)
      val va = BigInt("40403024", 16)
      val root = BigInt("20000", 16)

      request(dut, va, root)
      dut.io.pteValid.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(false.B)
      dut.io.requestReady.expect(true.B)
    }
  }
}
