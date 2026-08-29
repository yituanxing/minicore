package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.PrivilegeMode
import aethercore.config.CoreProfiles
import aethercore.core.{MachineCsrAddress, MachineCsrFile, SupervisorCsrAddress}

/**
  * First bounded RV64 privileged-mode contract.
  * RV64 第一层受控特权态合同：固定 SXL/UXL，并复用现有 M/S/U 状态机。
  */
class RV64SupervisorModeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RV64 M/S/U Supervisor V1"

  private val mstatusXlen64 = BigInt("0000000a00000000", 16)
  private val sstatusUxl64 = BigInt("0000000200000000", 16)

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

  it should "describe an RV64IM M/S/U profile without pulling later system features forward" in {
    val config = CoreProfiles.rv64imsuSoftware
    config.isa.xlen shouldBe 64
    config.isa.hasM shouldBe true
    config.isa.hasS shouldBe true
    config.isa.hasU shouldBe true
    config.isa.hasA shouldBe false
    config.isa.hasC shouldBe false
    config.isa.hasPmp shouldBe false
    config.isa.hasSv32 shouldBe false
    config.isa.hasSstc shouldBe false
    config.isa.hasZicsr shouldBe true
    config.isa.hasZifencei shouldBe false
    config.isa.march shouldBe "rv64im_zicsr"
    config.platform.busDataBits shouldBe 64
    config.platform.paddrBits shouldBe 64
  }

  it should "hold RV64 SXL and UXL at the implemented 64-bit WARL value" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imsuSoftware.isa)) { dut =>
      initialize(dut)

      read(dut, MachineCsrAddress.Misa) shouldBe BigInt("8000000000141100", 16)
      read(dut, MachineCsrAddress.Mstatus) shouldBe mstatusXlen64
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe sstatusUxl64

      write(dut, MachineCsrAddress.Mstatus, BigInt("ffffffffffffffff", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe (mstatusXlen64 | BigInt("00000000000219aa", 16))
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe (sstatusUxl64 | BigInt("0000000000000122", 16))

      write(dut, SupervisorCsrAddress.Sstatus, 0)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe sstatusUxl64
      write(dut, SupervisorCsrAddress.Sstatus, BigInt("ffffffffffffffff", 16))
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe (sstatusUxl64 | BigInt("122", 16))
    }
  }

  it should "preserve fixed XLEN state across MRET and SRET transitions" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imsuSoftware.isa)) { dut =>
      initialize(dut)

      write(dut, MachineCsrAddress.Mepc, BigInt("80000100", 16))
      write(dut, MachineCsrAddress.Mstatus, BigInt("880", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe (mstatusXlen64 | BigInt("880", 16))

      dut.io.trapReturn.poke(true.B)
      dut.clock.step()
      dut.io.trapReturn.poke(false.B)
      dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
      read(dut, MachineCsrAddress.Mstatus) shouldBe (mstatusXlen64 | BigInt("88", 16))
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe sstatusUxl64

      write(dut, SupervisorCsrAddress.Sepc, BigInt("80000200", 16))
      write(dut, SupervisorCsrAddress.Sstatus, 0)
      dut.io.trapReturnSupervisor.poke(true.B)
      dut.io.trapReturn.poke(true.B)
      dut.clock.step()
      dut.io.trapReturn.poke(false.B)
      dut.io.trapReturnSupervisor.poke(false.B)

      dut.io.currentPrivilege.expect(PrivilegeMode.User.U)
      read(dut, SupervisorCsrAddress.Sstatus) shouldBe (sstatusUxl64 | BigInt("20", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe (mstatusXlen64 | BigInt("a8", 16))
    }
  }

  it should "leave the historical RV64 M-only profile free of lower-mode XLEN state" in {
    simulate(new MachineCsrFile(CoreProfiles.rv64imCurrent.isa)) { dut =>
      initialize(dut)

      read(dut, MachineCsrAddress.Mstatus) shouldBe 0
      dut.io.readAddr.poke(SupervisorCsrAddress.Sstatus.U)
      dut.io.readImplemented.expect(false.B)

      write(dut, MachineCsrAddress.Mstatus, BigInt("ffffffffffffffff", 16))
      read(dut, MachineCsrAddress.Mstatus) shouldBe BigInt("1888", 16)
    }
  }
}
