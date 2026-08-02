package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile}

class PrivilegeModeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile privilege state"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
  }

  private def read(dut: MachineCsrFile, address: Int): BigInt = {
    dut.io.readAddr.poke(address.U)
    dut.io.readData.peek().litValue
  }

  private def write(dut: MachineCsrFile, address: Int, value: BigInt): Unit = {
    dut.io.writeAddr.poke(address.U)
    dut.io.writeData.poke(value.U)
    dut.io.writeEnable.poke(true.B)
    dut.clock.step()
    dut.io.writeEnable.poke(false.B)
  }

  private def mret(dut: MachineCsrFile): Unit = {
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
  }

  it should "reset in Machine mode and preserve Machine-only behavior" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imSoftware.isa)) { dut =>
      initialize(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)

      write(dut, MachineCsrAddress.Mstatus, 0)
      write(dut, MachineCsrAddress.Mepc, BigInt("80000100", 16))
      mret(dut)

      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00001880", 16)
    }
  }

  it should "enter User mode with MRET and stack User privilege on a trap" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imuSoftware.isa)) { dut =>
      initialize(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)

      // MPP=U and MPIE=1. MRET enters U, copies MPIE to MIE, sets MPIE,
      // and leaves MPP at the least supported privilege (U).
      write(dut, MachineCsrAddress.Mstatus, BigInt("80", 16))
      write(dut, MachineCsrAddress.Mepc, BigInt("80000103", 16))
      dut.io.returnPc.expect(BigInt("80000100", 16).U)
      mret(dut)

      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00000088", 16)

      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80000124", 16).U)
      dut.io.trapCause.poke(8.U)
      dut.io.trapValue.poke(0.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00000080", 16)
      read(dut, MachineCsrAddress.Mepc) shouldBe BigInt("80000124", 16)
      read(dut, MachineCsrAddress.Mcause) shouldBe 8

      mret(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      dut.io.returnPc.expect(BigInt("80000124", 16).U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00000088", 16)
    }
  }

  it should "enable a Machine timer interrupt automatically while running in User mode" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imuSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mie, BigInt("80", 16))
      write(dut, MachineCsrAddress.Mstatus, 0)
      write(dut, MachineCsrAddress.Mepc, BigInt("80000200", 16))
      mret(dut)

      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("00000080", 16)
      dut.io.timerInterrupt.poke(true.B)
      dut.io.machineTimerInterrupt.expect(true.B)
    }
  }
}
