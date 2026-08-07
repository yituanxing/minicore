package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.core.{Sv32InstructionFetchAdapter, Sv32PtwArbiter}

class Sv32InstructionTranslationSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Sv32 instruction-side translation"

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      execute: Boolean = false,
      user: Boolean = false,
      accessed: Boolean = false,
      valid: Boolean = true
  ): BigInt =
    (ppn << 10) |
      (if (valid) BigInt(1) else BigInt(0)) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (user) BigInt(1) << 4 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  private def initialize(dut: Sv32InstructionFetchAdapter): Unit = {
    dut.io.requestValid.poke(false.B)
    dut.io.kill.poke(false.B)
    dut.io.virtualAddress.poke(0.U)
    dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
    dut.io.satpTranslationEnabled.poke(false.B)
    dut.io.satpRootPpn.poke(0.U)
    dut.io.mxr.poke(false.B)
    dut.io.pteReady.poke(false.B)
    dut.io.pteData.poke(0.U)
    dut.io.pteFault.poke(false.B)
    dut.io.responseReady.poke(false.B)
  }

  it should "translate an S-mode execute request through two Sv32 levels" in {
    simulate(new Sv32InstructionFetchAdapter) { dut =>
      initialize(dut)
      val va = BigInt("40403020", 16)
      val rootPpn = BigInt("20000", 16)
      val nextPpn = BigInt("21000", 16)
      val leafPpn = BigInt("100001", 16)
      val vpn1 = (va >> 22) & 0x3ff
      val vpn0 = (va >> 12) & 0x3ff

      dut.io.virtualAddress.poke(va.U)
      dut.io.privilege.poke(PrivilegeMode.Supervisor.U)
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
      dut.io.pteData.poke(pte(leafPpn, execute = true, accessed = true).U)
      dut.io.pteReady.poke(true.B)
      dut.clock.step()
      dut.io.pteReady.poke(false.B)

      dut.io.responseValid.expect(true.B)
      dut.io.pageFault.expect(false.B)
      dut.io.accessFault.expect(false.B)
      dut.io.physicalAddress.expect(((leafPpn << 12) | (va & 0xfff)).U)
      dut.io.responseReady.poke(true.B)
      dut.clock.step()
      dut.io.responseReady.poke(false.B)
      dut.io.requestReady.expect(true.B)
    }
  }

  it should "cancel a stale speculative page walk before it can produce a response" in {
    simulate(new Sv32InstructionFetchAdapter) { dut =>
      initialize(dut)
      val va = BigInt("40403020", 16)
      val rootPpn = BigInt("20000", 16)

      dut.io.virtualAddress.poke(va.U)
      dut.io.satpTranslationEnabled.poke(true.B)
      dut.io.satpRootPpn.poke(rootPpn.U)
      dut.io.requestValid.poke(true.B)
      dut.clock.step()
      dut.io.requestValid.poke(false.B)
      dut.io.pteValid.expect(true.B)

      dut.io.kill.poke(true.B)
      dut.clock.step()
      dut.io.pteValid.expect(false.B)
      dut.io.responseValid.expect(false.B)
      dut.io.kill.poke(false.B)
      dut.io.requestReady.expect(true.B)
    }
  }

  behavior of "Sv32PtwArbiter"

  it should "give an older data walk deterministic priority over speculative fetch" in {
    simulate(new Sv32PtwArbiter) { dut =>
      dut.io.dataValid.poke(true.B)
      dut.io.dataAddress.poke(BigInt("100001000", 16).U)
      dut.io.fetchValid.poke(true.B)
      dut.io.fetchAddress.poke(BigInt("200002000", 16).U)
      dut.io.memoryReady.poke(true.B)
      dut.io.memoryRdata.poke(BigInt("12345678", 16).U)
      dut.io.memoryFault.poke(true.B)

      dut.io.memoryValid.expect(true.B)
      dut.io.memoryAddress.expect(BigInt("100001000", 16).U)
      dut.io.dataReady.expect(true.B)
      dut.io.fetchReady.expect(false.B)
      dut.io.dataRdata.expect(BigInt("12345678", 16).U)
      dut.io.dataFault.expect(true.B)
      dut.io.fetchFault.expect(false.B)

      dut.io.dataValid.poke(false.B)
      dut.io.memoryFault.poke(false.B)
      dut.io.memoryAddress.expect(BigInt("200002000", 16).U)
      dut.io.dataReady.expect(false.B)
      dut.io.fetchReady.expect(true.B)
      dut.io.fetchRdata.expect(BigInt("12345678", 16).U)
      dut.io.fetchFault.expect(false.B)
    }
  }
}
