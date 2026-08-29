package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.Sv32Tlb

class Sv32TlbSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32Tlb"

  private def initialize(dut: Sv32Tlb): Unit = {
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

  private def refill(
      dut: Sv32Tlb,
      va: BigInt,
      pa: BigInt,
      rootPpn: BigInt,
      privilege: Int = PrivilegeMode.Supervisor,
      write: Boolean = false,
      execute: Boolean = false,
      sum: Boolean = false,
      mxr: Boolean = false,
      leafLevel: Int = 0,
      global: Boolean = false
  ): Unit = {
    dut.io.refillVirtualAddress.poke(va.U)
    dut.io.refillPhysicalAddress.poke(pa.U)
    dut.io.refillRootPpn.poke(rootPpn.U)
    dut.io.refillPrivilege.poke(privilege.U)
    dut.io.refillWrite.poke(write.B)
    dut.io.refillExecute.poke(execute.B)
    dut.io.refillSum.poke(sum.B)
    dut.io.refillMxr.poke(mxr.B)
    dut.io.refillLeafLevel.poke(leafLevel.U)
    dut.io.refillGlobal.poke(global.B)
    dut.io.refillValid.poke(true.B)
    dut.clock.step()
    dut.io.refillValid.poke(false.B)
  }

  private def lookup(
      dut: Sv32Tlb,
      va: BigInt,
      rootPpn: BigInt,
      privilege: Int = PrivilegeMode.Supervisor,
      write: Boolean = false,
      execute: Boolean = false,
      sum: Boolean = false,
      mxr: Boolean = false
  ): Unit = {
    dut.io.virtualAddress.poke(va.U)
    dut.io.rootPpn.poke(rootPpn.U)
    dut.io.privilege.poke(privilege.U)
    dut.io.write.poke(write.B)
    dut.io.execute.poke(execute.B)
    dut.io.sum.poke(sum.B)
    dut.io.mxr.poke(mxr.B)
    dut.io.lookupValid.poke(true.B)
  }

  it should "cache a 4 KiB translation without crossing permission or root contexts" in {
    simulate(new Sv32Tlb(entries = 4)) { dut =>
      initialize(dut)
      val va = BigInt("40403024", 16)
      val pa = BigInt("100001024", 16)
      val root = BigInt("20000", 16)

      lookup(dut, va, root)
      dut.io.hit.expect(false.B)

      refill(dut, va, pa, root, global = true)
      lookup(dut, va + 0x3c0, root)
      dut.io.hit.expect(true.B)
      dut.io.physicalAddress.expect((pa + 0x3c0).U)
      dut.io.leafLevel.expect(0.U)
      dut.io.global.expect(true.B)

      lookup(dut, va, root + 1)
      dut.io.hit.expect(false.B)

      lookup(dut, va, root, write = true)
      dut.io.hit.expect(false.B)

      lookup(dut, va, root, privilege = PrivilegeMode.User)
      dut.io.hit.expect(false.B)
    }
  }

  it should "reuse a megapage entry across VPN0 while preserving the physical offset" in {
    simulate(new Sv32Tlb(entries = 4)) { dut =>
      initialize(dut)
      val root = BigInt("20000", 16)
      val va = BigInt("80401020", 16)
      val pa = BigInt("140401020", 16)

      refill(dut, va, pa, root, execute = true, leafLevel = 1)

      val secondVa = BigInt("807ffabc", 16)
      val expectedPa = (pa & ~BigInt("3fffff", 16)) | (secondVa & BigInt("3fffff", 16))
      lookup(dut, secondVa, root, execute = true)
      dut.io.hit.expect(true.B)
      dut.io.physicalAddress.expect(expectedPa.U)
      dut.io.leafLevel.expect(1.U)
      dut.io.global.expect(false.B)

      lookup(dut, BigInt("80800000", 16), root, execute = true)
      dut.io.hit.expect(false.B)
    }
  }

  it should "invalidate every cached translation on the initial coarse SFENCE path" in {
    simulate(new Sv32Tlb(entries = 4)) { dut =>
      initialize(dut)
      val va = BigInt("40403024", 16)
      val pa = BigInt("100001024", 16)
      val root = BigInt("20000", 16)

      refill(dut, va, pa, root, sum = true, mxr = true)
      lookup(dut, va, root, sum = true, mxr = true)
      dut.io.hit.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      lookup(dut, va, root, sum = true, mxr = true)
      dut.io.hit.expect(false.B)
    }
  }
}
