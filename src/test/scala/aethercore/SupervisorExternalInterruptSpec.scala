package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrBit, MachineCsrFile, SupervisorCsrAddress}

class SupervisorExternalInterruptSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MachineCsrFile supervisor external interrupt"

  private val seip = BigInt(1) << MachineCsrBit.SupervisorExternalInterrupt

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.supervisorExternalInterruptPending.get.poke(false.B)
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

  private def enterSupervisor(dut: MachineCsrFile, supervisorInterruptEnable: Boolean): Unit = {
    val mppSupervisor = BigInt(PrivilegeMode.Supervisor) << MachineCsrBit.MstatusMppLow
    val sie = if (supervisorInterruptEnable) BigInt(1) << MachineCsrBit.SstatusSie else BigInt(0)
    write(dut, MachineCsrAddress.Mstatus, mppSupervisor | sie)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
  }

  it should "expose SEIP through mip/sip and qualify a delegated S-mode external interrupt" in {
    simulate(
      new MachineCsrFile(
        CoreProfiles.rv32imsuSv32Software.isa,
        withSupervisorExternalInterrupt = true
      )
    ) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mideleg, seip)
      read(dut, MachineCsrAddress.Mideleg) shouldBe seip
      write(dut, SupervisorCsrAddress.Sie, seip)
      read(dut, SupervisorCsrAddress.Sie) shouldBe seip

      dut.io.supervisorExternalInterruptPending.get.poke(true.B)
      read(dut, MachineCsrAddress.Mip) shouldBe seip
      read(dut, SupervisorCsrAddress.Sip) shouldBe seip
      dut.io.supervisorExternalInterrupt.get.expect(false.B)

      enterSupervisor(dut, supervisorInterruptEnable = true)
      dut.io.supervisorExternalInterrupt.get.expect(true.B)

      write(dut, SupervisorCsrAddress.Sstatus, 0)
      dut.io.supervisorExternalInterrupt.get.expect(false.B)
    }
  }

  it should "require mideleg.SEIP before delivering the external interrupt to S-mode" in {
    simulate(
      new MachineCsrFile(
        CoreProfiles.rv32imsuSv32Software.isa,
        withSupervisorExternalInterrupt = true
      )
    ) { dut =>
      initialize(dut)
      write(dut, MachineCsrAddress.Mie, seip)
      enterSupervisor(dut, supervisorInterruptEnable = true)
      dut.io.supervisorExternalInterruptPending.get.poke(true.B)

      read(dut, MachineCsrAddress.Mip) shouldBe seip
      read(dut, SupervisorCsrAddress.Sip) shouldBe 0
      dut.io.supervisorExternalInterrupt.get.expect(false.B)

      write(dut, MachineCsrAddress.Mideleg, seip)
      read(dut, SupervisorCsrAddress.Sip) shouldBe seip
      dut.io.supervisorExternalInterrupt.get.expect(true.B)
    }
  }
}
