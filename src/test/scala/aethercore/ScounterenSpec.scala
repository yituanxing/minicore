package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, Rv32SstcCsrAddress, SupervisorCsrAddress}

class ScounterenSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "scounteren"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.time.foreach(_.poke(BigInt("1122334455667788", 16).U(64.W)))
    dut.io.trapEnter.poke(false.B)
    dut.io.trapPc.poke(0.U)
    dut.io.trapCause.poke(0.U)
    dut.io.trapValue.poke(0.U)
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
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

  private def machineReturn(dut: MachineCsrFile): Unit = {
    dut.io.trapReturnSupervisor.poke(false.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
  }

  private def supervisorReturn(dut: MachineCsrFile): Unit = {
    dut.io.trapReturnSupervisor.poke(true.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.trapReturnSupervisor.poke(false.B)
  }

  it should "exist with Supervisor mode and WARL unsupported counter bits to zero" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imsuSoftware.isa)) { dut =>
      initialize(dut)

      read(dut, SupervisorCsrAddress.Scounteren) shouldBe 0
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)

      write(dut, SupervisorCsrAddress.Scounteren, 7)
      read(dut, SupervisorCsrAddress.Scounteren) shouldBe 0
    }
  }

  it should "retain only TM and require both counter-enable levels for U-mode time" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imasuSv32Software.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mcounteren, 7)
      read(dut, MachineCsrAddress.Mcounteren) shouldBe 2
      write(dut, SupervisorCsrAddress.Scounteren, 7)
      read(dut, SupervisorCsrAddress.Scounteren) shouldBe 2

      write(dut, SupervisorCsrAddress.Scounteren, 0)
      write(dut, MachineCsrAddress.Mstatus, BigInt(PrivilegeMode.Supervisor) << 11)
      machineReturn(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)

      read(dut, Rv32SstcCsrAddress.Time) shouldBe BigInt("55667788", 16)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)

      supervisorReturn(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      dut.io.readAddr.poke(Rv32SstcCsrAddress.Time.U)
      dut.io.readImplemented.expect(false.B)

      dut.io.trapEnter.poke(true.B)
      dut.io.trapPc.poke(BigInt("80400000", 16).U)
      dut.io.trapCause.poke(8.U)
      dut.clock.step()
      dut.io.trapEnter.poke(false.B)
      dut.io.currentPrivilege.expect(PrivilegeMode.Machine.U)

      write(dut, SupervisorCsrAddress.Scounteren, 2)
      write(dut, MachineCsrAddress.Mstatus, 0)
      machineReturn(dut)
      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)

      read(dut, Rv32SstcCsrAddress.Timeh) shouldBe BigInt("11223344", 16)
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
    }
  }
}
