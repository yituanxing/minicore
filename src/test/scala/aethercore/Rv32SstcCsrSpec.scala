package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, Rv32SstcCsrAddress, SupervisorCsrAddress}

class Rv32SstcCsrSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV32 Sstc CSR integration"

  private def initialize(dut: MachineCsrFile): Unit = {
    dut.io.readAddr.poke(0.U)
    dut.io.writeEnable.poke(false.B)
    dut.io.writeAddr.poke(0.U)
    dut.io.writeData.poke(0.U)
    dut.io.timerInterrupt.poke(false.B)
    dut.io.time.get.poke(0.U)
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

  private def enterSupervisor(dut: MachineCsrFile): Unit = {
    // MPP=S, then retire MRET through the same state transition used by the core.
    write(dut, MachineCsrAddress.Mstatus, BigInt("00000800", 16))
    dut.io.trapReturnSupervisor.poke(false.B)
    dut.io.trapReturn.poke(true.B)
    dut.clock.step()
    dut.io.trapReturn.poke(false.B)
    dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
  }

  it should "fail closed in S-mode until firmware enables TM and STCE" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imasuSv32Software.isa)) { dut =>
      initialize(dut)
      enterSupervisor(dut)

      dut.io.time.get.poke(BigInt("0000000100000020", 16).U)

      dut.io.readAddr.poke(Rv32SstcCsrAddress.Time.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)

      dut.io.readAddr.poke(SupervisorCsrAddress.Stimecmp.U)
      dut.io.readImplemented.expect(false.B)
      dut.io.readWritable.expect(false.B)

      dut.io.supervisorTimerPending.get.expect(false.B)
      dut.io.supervisorTimerInterrupt.get.expect(false.B)
    }
  }

  it should "expose time, program stimecmp and deliver delegated STIP in S-mode" in {
    simulate(new MachineCsrFile(CoreProfiles.rv32imasuSv32Software.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mcounteren, 0x2)
      write(dut, MachineCsrAddress.Menvcfgh, BigInt("80000000", 16))
      write(dut, MachineCsrAddress.Mideleg, 0x20)

      read(dut, MachineCsrAddress.Mcounteren) shouldBe 0x2
      read(dut, MachineCsrAddress.Menvcfgh) shouldBe BigInt("80000000", 16)
      read(dut, MachineCsrAddress.Mideleg) shouldBe 0x20

      enterSupervisor(dut)

      dut.io.time.get.poke(BigInt("0000000100000010", 16).U)
      read(dut, Rv32SstcCsrAddress.Time) shouldBe 0x10
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)
      read(dut, Rv32SstcCsrAddress.Timeh) shouldBe 0x1
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(false.B)

      // RV32 safe programming order: block low, update high, publish final low.
      write(dut, SupervisorCsrAddress.Stimecmp, BigInt("ffffffff", 16))
      write(dut, SupervisorCsrAddress.Stimecmph, 0x1)
      write(dut, SupervisorCsrAddress.Stimecmp, 0x20)
      read(dut, SupervisorCsrAddress.Stimecmp) shouldBe 0x20
      dut.io.readImplemented.expect(true.B)
      dut.io.readWritable.expect(true.B)
      read(dut, SupervisorCsrAddress.Stimecmph) shouldBe 0x1

      // Enable supervisor interrupt globally and STIE locally.
      write(dut, SupervisorCsrAddress.Sstatus, 0x2)
      write(dut, SupervisorCsrAddress.Sie, 0x20)
      read(dut, SupervisorCsrAddress.Sie) shouldBe 0x20

      dut.io.time.get.poke(BigInt("000000010000001f", 16).U)
      dut.io.supervisorTimerPending.get.expect(false.B)
      dut.io.supervisorTimerInterrupt.get.expect(false.B)

      dut.io.time.get.poke(BigInt("0000000100000020", 16).U)
      dut.io.supervisorTimerPending.get.expect(true.B)
      dut.io.supervisorTimerInterrupt.get.expect(true.B)
      read(dut, SupervisorCsrAddress.Sip) shouldBe 0x20
    }
  }
}
